import unittest
from onn_session_policy.native_protocol import (
    MonitorFault, MonitorPhase, Proof, event, observer_live, requested,
    started, status_due, watchdog,
)


PHONE = {"kind":"device", "source":"phone", "input_id":7, "name":"virtual-remote",
         "bus":6, "vendor":0x18d1, "product":0x0100}


def reply(boot=1, lease="disarmed"):
    return {"kind":"status", "lease":lease, "volume":"absent", "boottime_ms":boot}


def proven():
    state = event(started(1000), PHONE, 1001).state
    return event(requested(state, 1002), reply(), 1003).state


class NativeProtocolTests(unittest.TestCase):
    def test_ready_and_process_start_do_not_prove_phone_source(self):
        state = event(started(1000), {"kind":"ready"}, 1001).state
        result = event(requested(state,1002), reply(),1003)
        self.assertEqual(Proof.NONE,result.proof)
        self.assertFalse(observer_live(result.state,1003))
        self.assertEqual(MonitorFault.SOURCE_BLIND,watchdog(result.state,6001).fault)

    def test_positive_phone_discovery_and_solicited_reply_prove_passive_observer(self):
        state = event(started(1000),PHONE,1001).state
        result = event(requested(state,1002),reply(),1003)
        self.assertEqual(Proof.NATIVE_STATUS,result.proof)
        self.assertTrue(observer_live(result.state,1003))
        self.assertEqual("disarmed",result.state.lease.value)
        self.assertFalse(status_due(result.state,4001))
        self.assertTrue(status_due(result.state,4002))

    def test_only_verified_phone_identity_can_establish_source_readiness(self):
        for change in ({"source":"other_power"},{"vendor":0},{"bus":5},{"name":"network-remote"}):
            state = event(started(1000),{**PHONE,**change},1001).state
            self.assertFalse(state.phone_inputs)

    def test_stale_reply_unsolicited_reply_and_duplicate_boot_are_rejected(self):
        state = event(started(1000),PHONE,1001).state
        self.assertEqual(MonitorFault.PROTOCOL,event(state,reply(),1002).fault)
        self.assertEqual(MonitorFault.PROTOCOL,event(requested(state,1002),reply(),5003).fault)
        state = proven()
        self.assertEqual(MonitorFault.PROTOCOL,event(requested(state,4003),reply(),4004).fault)

    def test_regular_fresh_statuses_are_new_proof_even_without_state_change(self):
        state = proven()
        for now,boot in ((4003,2),(7003,3),(10003,4)):
            result = event(requested(state,now),reply(boot),now+1)
            self.assertEqual(Proof.NATIVE_STATUS,result.proof)
            self.assertTrue(observer_live(result.state,now+1))
            state=result.state

    def test_stale_observer_and_missing_reply_are_real_faults(self):
        self.assertEqual(MonitorFault.STALE,watchdog(proven(),6004).fault)
        state = event(started(1000),PHONE,1001).state
        self.assertEqual(MonitorFault.STATUS_TIMEOUT,watchdog(requested(state,1001),5002).fault)

    def test_removed_or_new_phone_source_terminally_invalidates_existing_proof(self):
        for value in ({"kind":"device_removed","source":"phone","input_id":7},
                      {**PHONE,"input_id":8}):
            result=event(proven(),value,1100)
            self.assertEqual(MonitorPhase.LOST,result.state.phase)
            self.assertFalse(observer_live(result.state,1100))
            self.assertNotEqual(MonitorFault.NONE,result.fault)

    def test_disarming_keeps_passive_observer_and_phone_proof(self):
        state=event(proven(),{"kind":"grabbed","source":"physical","input_id":6},1100).state
        state=event(state,{"kind":"disarmed"},1200).state
        self.assertTrue(observer_live(state,1200))
        self.assertEqual(("7",),state.phone_inputs)
        self.assertEqual((),state.physical_grabs)


if __name__=="__main__":
    unittest.main()
