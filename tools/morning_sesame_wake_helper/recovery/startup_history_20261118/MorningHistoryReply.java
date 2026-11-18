package dev.yossi.morningsesame;

import org.json.JSONObject;

/** Unwraps a signature-owned reply without weakening the account-history validator. */
final class MorningHistoryReply {
    static final int MAX_REPLY_CHARS = 65536;

    private MorningHistoryReply() {}

    static JSONObject parse(String encoded, String runId, long startedWall, long deadlineWall,
                            long now) throws Exception {
        if (encoded == null || encoded.length() == 0 || encoded.length() > MAX_REPLY_CHARS)
            throw new IllegalArgumentException("history_reply_size");
        if (runId == null || !runId.matches("[A-Za-z0-9][A-Za-z0-9._:-]{0,127}")
                || startedWall <= 0 || startedWall > now || deadlineWall <= now
                || deadlineWall <= startedWall || deadlineWall - startedWall > 300000L)
            throw new IllegalArgumentException("history_request_window");
        JSONObject reply = new JSONObject(encoded);
        if (!exactNumber(reply.opt("schema"), 1L)
                || !runId.equals(reply.opt("run_id"))
                || !exactNumber(reply.opt("started_at_ms"), startedWall)
                || !exactNumber(reply.opt("deadline_ms"), deadlineWall))
            throw new IllegalArgumentException("history_reply_binding");
        String state = reply.optString("state", "");
        if (!Boolean.TRUE.equals(reply.opt("ok")) || !"ready".equals(state)) {
            if ("pending".equals(state)) throw new IllegalArgumentException("history_pending");
            if ("cancelled".equals(state)) throw new IllegalArgumentException("history_cancelled");
            throw new IllegalArgumentException("history_unavailable");
        }
        JSONObject progress = reply.optJSONObject("progress");
        MorningYouTubeHistory.parse(progress, now);
        JSONObject history = progress.getJSONObject("__youtube_history");
        if (history.getLong("captured_at_ms") < startedWall)
            throw new IllegalArgumentException("history_predates_run");
        // Only the independently validated account envelope crosses this boundary.
        return new JSONObject().put("__youtube_history", new JSONObject(history.toString()));
    }

    static String reason(Exception error) {
        String message = error.getMessage();
        return message != null && message.matches("(?:history|account_history)_[a-z_]{1,64}")
                ? message : "history_reply_invalid";
    }

    private static boolean exactNumber(Object value, long expected) {
        return value instanceof Number && ((Number) value).doubleValue() == (double) expected;
    }
}
