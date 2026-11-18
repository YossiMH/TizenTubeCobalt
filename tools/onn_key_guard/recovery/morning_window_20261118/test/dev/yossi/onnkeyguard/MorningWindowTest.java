package dev.yossi.onnkeyguard;

import java.text.SimpleDateFormat;
import java.util.Locale;
import java.util.TimeZone;
import org.json.JSONArray;
import org.json.JSONObject;

public final class MorningWindowTest {
    private static final String RUN = "12345678-1234-1234-1234-123456789abc";
    private static final String OTHER = "87654321-1234-1234-1234-123456789abc";
    private static final String VIDEO = "abcdefghijk";
    private static int checks;
    public static void main(String[] args) throws Exception {
        startupAndFallback(); confirmationAndRestart(); parentAndCalendar(); diagnosticGrant(); history();
        System.out.println("MorningWindow: " + checks + " behavioral checks passed");
    }
    private static void check(boolean value, String message) {
        checks++; if (!value) throw new AssertionError(message);
    }
    private static long t(String value) throws Exception {
        SimpleDateFormat f = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.ROOT);
        f.setTimeZone(TimeZone.getTimeZone("America/Los_Angeles")); return f.parse(value).getTime();
    }
    private static SchoolBannerPolicy start(long now) {
        SchoolBannerPolicy p = new SchoolBannerPolicy();
        check(p.scheduledStart(RUN, now, now + 300000L, true, now), "admitted scheduled start"); return p;
    }
    private static void absent(SchoolBannerPolicy p, long now) {
        p.observeSession("old-run", VIDEO, "completed", "expired", true, true, now);
    }
    private static void startupAndFallback() throws Exception {
        long s = t("2026-11-18 07:00:00"), eight = t("2026-11-18 08:00:00"), end = t("2026-11-18 09:30:00");
        SchoolBannerPolicy p = start(s);
        check(p.snapshot(s).phase == SchoolBannerPolicy.Phase.STARTING, "protect before selection");
        check(p.snapshot(s).startupProtectionReady && !p.snapshot(s).protectionReady, "startup does not display reminder");
        check(p.startupWorkAllowed(RUN, s + 299999L), "finite startup work live");
        check(!p.startupWorkAllowed(RUN, s + 300000L), "work ends independently at five minutes");
        check(p.snapshot(s + 300000L).startupProtectionReady, "history failure does not free physical control");
        check(p.snapshot(eight).phase == SchoolBannerPolicy.Phase.STARTING, "cached absence cannot trigger fallback");
        absent(p, eight);
        check(p.snapshot(eight).phase == SchoolBannerPolicy.Phase.ACTIVE, "fresh absence at eight activates fallback");
        check(p.snapshot(eight).videoId.equals("") && p.snapshot(eight).completedAtMs == -1L,
                "fallback never fabricates video or end");
        check(!p.candidate(RUN, VIDEO, eight), "late selected candidate rejected");
        check(!p.ended(RUN, VIDEO, eight, eight), "late end cannot reidentify fallback");
        check(p.snapshot(end - 1L).protectionReady, "fallback lasts to final millisecond");
        check(p.snapshot(end).phase == SchoolBannerPolicy.Phase.EXPIRED, "exact cutoff releases");
        SchoolBannerPolicy restored = new SchoolBannerPolicy();
        check(restored.restore(p.save(), end), "expired unbound window restores");
        check(!restored.snapshot(end).startupProtectionReady, "expired restore cannot protect");
        check(!new SchoolBannerPolicy().scheduledStart(RUN, s, s + 300001L, true, s), "invalid work bound rejected");
        check(!new SchoolBannerPolicy().scheduledStart(RUN, s, s + 300000L, false, s), "missing phone proof rejected");
    }
    private static void confirmationAndRestart() throws Exception {
        long s = t("2026-11-18 07:00:00"), eight = t("2026-11-18 08:00:00");
        SchoolBannerPolicy p = start(s);
        check(p.candidate(RUN, VIDEO, s + 1000), "signature candidate accepted");
        String beforeBinding = p.save();
        SchoolBannerPolicy r = new SchoolBannerPolicy();
        check(r.restore(beforeBinding, eight), "starting checkpoint restores after work deadline");
        check(r.snapshot(eight).phase == SchoolBannerPolicy.Phase.STARTING, "restart needs current canonical proof");
        r.observeSession(RUN, VIDEO, "active", "daily_start", true, true, eight);
        check(r.snapshot(eight).phase == SchoolBannerPolicy.Phase.WAITING && r.snapshot(eight).videoId.equals(VIDEO),
                "confirmed canonical episode wins over fallback across checkpoint boundary");
        check(!r.snapshot(eight).protectionReady, "eight never interrupts confirmed episode");
        r.observeSession(RUN, VIDEO, "phone_paused", "phone_pause", true, true, eight + 1000);
        check(r.snapshot(eight + 1000).phase == SchoolBannerPolicy.Phase.WAITING, "parent pause is still active episode");
        check(r.ended(RUN, VIDEO, eight + 2000, eight + 2000), "true end hands off to reminder");
        check(r.snapshot(eight + 2000).reason.equals("episode_ended"), "actual-end reason retained");
        SchoolBannerPolicy other = start(s);
        other.observeSession(OTHER, "zyxwvutsrqp", "active", "daily_start", true, true, eight);
        check(other.snapshot(eight).phase == SchoolBannerPolicy.Phase.STARTING, "other authorized episode suppresses fallback");
        check(!other.snapshot(eight).startupProtectionReady && other.snapshot(eight).videoId.length() == 0,
                "foreign canonical episode is never rebound");
        absent(other, eight + 1000L);
        check(other.snapshot(eight + 1000L).protectionReady, "fresh absent observation allows fallback");
    }
    private static void parentAndCalendar() throws Exception {
        long s = t("2026-11-18 07:00:00"), eight = t("2026-11-18 08:00:00");
        for (int key : new int[] {116, 172, 158, 201, 28}) {
            SchoolBannerPolicy p = start(s); p.phoneKey(key, 1, s + 1, s + 1);
            SchoolBannerPolicy r = new SchoolBannerPolicy(); check(r.restore(p.save(), s + 10), "phone veto persisted");
            check(!r.scheduledStart(OTHER, s + 10, s + 300010, true, s + 10), "new run cannot override same-day phone veto");
            absent(r, eight); check(r.snapshot(eight).phase == SchoolBannerPolicy.Phase.DISMISSED, "fallback cannot undo veto");
        }
        SchoolBannerPolicy volume = start(s); volume.phoneKey(115, 1, s + 1, s + 1);
        check(volume.snapshot(s + 1).startupProtectionReady, "volume preserves existing exception");
        volume.observeInputProof(false, s + 2); volume.observeInputProof(true, s + 3);
        check(volume.snapshot(s + 3).phase == SchoolBannerPolicy.Phase.DISMISSED, "lost provenance cannot restart protection");
        for (String date : new String[] {"2026-11-22", "2026-11-26"}) {
            long day = t(date + " 07:00:00"), atEight = t(date + " 08:00:00");
            SchoolBannerPolicy p = start(day); check(p.snapshot(day).startupProtectionReady, "valid holiday/Sunday startup still protected");
            absent(p, atEight); check(p.snapshot(atEight).phase == SchoolBannerPolicy.Phase.SKIPPED, "holiday/Sunday fallback excluded");
        }
    }
    private static void diagnosticGrant() {
        MorningWindowGrant g = new MorningWindowGrant();
        check(g.arm(10000, 12345, 30, 120, 9000, 1000), "bounded test grant armed");
        check(!g.consume(RUN, 10000, 10001, 12346, 10002, 2002), "wrong actual sender rejected");
        check(!g.consume(RUN, 16000, 16000, 12345, 16000, 8000), "wrong alarm window rejected");
        check(g.consume(RUN, 10000, 10001, 12345, 10002, 2002), "real scheduled signature run consumes grant");
        check(g.consumedRun().equals(RUN), "real UUID recorded");
        check(!g.consume(OTHER, 10000, 10001, 12345, 10002, 2002), "grant consumed once");
        check(g.cutoffWall() - 10002 <= 120000, "cutoff stays within original monotonic grant");
        SchoolBannerPolicy test = new SchoolBannerPolicy();
        check(test.diagnosticStart(RUN, 10000, 310000, 40002, g.cutoffWall(), true, 10002), "separate diagnostic uses same state machine");
        check(test.save().equals(""), "diagnostic never overwrites real-day veto");
        test.observeSession("old-run", VIDEO, "completed", "expired", true, true, 40002);
        check(test.snapshot(40002).phase == SchoolBannerPolicy.Phase.ACTIVE, "diagnostic follows fallback transition");
        test.expireDiagnostic(); check(!test.snapshot(40003).protectionReady, "monotonic cutoff releases even if wall clock lags");
        MorningWindowGrant cancelled = new MorningWindowGrant();
        check(cancelled.arm(10000, 12345, 30, 120, 9000, 1000), "another independent grant");
        cancelled.cancel(); check(!cancelled.consume(RUN, 10000, 10000, 12345, 10000, 2000), "phone/observer veto cancels armed grant");
        check(!new MorningWindowGrant().arm(10000, 12345, 30, 181, 9000, 1000), "overlong grant rejected");
    }
    private static JSONObject envelope(long at) throws Exception {
        JSONObject h = new JSONObject().put("schema",1).put("source","youtube_playlist")
            .put("playlist_id","PLxKlPmCFYxy9y498dCB1tIddaxZz3OQCA").put("captured_at_ms",at)
            .put("signed_in",true).put("complete",true).put("covered_ids",new JSONArray().put(VIDEO))
            .put("progress",new JSONObject().put(VIDEO,49));
        return new JSONObject().put("__youtube_history",h);
    }
    private static JSONObject event(long start, long now, String state) throws Exception {
        return new JSONObject().put("run_id",RUN).put("started_at_ms",start).put("deadline_ms",start+300000)
            .put("sample_wall_ms",now).put("state",state);
    }
    private static void history() throws Exception {
        long s=1000000, now=s+1000;
        MorningHistoryCache c=new MorningHistoryCache(); c.bind(RUN,s,s+300000);
        JSONObject e=event(s,now,"ready").put("progress",envelope(now));
        check(c.accept(e,true,now,1000), "fresh complete account history accepted");
        JSONObject r=c.read(RUN,s,s+300000,true,now,1001);
        check(r.getBoolean("ok") && r.getString("state").equals("ready"), "helper sees exact ready ABI");
        check(r.getJSONObject("progress").getJSONObject("__youtube_history").getJSONObject("progress").getDouble(VIDEO)==49,
                "percentage preserved");
        e.getJSONObject("progress").getJSONObject("__youtube_history").getJSONObject("progress").put(VIDEO,0);
        check(c.read(RUN,s,s+300000,true,now,1002).getJSONObject("progress").getJSONObject("__youtube_history")
                .getJSONObject("progress").getDouble(VIDEO)==49,"cache is immutable copy");
        check(!c.read(OTHER,s,s+300000,true,now,1002).has("progress"),"wrong run cannot read snapshot");
        check(!c.read(RUN,s+1,s+300000,true,now,1002).has("progress"),"wrong start cannot read snapshot");
        check(!c.read(RUN,s,s+300000,true,now+3001,4001).has("progress"),"stale controller sample expires");
        check(c.accept(event(s,now+1,"ready").put("progress",envelope(now)),true,now+1,1003),"refresh accepted");
        check(c.accept(event(s,now+2,"pending"),true,now+2,1004),"pending account change accepted");
        check(!c.read(RUN,s,s+300000,true,now+2,1004).has("progress"),"pending clears prior history immediately");
        for(String field:new String[]{"schema","signed_in","complete","captured_at_ms"}) {
            JSONObject bad=envelope(now); bad.getJSONObject("__youtube_history").put(field,"1");
            check(!c.accept(event(s,now,"ready").put("progress",bad),true,now,1005),"wrong typed "+field+" rejected");
        }
        JSONObject stale=envelope(s-1); check(!c.accept(event(s,now,"ready").put("progress",stale),true,now,1006),"before-run capture rejected");
        JSONObject incomplete=envelope(now); incomplete.getJSONObject("__youtube_history").getJSONObject("progress").put("zyxwvutsrqp",0);
        check(!c.accept(event(s,now,"ready").put("progress",incomplete),true,now,1007),"uncovered id rejected, not zero-filled");
        check(!c.accept(event(s,now,"ready").put("progress",envelope(now)),false,now,1008),"no history when startup revoked");
        check(c.read(RUN,s,s+300000,false,now,1009).getString("state").equals("cancelled"),"terminal cancellation explicit");
    }
}
