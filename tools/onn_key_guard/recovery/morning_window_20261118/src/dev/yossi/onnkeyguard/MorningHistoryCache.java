package dev.yossi.onnkeyguard;

import java.util.HashSet;
import java.util.Iterator;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

/** Ephemeral authenticated controller observation, never an independent run authority. */
public final class MorningHistoryCache {
    public static final long MAX_SAMPLE_AGE_MS = 3000L;
    private static final String PLAYLIST = "PLxKlPmCFYxy9y498dCB1tIddaxZz3OQCA";
    private String runId = "";
    private long start = -1L, deadline = -1L, sampled = -1L, receivedElapsed = -1L;
    private String state = "pending", reason = "awaiting_player_history";
    private JSONObject progress;

    public synchronized void bind(String id, long startedAt, long expires) {
        runId = id; start = startedAt; deadline = expires;
        invalidate("pending", "awaiting_player_history");
    }

    public synchronized void invalidate(String nextState, String why) {
        progress = null; sampled = receivedElapsed = -1L;
        state = nextState; reason = why;
    }

    public synchronized boolean accept(JSONObject event, boolean workAllowed, long now, long elapsed) {
        if (!workAllowed || !matches(event.optString("run_id", ""), integer(event.opt("started_at_ms")),
                integer(event.opt("deadline_ms")))) return false;
        long sample = integer(event.opt("sample_wall_ms"));
        if (sample < start || sample > now + 1000L || sample < now - MAX_SAMPLE_AGE_MS || now >= deadline)
            return false;
        String next = event.optString("state", "");
        if (!"ready".equals(next) && !"pending".equals(next) && !"unavailable".equals(next)) return false;
        // A failed or incomplete observation invalidates a previous complete snapshot immediately.
        invalidate(next, "ready".equals(next) ? "complete_account_history" : "pending".equals(next)
                ? "awaiting_player_history" : "player_history_unavailable");
        sampled = sample; receivedElapsed = elapsed;
        if (!"ready".equals(next)) return true;
        try {
            progress = validate(event.optJSONObject("progress"), start, now);
            return true;
        } catch (IllegalArgumentException bad) {
            invalidate("unavailable", "invalid_account_history");
            return false;
        }
    }

    public synchronized JSONObject read(String id, long startedAt, long expires,
            boolean workAllowed, long now, long elapsed) {
        if (!matches(id, startedAt, expires)) return result("unavailable", "run_mismatch", false, null);
        if (!workAllowed || now >= deadline) {
            invalidate("cancelled", "startup_not_authorized");
            return result(state, reason, false, null);
        }
        if (progress != null && (sampled > now + 1000L || now - sampled > MAX_SAMPLE_AGE_MS
                || receivedElapsed < 0 || elapsed < receivedElapsed
                || elapsed - receivedElapsed > MAX_SAMPLE_AGE_MS))
            invalidate("pending", "fresh_player_sample_required");
        return result(state, reason, true, progress);
    }

    public synchronized JSONObject status() {
        JSONObject out = result(state, reason, true, null);
        put(out, "sample_wall_ms", sampled);
        put(out, "covered_count", progress == null ? 0 : progress.optJSONObject("__youtube_history")
                .optJSONArray("covered_ids").length());
        return out;
    }

    private boolean matches(String id, long startedAt, long expires) {
        return runId.length() > 0 && runId.equals(id) && start == startedAt && deadline == expires;
    }

    private JSONObject result(String phase, String why, boolean ok, JSONObject body) {
        JSONObject out = new JSONObject();
        put(out, "schema", 1); put(out, "ok", ok); put(out, "state", phase); put(out, "reason", why);
        put(out, "run_id", runId); put(out, "started_at_ms", start); put(out, "deadline_ms", deadline);
        if (body != null) put(out, "progress", copy(body));
        return out;
    }

    public static JSONObject validate(JSONObject envelope, long runStart, long now) {
        JSONObject h = envelope == null ? null : envelope.optJSONObject("__youtube_history");
        if (envelope == null || envelope.length() != 1 || h == null || integer(h.opt("schema")) != 1
                || !"youtube_playlist".equals(h.optString("source", ""))
                || !PLAYLIST.equals(h.optString("playlist_id", ""))
                || !Boolean.TRUE.equals(h.opt("signed_in")) || !Boolean.TRUE.equals(h.opt("complete")))
            throw new IllegalArgumentException("history_scope");
        long captured = integer(h.opt("captured_at_ms"));
        if (captured <= 0 || captured < runStart || captured > now || now - captured > 300000L)
            throw new IllegalArgumentException("history_time");
        JSONArray ids = h.optJSONArray("covered_ids");
        JSONObject percentages = h.optJSONObject("progress");
        if (ids == null || percentages == null || ids.length() < 1 || ids.length() > 512
                || percentages.length() != ids.length()) throw new IllegalArgumentException("history_coverage");
        HashSet<String> covered = new HashSet<String>();
        for (int i = 0; i < ids.length(); i++) {
            Object item = ids.opt(i);
            if (!(item instanceof String) || !((String) item).matches("[A-Za-z0-9_-]{11}")
                    || !covered.add((String) item)) throw new IllegalArgumentException("history_id");
            Object value = percentages.opt((String) item);
            if (!(value instanceof Number)) throw new IllegalArgumentException("history_progress");
            double number = ((Number) value).doubleValue();
            if (Double.isNaN(number) || Double.isInfinite(number) || number < 0 || number > 100)
                throw new IllegalArgumentException("history_progress");
        }
        Iterator<String> keys = percentages.keys();
        while (keys.hasNext()) if (!covered.contains(keys.next()))
            throw new IllegalArgumentException("history_coverage");
        if (envelope.toString().length() > 65536) throw new IllegalArgumentException("history_size");
        return copy(envelope);
    }

    static long integer(Object value) {
        if (!(value instanceof Number)) return -1L;
        double number = ((Number) value).doubleValue();
        if (Double.isNaN(number) || Double.isInfinite(number) || number < 0
                || number > 9007199254740991d || number != Math.floor(number)) return -1L;
        return ((Number) value).longValue();
    }

    private static JSONObject copy(JSONObject object) {
        try { return new JSONObject(object.toString()); }
        catch (JSONException impossible) { throw new IllegalArgumentException("history_json", impossible); }
    }

    static void put(JSONObject object, String key, Object value) {
        try { object.put(key, value); }
        catch (JSONException impossible) { throw new IllegalStateException(impossible); }
    }
}
