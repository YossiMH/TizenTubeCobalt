package dev.yossi.morningsesame;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Describes rejection without changing PlaybackEvidence's acceptance rules or retaining XML. */
final class PlaybackDiagnostics {
    private static final Pattern VIDEO = Pattern.compile("<ms_playing>([A-Za-z0-9_-]{11})</ms_playing>");
    private static final Pattern AT = Pattern.compile("<ms_playing_at>([0-9]+)</ms_playing_at>");
    private static final Pattern POSITION = Pattern.compile("<ms_position>([0-9]+(?:\\.[0-9]+)?)</ms_position>");
    static final class Observation {
        final String reason;
        final Map<String, Long> values;
        Observation(String reason, Map<String, Long> values) { this.reason = reason; this.values = values; }
    }
    private PlaybackDiagnostics() {}
    static Observation inspect(String xml, String expected, long runStarted, long now) {
        Map<String, Long> values = new LinkedHashMap<>();
        if (xml == null || xml.length() > 1_000_000) return new Observation("sample_missing", values);
        boolean marker = xml.contains("<yumi>morning-sesame</yumi>");
        boolean playing = xml.contains("<ms_state>playing</ms_state>");
        Matcher video = VIDEO.matcher(xml), at = AT.matcher(xml), position = POSITION.matcher(xml);
        boolean videoMatches = video.find() && expected != null && expected.equals(video.group(1));
        values.put("marker_present", marker ? 1L : 0L);
        values.put("state_playing", playing ? 1L : 0L);
        values.put("video_matches", videoMatches ? 1L : 0L);
        if (!marker) return new Observation("marker_missing", values);
        if (!playing) return new Observation("state_idle", values);
        if (!videoMatches) return new Observation("video_mismatch", values);
        if (!at.find() || !position.find()) return new Observation("sample_missing", values);
        try {
            long sampledAt = Long.parseLong(at.group(1));
            double sampledPosition = Double.parseDouble(position.group(1));
            values.put("sample_age_ms", now - sampledAt);
            values.put("sample_since_run_ms", sampledAt - runStarted);
            if (!Double.isFinite(sampledPosition) || sampledPosition < 0.0
                    || sampledPosition > Long.MAX_VALUE / 1000.0) return new Observation("position_invalid", values);
            values.put("first_position_ms", Math.round(sampledPosition * 1000.0));
            if (runStarted <= 0L || sampledAt < runStarted) return new Observation("sample_before_run", values);
            if (sampledAt > now + 1000L) return new Observation("sample_future", values);
            if (now - sampledAt > 5000L) return new Observation("sample_stale", values);
            return new Observation("ok", values);
        } catch (NumberFormatException invalid) { return new Observation("sample_missing", values); }
    }
}
