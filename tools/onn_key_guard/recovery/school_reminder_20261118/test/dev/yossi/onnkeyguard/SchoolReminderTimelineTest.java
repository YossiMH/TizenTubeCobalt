package dev.yossi.onnkeyguard;

public final class SchoolReminderTimelineTest {
    private static int checks;
    private static void check(boolean value, String reason) {
        checks++;
        if (!value) throw new AssertionError(reason);
    }
    private static void equal(float expected, float actual, String reason) {
        check(Math.abs(expected - actual) < 0.0001f, reason + ": " + actual);
    }
    private static SchoolReminderTimeline clock(final long... holds) {
        return new SchoolReminderTimeline(new SchoolReminderTimeline.HoldSource() {
            int next;
            @Override public long nextHoldMs() { return holds[Math.min(next++, holds.length - 1)]; }
        });
    }
    private static void stopped(SchoolReminderTimeline.Frame f, String reason) {
        check(!f.musicPlaying && !f.videoPlaying, reason);
    }
    public static void main(String[] args) {
        SchoolReminderTimeline t = clock(30000, 45000, 37500);
        stopped(t.frame(0), "loading has no audio");
        check(!t.videoCompleted(0), "loading rejects completion");
        t.ready(1000);
        SchoolReminderTimeline.Frame f = t.frame(1000);
        check(f.phase == SchoolReminderTimeline.Phase.HOLD, "first hold");
        check(f.musicPlaying && !f.videoPlaying, "music only");
        equal(1, f.textAlpha, "initial full text");
        equal(1, f.firstAlpha, "initial first frame");
        equal(0, f.musicVolume, "initial gentle music onset");
        equal(0.5f, t.frame(3500).musicVolume, "initial five second music fade midpoint");
        equal(1, t.frame(6000).musicVolume, "initial music full at five seconds");
        check(t.frame(30999).phase == SchoolReminderTimeline.Phase.HOLD, "hold lasts full thirty seconds");
        f = t.frame(31000);
        check(f.phase == SchoolReminderTimeline.Phase.FADE_OUT, "fade starts at thirty seconds");
        equal(1, f.textAlpha, "text fade starts at one");
        equal(1, f.musicVolume, "music fade starts simultaneously at one");
        f = t.frame(32000);
        equal(.5f, f.textAlpha, "text halfway after one second");
        equal(.8f, f.musicVolume, "music fades over five seconds");
        f = t.frame(33000);
        equal(0, f.textAlpha, "text absent after two seconds");
        equal(.6f, f.musicVolume, "music still fading after text disappears");
        check(!f.videoPlaying, "video waits for entire music fade");
        check(t.frame(35999).musicPlaying, "music still active just before pause");
        f = t.frame(36000);
        check(f.phase == SchoolReminderTimeline.Phase.VIDEO, "video begins at fade endpoint");
        check(!f.musicPlaying && f.videoPlaying, "music pauses while video plays");
        equal(0, f.musicVolume, "music silent for video");
        equal(0, f.textAlpha, "no text over playing video");
        check(t.frame(96000).phase == SchoolReminderTimeline.Phase.VIDEO, "duration cannot synthesize completion");
        check(t.videoCompleted(96144), "actual video completion accepted");
        check(!t.videoCompleted(96144), "duplicate completion ignored");
        f = t.frame(96144);
        check(f.phase == SchoolReminderTimeline.Phase.RETURN && f.lastVisible, "last frame after true completion");
        check(f.musicPlaying && !f.videoPlaying, "music resumes at completion");
        equal(0, f.musicVolume, "return music starts silent");
        equal(0, f.textAlpha, "text remains absent at completion");
        equal(0, f.firstAlpha, "last frame alone at completion");
        f = t.frame(99144);
        equal(.6f, f.musicVolume, "music at three seconds");
        equal(0, f.textAlpha, "text fade starts at plus three seconds");
        f = t.frame(100144);
        equal(.5f, f.textAlpha, "text midpoint at plus four");
        equal(.8f, f.musicVolume, "music at plus four");
        f = t.frame(101144);
        equal(1, f.textAlpha, "text fade ends at plus five");
        equal(1, f.musicVolume, "music fade also ends at plus five");
        equal(0, f.firstAlpha, "no frame crossfade at fade-in endpoint");
        f = t.frame(104143);
        equal(0, f.firstAlpha, "wait full three seconds after text fade ends");
        f = t.frame(104144);
        equal(0, f.firstAlpha, "frame crossfade starts at plus eight");
        f = t.frame(105644);
        equal(.5f, f.firstAlpha, "frame crossfade midpoint at plus nine point five");
        check(f.lastVisible, "last frame remains opaque below first during crossfade");
        equal(1, f.textAlpha, "text remains visible during frame crossfade");
        f = t.frame(107144);
        check(f.phase == SchoolReminderTimeline.Phase.HOLD && f.cycle == 1, "next hold at plus eleven");
        check(f.holdMs == 45000, "new random duration selected for next cycle");
        equal(1, f.firstAlpha, "first frame after transition");
        equal(1, f.musicVolume, "music continues without resetting its fade");
        check(!f.lastVisible, "last image removed after crossfade");
        check(t.frame(152143).phase == SchoolReminderTimeline.Phase.HOLD, "maximum hold inclusive duration");
        check(t.frame(152144).phase == SchoolReminderTimeline.Phase.FADE_OUT, "maximum hold endpoint");

        t = clock(30000);
        t.suspend(true, 10);
        t.ready(1000);
        stopped(t.frame(2000), "prepared without audio focus stays silent");
        t.suspend(false, 2010);
        check(t.frame(2010).elapsedMs == 0, "focus wait does not consume hold");
        t.frame(32010);
        t.suspend(true, 33010);
        f = t.frame(80000);
        equal(.5f, f.textAlpha, "focus loss freezes fade position");
        stopped(f, "focus loss pauses both players");
        t.suspend(false, 83010);
        f = t.frame(83010);
        equal(.5f, f.textAlpha, "focus regain resumes same fade position");
        check(f.musicPlaying, "music resumes after focus gain");
        f = t.frame(87010);
        check(f.videoPlaying, "video begins after remaining fade");
        t.suspend(true, 88010);
        stopped(t.frame(90000), "video pauses on focus loss");
        t.suspend(false, 91010);
        check(t.frame(91010).videoPlaying, "video resumes rather than restarts timeline");

        for (long position : new long[] {0, 1000, 30000, 31000, 35000, 60000}) {
            t = clock(30000); t.ready(0); t.frame(position); t.stop();
            stopped(t.frame(position + 100000), "hard stop in every phase is terminal");
            t.ready(position + 100001);
            t.suspend(false, position + 100002);
            check(!t.videoCompleted(position + 100003), "stale completion cannot revive stopped reminder");
            check(t.frame(position + 100004).phase == SchoolReminderTimeline.Phase.STOPPED,
                    "stale ready/focus cannot revive stopped reminder");
        }
        t = clock(30000); t.ready(0); t.frame(35000); t.videoCompleted(45000);
        t.frame(50000); t.stop(); stopped(t.frame(100000), "hard stop during return fade");
        check(!t.videoCompleted(100001), "completion after hard cutoff ignored");
        for (long invalid : new long[] {0, 29999, 45001, Long.MAX_VALUE}) {
            boolean rejected = false;
            try { clock(invalid).ready(0); } catch (IllegalArgumentException expected) { rejected = true; }
            check(rejected, "invalid hold rejected");
        }
        System.out.println("SchoolReminderTimelineTest: " + checks + " checks passed");
    }
}
