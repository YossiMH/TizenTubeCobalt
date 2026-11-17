"""Offline safety and receipt checks; this module never opens a device connection."""
import copy
import importlib.util
import json
from pathlib import Path
import tempfile
import unittest


spec = importlib.util.spec_from_file_location("acceptance", Path(__file__).with_name("acceptance.py"))
acceptance = importlib.util.module_from_spec(spec)
spec.loader.exec_module(acceptance)


def baseline():
    return {"canonical": {"session": {"id": "", "video_id": "", "phase": "inactive",
                                      "expires_at_ms": -1, "duration_seconds": 0, "reason": "none"},
                          "rollout": "enabled", "session_mode": "scheduled",
                          "input_observer_status": "live", "input_observer_age_ms": 100,
                          "school_banner": {"phase": "inactive", "diagnostic": False,
                                            "overlay_visible": False, "protection_ready": False}},
            "controller": {"controller": "running", "state_source": "live", "native_process": "running",
                           "input_observer": "live", "native_health": "monitoring",
                           "session_phase": "inactive", "native_lease": "disarmed",
                           "native_physical_grabs": [], "recovery_in_progress": False}}


def visible(deadline):
    state = baseline()
    state["canonical"]["school_banner"].update(phase="active", diagnostic=True, run_id="diagnostic",
                                              expires_at_ms=deadline, protection_ready=True,
                                              overlay_visible=True, message=acceptance.MESSAGE)
    state["controller"].update(session_phase="school_banner", native_lease="armed",
                               native_physical_grabs=["event2"], native_health="physical_grabbed")
    return state


class FakeClient:
    def __init__(self, state):
        self.current = state
        self.events = []

    def state(self):
        return copy.deepcopy(self.current)

    def json(self, path, event):
        self.events.append((path, event))
        self.current = baseline()
        self.current["canonical"]["school_banner"]["last_diagnostic_result"] = "phone_dismissed"
        return {"schema": 1, "ok": True}


class AcceptanceSafetyTests(unittest.TestCase):
    def test_original_episode_active_is_rejected(self):
        state = baseline()
        state["canonical"]["session"]["phase"] = "active"
        with self.assertRaisesRegex(acceptance.CheckFailed, "real_episode_not_idle"):
            acceptance.idle(state)

    def test_waiting_morning_banner_is_rejected(self):
        state = baseline()
        state["canonical"]["school_banner"]["phase"] = "waiting"
        with self.assertRaisesRegex(acceptance.CheckFailed, "existing_banner_present"):
            acceptance.idle(state)

    def test_unreleased_lease_is_rejected(self):
        state = baseline()
        state["controller"]["native_physical_grabs"] = ["event2"]
        with self.assertRaisesRegex(acceptance.CheckFailed, "existing_native_lease_present"):
            acceptance.idle(state)

    def test_stale_native_proof_is_not_accepted(self):
        state = baseline()
        self.assertTrue(acceptance.proof_live(state))
        state["canonical"]["input_observer_age_ms"] = 4001
        self.assertFalse(acceptance.proof_live(state))

    def test_active_requires_exact_own_deadline_and_physical_grab(self):
        state = visible(acceptance.now_ms() + 20000)
        deadline = state["canonical"]["school_banner"]["expires_at_ms"]
        self.assertTrue(acceptance.active(state, deadline))
        self.assertFalse(acceptance.active(state, deadline + 1))
        state["controller"]["native_physical_grabs"] = []
        self.assertFalse(acceptance.active(state, deadline))

    def test_release_requires_exact_reason_and_native_release(self):
        state = baseline()
        state["canonical"]["school_banner"]["last_diagnostic_result"] = "expired"
        self.assertTrue(acceptance.released(state, "expired"))
        self.assertFalse(acceptance.released(state, "phone_dismissed"))
        state["controller"]["native_lease"] = "armed"
        self.assertFalse(acceptance.released(state, "expired"))

    def test_receipt_creation_is_exclusive(self):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "receipt.json"
            acceptance.Receipt(path)
            with self.assertRaises(FileExistsError):
                acceptance.Receipt(path)

    def test_synthetic_dismissal_balanced_and_not_replayed(self):
        with tempfile.TemporaryDirectory() as directory:
            receipt = acceptance.Receipt(Path(directory) / "receipt.json")
            receipt.value["before"] = baseline()
            receipt.value["diagnostic_deadline_ms"] = acceptance.now_ms() + 20000
            receipt.record("diagnostic_active")
            client = FakeClient(visible(receipt.value["diagnostic_deadline_ms"]))
            acceptance.dismiss(client, receipt)
            self.assertEqual([event[1]["value"] for event in client.events], [1, 0])
            self.assertEqual([event[1]["linux_code"] for event in client.events], [158, 158])
            saved = json.loads(receipt.path.read_text())
            self.assertTrue(saved["steps"][-1]["canonical_state_restored"])
            with self.assertRaisesRegex(acceptance.CheckFailed, "phone_submission_already_recorded"):
                acceptance.dismiss(client, receipt)
            self.assertEqual(len(client.events), 2)

    def test_changed_canonical_episode_prevents_synthetic_event(self):
        with tempfile.TemporaryDirectory() as directory:
            receipt = acceptance.Receipt(Path(directory) / "receipt.json")
            receipt.value["before"] = baseline()
            receipt.value["diagnostic_deadline_ms"] = acceptance.now_ms() + 20000
            receipt.record("diagnostic_active")
            state = visible(receipt.value["diagnostic_deadline_ms"])
            state["canonical"]["session"]["id"] = "new-real-morning"
            client = FakeClient(state)
            with self.assertRaisesRegex(acceptance.CheckFailed, "canonical_episode_or_configuration_changed"):
                acceptance.dismiss(client, receipt)
            self.assertEqual(client.events, [])

    def test_token_private_mode_required(self):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "controller.token"
            path.write_text("a" * 64)
            path.chmod(0o600)
            self.assertEqual(len(acceptance.read_token(path)), 64)
            path.chmod(0o644)
            with self.assertRaisesRegex(acceptance.CheckFailed, "private_token_file_required"):
                acceptance.read_token(path)


if __name__ == "__main__":
    unittest.main()
