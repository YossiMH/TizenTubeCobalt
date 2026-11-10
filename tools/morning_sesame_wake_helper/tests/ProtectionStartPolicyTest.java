package dev.yossi.morningsesame;

public final class ProtectionStartPolicyTest {
    private static int assertions;
    private static void check(boolean value, String message) {
        assertions++;
        if (!value) throw new AssertionError(message);
    }
    public static void main(String[] args) {
        String video = "EP2tuoBV184";
        check(ProtectionStartPolicy.durationFor("wake_requested", video, 3600) == 3600,
                "confirmed scheduled episode can start physical protection");
        for (String trigger : new String[] {"manual_play", "direct_play", "", "recovered"}) {
            check(ProtectionStartPolicy.durationFor(trigger, video, 3600) == 0,
                    "ordinary/manual playback cannot inherit daily protection: " + trigger);
        }
        check(ProtectionStartPolicy.durationFor(null, video, 3600) == 0, "null trigger rejected");
        check(ProtectionStartPolicy.durationFor("wake_requested", null, 3600) == 0, "null video rejected");
        check(ProtectionStartPolicy.durationFor("wake_requested", "wrong", 3600) == 0, "invalid video rejected");
        check(ProtectionStartPolicy.durationFor("wake_requested", video, 0) == 0, "unknown duration stays unarmed");
        check(ProtectionStartPolicy.durationFor("wake_requested", video, 1800) == 0, "short episode stays unarmed");
        check(ProtectionStartPolicy.durationFor("wake_requested", video, 1801) == 1801, "qualifying duration accepted");
        check(ProtectionStartPolicy.durationFor("wake_requested", video, 21600) == 21600, "finite six hour bound");
        check(ProtectionStartPolicy.durationFor("wake_requested", video, 21601) == 0, "unbounded duration rejected");
        check(ProtectionStartPolicy.durationFor("wake_requested", video, Integer.MAX_VALUE) == 0, "overflow rejected");
        System.out.println("ProtectionStartPolicyTest: " + assertions + " assertions passed");
    }
}
