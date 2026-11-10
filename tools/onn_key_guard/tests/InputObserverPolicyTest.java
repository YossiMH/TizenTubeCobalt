package dev.yossi.onnkeyguard;

import dev.yossi.onnkeyguard.InputObserverPolicy.Activation;
import dev.yossi.onnkeyguard.InputObserverPolicy.Health;
import dev.yossi.onnkeyguard.InputObserverPolicy.Proof;
import dev.yossi.onnkeyguard.InputObserverPolicy.State;

/** Real provenance-fence transitions; no process/device replacements. */
public final class InputObserverPolicyTest {
    private static int assertions;

    public static void main(String[] args) {
        proofAndStartupContinuity();
        failuresAndExpiry();
        prestartPhoneIntent();
        processStateIsNeverRestored();
        delayedProofCannotCrossFault();
        System.out.println("PASS InputObserverPolicyTest: " + assertions + " assertions");
    }

    private static void proofAndStartupContinuity() {
        InputObserverPolicy p = new InputObserverPolicy();
        equal(State.UNKNOWN, p.status(0).state, "new process has no proof");
        equal(Activation.UNPROVEN, p.activation(0), "unproven enrollment denied");
        truth(!p.isLive(0), "unknown monitor cannot arm");
        for (Health health : new Health[] {Health.STARTING, Health.MONITORING, Health.PHYSICAL_GRABBED,
                Health.LEASE_ARMED, Health.DISARMED}) {
            p.observeHealth(health, Proof.NONE, 10);
            truth(!p.isLive(10), "stored/coalesced state is not proof: " + health);
        }
        p.observeHealth(Health.MONITORING, Proof.NATIVE_STATUS, 1000);
        truth(p.isLive(1000), "fresh actual STATUS proves monitor");
        equal(Activation.WARMING_UP, p.activation(1000), "daily enrollment waits for observed prestart history");
        p.observeHealth(Health.MONITORING, Proof.NATIVE_STATUS, 4000);
        p.observeHealth(Health.MONITORING, Proof.NATIVE_STATUS, 7000);
        p.observeHealth(Health.MONITORING, Proof.NATIVE_STATUS, 10000);
        equal(Activation.ALLOWED, p.activation(10000), "continuous fresh passive observer permits enrollment");
        equal(Long.valueOf(2000), p.status(12000).ageMs, "age uses elapsed receipt time");
        equal(Activation.STALE_PROOF, p.activation(14001), "new starts require stricter fresh proof");
        truth(p.isLive(14001), "existing run retains bounded heartbeat grace");
    }

    private static void failuresAndExpiry() {
        InputObserverPolicy p = new InputObserverPolicy();
        p.observeHealth(Health.MONITORING, Proof.NATIVE_STATUS, 1000);
        truth(p.isLive(12999), "heartbeat valid before twelve seconds");
        equal(State.LOST, p.status(13000).state, "twelve-second missing heartbeat loses proof");
        p.observeHealth(Health.MONITORING, Proof.NONE, 13100);
        truth(!p.isLive(13100), "stored monitoring state cannot renew lost proof");
        p.observeHealth(Health.PHYSICAL_GRABBED, Proof.NATIVE_STATUS, 14000);
        equal(Activation.WARMING_UP, p.activation(14000), "fresh recovered observer starts a new continuity window");
        InputObserverPolicy.Snapshot before = p.status(14000);
        p.observeHealth(Health.UNAVAILABLE, Proof.NONE, 14001);
        truth(!p.isLive(14001), "native EOF/device fault immediately loses proof");
        equal(State.LIVE, before.state, "prior snapshot remains immutable");
        p.observeHealth(Health.MONITORING, Proof.NATIVE_STATUS, 15000);
        p.observeHealth(Health.STARTING, Proof.NONE, 15001);
        truth(!p.isLive(15001), "observer restart invalidates old proof");
        p.observeHealth(Health.MONITORING, Proof.NATIVE_STATUS, 16000);
        p.observeHealth(Health.DISARMED, Proof.NONE, 16001);
        truth(p.isLive(16001), "releasing physical grab does not kill persistent passive observer");
        p.observeHealth(Health.STOPPED, Proof.NONE, 16002);
        truth(!p.isLive(16002), "stopped observer invalidates immediately");
        p.observeHealth(Health.MONITORING, Proof.NATIVE_STATUS, 17000);
        truth(!p.isLive(16999), "elapsed-clock reversal cannot preserve false freshness");
    }

    private static void prestartPhoneIntent() {
        InputObserverPolicy p = new InputObserverPolicy();
        p.observeHealth(Health.MONITORING, Proof.NATIVE_STATUS, 0);
        p.observeHealth(Health.MONITORING, Proof.NATIVE_STATUS, 3000);
        p.observeHealth(Health.MONITORING, Proof.NATIVE_STATUS, 6000);
        p.observePhoneKey(116, 1, 8000); // The session does not exist yet.
        p.observeHealth(Health.MONITORING, Proof.NATIVE_STATUS, 9000);
        equal(Activation.RECENT_PHONE, p.activation(9000), "phone power just before enrollment is remembered");
        truth(p.isLive(9000), "explicit DUMP test may separately authorize a bounded test with live proof");
        p.observeHealth(Health.MONITORING, Proof.NATIVE_STATUS, 12000);
        p.observeHealth(Health.MONITORING, Proof.NATIVE_STATUS, 15000);
        for (int volume : new int[] {113, 114, 115}) p.observePhoneKey(volume, 1, 16000);
        equal(Activation.RECENT_PHONE, p.activation(16000), "prestart boundary inclusive");
        equal(Activation.ALLOWED, p.activation(16001), "volume never extends prestart intent fence");
        p.observePhoneKey(28, 0, 16002);
        equal(Activation.ALLOWED, p.activation(16002), "key release does not manufacture intent");
        p.observePhoneKey(28, 1, 16003);
        equal(Activation.RECENT_PHONE, p.activation(16003), "generic inactive key also fences enrollment");
        p.observeHealth(Health.MONITORING, Proof.NATIVE_STATUS, 19000);
        p.observeHealth(Health.MONITORING, Proof.NATIVE_STATUS, 22000);
        p.observePhoneKey(28, 2, 23900);
        p.observeHealth(Health.MONITORING, Proof.NATIVE_STATUS, 25000);
        equal(Activation.RECENT_PHONE, p.activation(25000), "held phone key remains a prestart intent");
    }

    private static void delayedProofCannotCrossFault() {
        InputObserverPolicy p = new InputObserverPolicy();
        truth(p.observeHealth(Health.MONITORING, Proof.NATIVE_STATUS, 1000, 10000, 10000), "initial timed proof accepted");
        p.observeHealth(Health.UNAVAILABLE, Proof.NONE, 2000, -1, 11000);
        truth(!p.observeHealth(Health.MONITORING, Proof.NATIVE_STATUS, 2100, 10999, 11100), "delayed pre-fault sample rejected");
        truth(!p.observeHealth(Health.MONITORING, Proof.NATIVE_STATUS, 2200, 11000, 11200), "proof at fault cutoff rejected");
        truth(!p.isLive(2200), "delayed HTTP proof cannot relabel monitor live");
        truth(p.observeHealth(Health.MONITORING, Proof.NATIVE_STATUS, 2300, 11200, 11300), "new actual post-fault proof accepted");
        equal(Activation.WARMING_UP, p.activation(2300), "new proof does not bypass continuity warmup");
        truth(!p.observeHealth(Health.MONITORING, Proof.NATIVE_STATUS, 2400, 11200, 11400), "replayed sample cannot renew proof timestamp");
    }

    private static void processStateIsNeverRestored() {
        InputObserverPolicy previous = new InputObserverPolicy();
        previous.observeHealth(Health.MONITORING, Proof.NATIVE_STATUS, 10000);
        truth(previous.isLive(10000), "old process had proof");
        InputObserverPolicy restarted = new InputObserverPolicy();
        equal(State.UNKNOWN, restarted.status(10001).state, "new process cannot reuse old health");
        truth(restarted.status(10001).ageMs == null, "no fabricated heartbeat timestamp after restart");
        restarted.observePhoneKey(115, 1, 10001);
        equal(Activation.UNPROVEN, restarted.activation(10001), "volume cannot authorize observer or activation");
    }

    private static void truth(boolean condition, String label) {
        assertions++;
        if (!condition) throw new AssertionError(label);
    }

    private static void equal(Object expected, Object actual, String label) {
        assertions++;
        if (!expected.equals(actual)) throw new AssertionError(label + ": expected " + expected + ", got " + actual);
    }
}
