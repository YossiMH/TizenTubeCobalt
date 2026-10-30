package dev.yossi.morningsesame;

/** Diagnostic classifications are bounded and do not replace playback acceptance. */
public final class PlaybackDiagnosticsTest {
    private static final String VIDEO = "4BakiCgKOZM";
    private static final long NOW = 1_800_000_000_000L, START = NOW - 10_000L;
    private static int checks;

    private static void check(boolean value, String message) {
        checks++;
        if (!value) throw new AssertionError(message);
    }
    private static String xml(String video, long sampledAt, String position) {
        return "<yumi>morning-sesame</yumi><ms_state>playing</ms_state><ms_playing>" + video
                + "</ms_playing><ms_playing_at>" + sampledAt + "</ms_playing_at><ms_position>"
                + position + "</ms_position>";
    }
    private static PlaybackDiagnostics.Observation inspect(String xml) {
        return PlaybackDiagnostics.inspect(xml, VIDEO, START, NOW);
    }
    private static void reason(String xml, String expected, String message) {
        check(expected.equals(inspect(xml).reason), message);
    }

    public static void main(String[] args) {
        String valid = xml(VIDEO, NOW - 2_000L, "12.5");
        PlaybackDiagnostics.Observation good = inspect(valid);
        check("ok".equals(good.reason), "valid playing evidence classified without changing its meaning");
        check(good.values.get("sample_age_ms") == 2_000L, "sample age is recorded in milliseconds");
        check(good.values.get("sample_since_run_ms") == 8_000L, "sample timing is correlated with run start");
        check(good.values.get("first_position_ms") == 12_500L, "position is represented as bounded numeric milliseconds");
        check(good.values.get("marker_present") == 1L && good.values.get("state_playing") == 1L
                        && good.values.get("video_matches") == 1L, "identity and playing flags are retained");

        reason(null, "sample_missing", "null status is classified");
        reason(valid.replace("<yumi>morning-sesame</yumi>", ""), "marker_missing", "wrong endpoint identified");
        reason(valid.replace("<ms_state>playing</ms_state>", "<ms_state>paused</ms_state>"),
                "state_idle", "non-playing status identified");
        reason(xml("___________", NOW, "12"), "video_mismatch", "different video identified");
        reason(valid.replace("<ms_playing_at>" + (NOW - 2_000L) + "</ms_playing_at>", ""),
                "sample_missing", "missing timestamp identified");
        reason(xml(VIDEO, START - 1L, "12"), "sample_before_run", "pre-run cached sample identified");
        reason(xml(VIDEO, NOW + 1_001L, "12"), "sample_future", "future timestamp identified");
        reason(xml(VIDEO, NOW - 5_001L, "12"), "sample_stale", "stale current-run sample identified");
        reason(xml(VIDEO, NOW + 1_000L, "12"), "ok", "existing future-skew allowance retained");
        reason(xml(VIDEO, NOW - 5_000L, "12"), "ok", "existing freshness boundary retained");
        String hugeNumber = "9";
        for (int i = 0; i < 10; i++) hugeNumber += hugeNumber;
        reason(xml(VIDEO, NOW, hugeNumber), "position_invalid", "non-finite numeric position is classified safely");
        reason(valid.replace(Long.toString(NOW - 2_000L), hugeNumber), "sample_missing",
                "unparseable timestamp cannot escape the diagnostic parser");
        StringBuilder hugeStatus = new StringBuilder();
        for (int i = 0; i <= 1_000_000; i++) hugeStatus.append('x');
        reason(hugeStatus.toString(), "sample_missing", "oversized status rejected before field scanning");

        String sensitive = valid + "<session>SECRET_COOKIE_AND_ACCOUNT_TOKEN</session>"
                + "<url>https://private.invalid/?token=SECRET_COOKIE_AND_ACCOUNT_TOKEN</url>";
        PlaybackDiagnostics.Observation sanitized = inspect(sensitive);
        check(!sanitized.values.toString().contains("SECRET_COOKIE_AND_ACCOUNT_TOKEN")
                        && !sanitized.values.toString().contains("private.invalid"),
                "response bodies, URLs and session values are absent from diagnostic output");
        check(sanitized.values.size() <= 8, "one status sample has a fixed small number of numeric fields");

        String firstXml = xml(VIDEO, NOW - 1_000L, "12.5");
        String frozenXml = xml(VIDEO, NOW, "12.5");
        PlaybackEvidence first = PlaybackEvidence.read(firstXml, VIDEO, START, NOW);
        PlaybackEvidence frozen = PlaybackEvidence.read(frozenXml, VIDEO, START, NOW);
        check("ok".equals(inspect(firstXml).reason) && "ok".equals(inspect(frozenXml).reason)
                        && !PlaybackEvidence.advances(first, frozen),
                "individually fresh diagnostic samples do not turn frozen playback into accepted playback");
        PlaybackEvidence advancing = PlaybackEvidence.read(xml(VIDEO, NOW, "14.5"), VIDEO, START, NOW);
        check(PlaybackEvidence.advances(first, advancing), "original advancing-playback rule remains authoritative");
        System.out.println("PlaybackDiagnostics: " + checks + " classification, boundary and privacy checks passed.");
    }
}
