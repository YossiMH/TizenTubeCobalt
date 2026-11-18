package dev.yossi.morningsesame;

import java.util.Collections;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import org.json.JSONArray;
import org.json.JSONObject;

/** Validates the explicitly scoped, complete account snapshot supplied by the signed-in player. */
final class MorningYouTubeHistory {
    static final String PLAYLIST_ID = "PLxKlPmCFYxy9y498dCB1tIddaxZz3OQCA";
    static final long MAX_AGE_MS = 300_000L;
    static final int MAX_VIDEOS = 512;

    private MorningYouTubeHistory() {}

    static Map<String, Double> parse(JSONObject envelope, long now) throws Exception {
        JSONObject history = envelope == null ? null : envelope.optJSONObject("__youtube_history");
        if (history == null || !numberEquals(history.opt("schema"), 1.0)
                || !"youtube_playlist".equals(history.optString("source"))
                || !PLAYLIST_ID.equals(history.optString("playlist_id"))
                || !Boolean.TRUE.equals(history.opt("signed_in"))
                || !Boolean.TRUE.equals(history.opt("complete")))
            throw new IllegalArgumentException("account_history_scope");
        Object captured = history.opt("captured_at_ms");
        if (!(captured instanceof Number)) throw new IllegalArgumentException("account_history_time");
        double timestamp = ((Number) captured).doubleValue();
        if (Double.isNaN(timestamp) || Double.isInfinite(timestamp) || timestamp != Math.floor(timestamp)
                || timestamp <= 0 || timestamp > now || timestamp < now - MAX_AGE_MS)
            throw new IllegalArgumentException("account_history_stale");
        JSONArray covered = history.optJSONArray("covered_ids");
        JSONObject progress = history.optJSONObject("progress");
        if (covered == null || progress == null || covered.length() == 0
                || covered.length() > MAX_VIDEOS || progress.length() != covered.length())
            throw new IllegalArgumentException("account_history_coverage");
        Map<String, Double> result = new LinkedHashMap<>();
        for (int i = 0; i < covered.length(); i++) {
            Object rawId = covered.opt(i);
            if (!(rawId instanceof String)) throw new IllegalArgumentException("account_history_id");
            String id = (String) rawId;
            Object rawPercent = progress.opt(id);
            if (!MorningEpisodePolicy.validId(id) || result.containsKey(id)
                    || !(rawPercent instanceof Number))
                throw new IllegalArgumentException("account_history_progress");
            double percent = ((Number) rawPercent).doubleValue();
            if (Double.isNaN(percent) || Double.isInfinite(percent) || percent < 0 || percent > 100)
                throw new IllegalArgumentException("account_history_progress");
            result.put(id, percent);
        }
        Iterator<String> keys = progress.keys();
        while (keys.hasNext()) if (!result.containsKey(keys.next()))
            throw new IllegalArgumentException("account_history_uncovered_id");
        return Collections.unmodifiableMap(result);
    }

    private static boolean numberEquals(Object value, double expected) {
        return value instanceof Number && ((Number) value).doubleValue() == expected;
    }
}
