import argparse
import asyncio
from dataclasses import asdict
import json
import os
from pathlib import Path
import signal

from .adapters import AdapterError, Android, SessionClient, read_token, store_token
from .controller import Config, Controller, RecoveryMode, control_request
from .media import MediaSource


def arguments():
    parser = argparse.ArgumentParser(description="Termux Onn Sesame session controller")
    parser.add_argument("--adb", default="adb", help="adb executable")
    parser.add_argument("--serial", default=os.environ.get("ONN_ADB_SERIAL"), help="adb serial; ONN_ADB_SERIAL if unset")
    parser.add_argument("--state-dir", type=Path, default=Path.home() / ".local/state/onn-session-policy")
    parser.add_argument("--token-file", type=Path, help="default: STATE_DIR/controller.token")
    parser.add_argument("--port", type=int, default=8768, help="canonical app loopback port")
    sub = parser.add_subparsers(dest="command", required=True)
    run = sub.add_parser("run", help="foreground controller; use Termux service for persistence")
    run.add_argument("--native-path", default="/data/local/tmp/onn-policy/onn_input_guard")
    run.add_argument("--player-port", type=int, default=8012)
    run.add_argument("--player-package", default="io.gh.yossim.tizentube.cobalt")
    run.add_argument("--recovery-mode", choices=[x.value for x in RecoveryMode], default="observe",
                     help="enable after real phone power classification is verified")
    run.add_argument("--media-source", choices=[x.value for x in MediaSource], default="auto")
    run.add_argument("--debugger-port", type=int, default=9222)
    sub.add_parser("get-token", help="obtain protected endpoint token and save privately")
    sub.add_parser("status", help="show fresh canonical app status")
    sub.add_parser("running", help="show this controller's own live status")
    sub.add_parser("stop", help="assistant override of current session, then stop owned controller")
    sub.add_parser("probe", help="read app health without waking")
    sub.add_parser("override", help="assistant override current session; keep controller monitoring")
    return parser.parse_args()


async def main(args):
    android = Android(args.adb, args.serial)
    token_file = args.token_file or args.state_dir / "controller.token"
    if args.command == "get-token":
        result = await android.admin("controller_token")
        store_token(token_file, result.get("token", ""))
        print(json.dumps({"schema": 1, "result": "token_saved"}))
    elif args.command == "status":
        snapshot = await SessionClient(read_token(token_file), args.port).status()
        print(json.dumps({"schema": 1, "state_source": "live", **asdict(snapshot)}))
    elif args.command == "running":
        print(json.dumps(await control_request(args.state_dir, "status")))
    elif args.command in ("probe", "override", "stop"):
        if args.command == "stop":
            failed = None
            try:
                result = await android.admin("override")
            except (AdapterError, OSError, asyncio.TimeoutError) as exc:
                failed = exc
                result = {"error":"app_override_failed"}
            result.pop("token", None)
            try:
                controller = await control_request(args.state_dir, "shutdown")
            except (OSError, asyncio.TimeoutError):
                controller = {"controller": "not_running"}
            print(json.dumps({"schema": 1, "app": result, **controller}))
            if failed is not None:
                raise SystemExit(1)
        else:
            result = await android.admin(args.command)
            result.pop("token", None)
            if args.command == "override":
                try:
                    await control_request(args.state_dir, "refresh")
                except (OSError, asyncio.TimeoutError):
                    pass
            print(json.dumps(result))
    elif args.command == "run":
        config = Config(args.state_dir, args.native_path, args.player_port,
                        args.player_package, RecoveryMode(args.recovery_mode),
                        MediaSource(args.media_source), args.debugger_port)
        controller = Controller(config, android, SessionClient(read_token(token_file), args.port))
        loop = asyncio.get_running_loop()
        for sig in (signal.SIGINT, signal.SIGTERM):
            loop.add_signal_handler(sig, controller.stop_requested.set)
        await controller.run()


if __name__ == "__main__":
    try:
        asyncio.run(main(arguments()))
    except (AdapterError, OSError, ValueError, asyncio.TimeoutError) as exc:
        # No raw exception text: credentials or command outputs are never echoed.
        kind = str(exc) if isinstance(exc, AdapterError) else type(exc).__name__
        print(json.dumps({"schema": 1, "error": kind}))
        raise SystemExit(1)
