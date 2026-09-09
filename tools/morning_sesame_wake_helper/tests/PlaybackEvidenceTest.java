package dev.yossi.morningsesame;

public final class PlaybackEvidenceTest {
    private static final String VIDEO = "Abcde123_-X";
    private static String xml(String v, long at, double position, String state) {
        return "<service><yumi>morning-sesame</yumi><ms_state>" + state
                + "</ms_state><ms_playing>" + v + "</ms_playing><ms_playing_at>"
                + at + "</ms_playing_at><ms_position>" + position + "</ms_position></service>";
    }
    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
    public static void main(String[] args) {
        long run = 100000L, now = 103000L;
        PlaybackEvidence a = PlaybackEvidence.read(xml(VIDEO, 102000L, 2.0, "playing"), VIDEO, run, now);
        PlaybackEvidence b = PlaybackEvidence.read(xml(VIDEO, 104000L, 4.0, "playing"), VIDEO, run, 105000L);
        check(PlaybackEvidence.advances(a,b), "fresh advancing video should pass");
        check(!PlaybackEvidence.advances(a,a), "cached repeated evidence must fail");
        check(!PlaybackEvidence.advances(a,PlaybackEvidence.read(xml(VIDEO,104000L,2.0,"playing"),VIDEO,run,105000L)), "frozen position must fail");
        check(PlaybackEvidence.read(xml(VIDEO,99999L,2.0,"playing"),VIDEO,run,now)==null,"previous run must fail");
        check(PlaybackEvidence.read(xml(VIDEO,102000L,2.0,"playing"),VIDEO,run,108000L)==null,"stale heartbeat must fail");
        check(PlaybackEvidence.read(xml(VIDEO,110000L,2.0,"playing"),VIDEO,run,now)==null,"future timestamp must fail");
        check(PlaybackEvidence.read(xml("Other123_-X",102000L,2.0,"playing"),VIDEO,run,now)==null,"wrong episode must fail");
        check(PlaybackEvidence.read(xml(VIDEO,102000L,2.0,"idle"),VIDEO,run,now)==null,"paused/idle must fail");
        check(PlaybackEvidence.read(xml(VIDEO,102000L,2.0,"playing"),VIDEO,0,now)==null,"uninitialized run must fail");
        check(PlaybackEvidence.read("<ms_playing>"+VIDEO+"</ms_playing>",VIDEO,run,now)==null,"partial evidence must fail");
        check(PlaybackEvidence.read(xml(VIDEO,102000L,Double.NaN,"playing"),VIDEO,run,now)==null,"NaN position must fail");
        check(PlaybackEvidence.read(xml(VIDEO,102000L,-1.0,"playing"),VIDEO,run,now)==null,"negative position must fail");
        System.out.println("PlaybackEvidence: 12 playback confirmation cases passed.");
    }
}
