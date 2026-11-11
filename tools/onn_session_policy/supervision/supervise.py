#!/usr/bin/env python3
"""Owned TermuxBoot supervision. Staged configuration is disabled by default."""
import argparse
from dataclasses import dataclass
from enum import Enum
import fcntl
import json
import os
from pathlib import Path
import selectors
import signal
import socket
import stat
import subprocess
import sys
import threading
import time


class Activation(str, Enum):
    DISABLED = "disabled"
    ENABLED = "enabled"


class Phase(str, Enum):
    STARTING = "starting"
    WAITING_ADB = "waiting_adb"
    RUNNING = "running"
    BACKOFF = "backoff"
    STOPPING = "stopping"


class ServiceError(RuntimeError):
    pass


def mono():
    return time.monotonic()


def backoff(failures):
    return min(60, 5 * 2 ** min(max(0, failures - 1), 4))


def private_directory(path):
    path.mkdir(mode=0o700, parents=True, exist_ok=True)
    info = path.lstat()
    if not stat.S_ISDIR(info.st_mode) or info.st_uid != os.getuid() or info.st_mode & 0o077:
        raise ServiceError("private_state_directory_required")


def private_file(path):
    info = path.lstat()
    if not stat.S_ISREG(info.st_mode) or info.st_uid != os.getuid() or info.st_mode & 0o077:
        raise ServiceError("private_file_required")


@dataclass(frozen=True)
class Config:
    activation: Activation
    python: str
    entry: str
    adb: str
    serial: str
    native: str
    controller_state: str
    token_file: str
    supervisor_state: str
    recovery_mode: str
    media_source: str

    def command(self):
        return [self.python, "-u", self.entry, "--adb", self.adb, "--serial", self.serial,
                "--state-dir", self.controller_state, "--token-file", self.token_file,
                "run", "--native-path", self.native, "--recovery-mode", self.recovery_mode,
                "--media-source", self.media_source]


def load_config(path):
    private_file(path)
    if path.stat().st_size > 8192:
        raise ServiceError("config_too_large")
    value = json.loads(path.read_text())
    if not isinstance(value, dict) or type(value.get("schema")) is not int or value["schema"] != 1:
        raise ServiceError("config_schema_invalid")
    keys = ("python", "entry", "adb", "serial", "native", "controller_state",
            "token_file", "supervisor_state", "recovery_mode", "media_source")
    for key in keys:
        item = value.get(key)
        if not isinstance(item, str) or not item or len(item) > 512 or any(ord(c) < 32 for c in item):
            raise ServiceError("config_field_invalid")
        if key not in ("serial", "recovery_mode", "media_source") and not Path(item).is_absolute():
            raise ServiceError("absolute_paths_required")
    if value["recovery_mode"] not in ("observe", "enabled") or value["media_source"] not in ("auto", "dial", "cdp"):
        raise ServiceError("config_mode_invalid")
    return Config(Activation(value.get("activation")), *(value[k] for k in keys))


class BoundedLog:
    """Three files of at most 64 KiB; raw child output and secrets are omitted."""
    LIMIT = 65_536
    KEYS = frozenset(("event", "wall_ms", "reason", "result", "phase", "state_source",
                      "recovery_mode", "source", "exit_code", "delay_seconds", "child_pid",
                      "uptime_seconds", "bytes"))
    KEYS = KEYS | frozenset(("operation", "failure_type", "output_status"))

    def __init__(self, directory):
        self.directory = directory
        self.path = directory / "service.jsonl"

    def write(self, value):
        safe = {key: item[:160] if isinstance(item, str) else item for key, item in value.items()
                if key in self.KEYS and type(item) in (str, int, float)}
        safe.setdefault("wall_ms", time.time_ns() // 1_000_000)
        data = (json.dumps(safe, separators=(",", ":"), allow_nan=False) + "\n").encode()
        if self.path.exists() and self.path.stat().st_size + len(data) > self.LIMIT:
            oldest = self.directory / "service.jsonl.2"
            oldest.unlink(missing_ok=True)
            previous = self.directory / "service.jsonl.1"
            if previous.exists():
                previous.replace(oldest)
            self.path.replace(previous)
        descriptor = os.open(self.path, os.O_WRONLY | os.O_CREAT | os.O_APPEND | os.O_NOFOLLOW, 0o600)
        with os.fdopen(descriptor, "ab") as stream:
            stream.write(data)

    def child_line(self, data):
        try:
            value = json.loads(data)
            if not isinstance(value, dict):
                raise ValueError("not_object")
            self.write(value)
        except (ValueError, UnicodeError):
            self.write({"event": "controller_output_omitted", "bytes": len(data)})


class OwnedChild:
    def __init__(self, command):
        self.process = subprocess.Popen(command, stdin=subprocess.DEVNULL, stdout=subprocess.PIPE,
                                        stderr=subprocess.STDOUT, start_new_session=True, bufsize=0)
        self.started_at = mono()

    def stop(self, grace=8):
        process = self.process
        if process.poll() is None:
            process.terminate()  # Exact child handle; controller releases its helper.
            try:
                process.wait(timeout=grace)
            except subprocess.TimeoutExpired:
                # This process group was created by this Popen, never discovered
                # by name, adopted, or read from a stale PID file.
                if process.poll() is None and os.getpgid(process.pid) == process.pid:
                    os.killpg(process.pid, signal.SIGKILL)
                elif process.poll() is None:
                    process.kill()
                process.wait(timeout=2)
        else:
            process.wait()
        process.stdout.close()


class Supervisor:
    def __init__(self, config, config_path):
        self.config, self.config_path = config, config_path
        self.phase = Phase.STARTING
        self.stop_requested = threading.Event()
        self.child = None
        self.failures = 0
        self.next_start = 0.0
        self.last_exit = None
        self.started_count = 0
        self.buffer = b""
        self.selector = selectors.DefaultSelector()

    def status(self):
        return {"schema": 1, "phase": Phase.STOPPING.value if self.stop_requested.is_set() else self.phase.value,
                "child_pid": self.child.process.pid if self.child else None,
                "starts": self.started_count, "last_exit_code": self.last_exit,
                "restart_in_seconds": round(max(0, self.next_start - mono()), 1)}

    def _snapshot(self, directory):
        temporary = directory / "status.json.tmp"
        descriptor = os.open(temporary, os.O_WRONLY | os.O_CREAT | os.O_TRUNC | os.O_NOFOLLOW, 0o600)
        with os.fdopen(descriptor, "w") as stream:
            json.dump({**self.status(), "state_source":"cache", "sample_wall_ms":time.time_ns() // 1_000_000}, stream)
        temporary.replace(directory / "status.json")

    def _adb_ready(self):
        try:
            result = subprocess.run([self.config.adb, "-s", self.config.serial, "get-state"],
                stdin=subprocess.DEVNULL, capture_output=True, timeout=4)
            return result.returncode == 0 and result.stdout.strip() == b"device"
        except (OSError, subprocess.TimeoutExpired):
            return False

    def _wait_to_restart(self, log):
        if self.stop_requested.is_set():
            return
        self.failures += 1
        delay = backoff(self.failures)
        self.next_start = mono() + delay
        self.phase = Phase.BACKOFF
        log.write({"event": "restart_backoff", "delay_seconds": delay})

    def _control(self, listener):
        connection, _ = listener.accept()
        try:
            connection.settimeout(1)
            command = connection.recv(128).decode("ascii").strip()
            if command == "stop":
                self.stop_requested.set()
                self.phase = Phase.STOPPING
            elif command != "status":
                raise ServiceError("unknown_control_command")
            connection.sendall((json.dumps(self.status()) + "\n").encode())
        finally:
            connection.close()

    def _output(self, stream, log):
        data = os.read(stream.fileno(), 4096)
        if not data:
            self.selector.unregister(stream)
            return
        self.buffer += data
        while b"\n" in self.buffer:
            line, self.buffer = self.buffer.split(b"\n", 1)
            log.child_line(line) if len(line) <= 4096 else log.write({"event":"controller_output_omitted", "bytes":len(line)})
        if len(self.buffer) > 8192:
            log.write({"event":"controller_output_omitted", "bytes":len(self.buffer)})
            self.buffer = b""

    def run(self):
        if self.config.activation != Activation.ENABLED:
            print(json.dumps({"schema":1, "result":"disabled"}))
            return
        private_file(Path(self.config.token_file))  # Stat only; never copy or log token.
        directory = Path(self.config.supervisor_state)
        private_directory(directory)
        lock = os.open(directory / "supervisor.lock", os.O_RDWR | os.O_CREAT | os.O_NOFOLLOW, 0o600)
        listener = None
        path = directory / "supervisor.sock"
        previous_handlers = {}
        try:
            try:
                fcntl.flock(lock, fcntl.LOCK_EX | fcntl.LOCK_NB)
            except BlockingIOError as exc:
                raise ServiceError("supervisor_already_running") from exc
            if path.exists():
                info = path.lstat()
                if not stat.S_ISSOCK(info.st_mode) or info.st_uid != os.getuid():
                    raise ServiceError("unsafe_control_path")
                path.unlink()
            listener = socket.socket(socket.AF_UNIX, socket.SOCK_STREAM)
            listener.bind(str(path)); path.chmod(0o600)
            listener.listen(4); listener.setblocking(False)
            self.selector.register(listener, selectors.EVENT_READ, "control")
            for number in (signal.SIGINT, signal.SIGTERM):
                previous_handlers[number] = signal.signal(number, lambda *_: self.stop_requested.set())
            log = BoundedLog(directory)
            log.write({"event":"supervisor_started"})
            last_config = last_audit = 0.0
            while not self.stop_requested.is_set():
                now = mono()
                if now - last_config >= 5:
                    if load_config(self.config_path) != self.config:
                        self.stop_requested.set()
                        self.phase = Phase.STOPPING  # Disable/edit requires clean restart.
                        break
                    last_config = now
                if self.child and self.child.process.poll() is not None:
                    self.last_exit = self.child.process.returncode
                    uptime = now - self.child.started_at
                    log.write({"event":"controller_exited", "exit_code":self.last_exit, "uptime_seconds":round(uptime, 1)})
                    try:
                        self.selector.unregister(self.child.process.stdout)
                    except KeyError:
                        pass
                    self.child.stop(); self.child = None; self.buffer = b""
                    if uptime >= 120:
                        self.failures = 0
                    self._wait_to_restart(log)
                if self.child is None and now >= self.next_start:
                    self.phase = Phase.WAITING_ADB
                    ready = self._adb_ready()
                    if self.stop_requested.is_set():
                        break
                    if ready:
                        try:
                            self.child = OwnedChild(self.config.command())
                            if self.stop_requested.is_set():
                                self.child.stop(); self.child = None
                                break
                            os.set_blocking(self.child.process.stdout.fileno(), False)
                            self.selector.register(self.child.process.stdout, selectors.EVENT_READ, "child")
                            self.started_count += 1; self.phase = Phase.RUNNING
                            log.write({"event":"controller_started", "child_pid":self.child.process.pid})
                        except OSError:
                            log.write({"event":"controller_spawn_failed"})
                            self._wait_to_restart(log)
                    else:
                        log.write({"event":"adb_unavailable"})
                        self._wait_to_restart(log)
                if now - last_audit >= 60:
                    self._snapshot(directory)
                    log.write({"event":"supervisor_audit", "phase":self.phase.value})
                    last_audit = now
                for key, _ in self.selector.select(0.5):
                    try:
                        self._control(listener) if key.data == "control" else self._output(key.fileobj, log)
                    except (OSError, UnicodeError, ServiceError):
                        log.write({"event":"control_or_output_rejected"})
        finally:
            self.stop_requested.set()
            self.phase = Phase.STOPPING
            if self.child:
                self.child.stop(); self.child = None
            if listener:
                self._snapshot(directory)
                BoundedLog(directory).write({"event":"supervisor_stopped"})
                listener.close(); path.unlink(missing_ok=True)
            self.selector.close()
            for number, previous in previous_handlers.items():
                signal.signal(number, previous)
            os.close(lock)


def control(config, command):
    connection = socket.socket(socket.AF_UNIX, socket.SOCK_STREAM)
    try:
        connection.settimeout(2)
        connection.connect(str(Path(config.supervisor_state) / "supervisor.sock"))
        connection.sendall((command + "\n").encode())
        print(connection.recv(4096).decode().strip())
    finally:
        connection.close()


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--config", type=Path, required=True)
    parser.add_argument("command", choices=("run", "status", "stop", "validate"))
    args = parser.parse_args()
    config = load_config(args.config)
    if args.command == "validate":
        print(json.dumps({"schema":1, "result":"valid", "activation":config.activation.value}))
    elif args.command == "run":
        Supervisor(config, args.config).run()
    else:
        control(config, args.command)


if __name__ == "__main__":
    try:
        main()
    except (ServiceError, OSError, ValueError) as exc:
        print(json.dumps({"schema":1, "error":str(exc) if isinstance(exc, ServiceError) else type(exc).__name__}))
        sys.exit(1)
