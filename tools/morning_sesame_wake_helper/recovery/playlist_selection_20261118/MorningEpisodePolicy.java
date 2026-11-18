package dev.yossi.morningsesame;

import java.util.Map;

/** Eligibility is determined only by current signed-in YouTube playlist progress. */
final class MorningEpisodePolicy {
    static final int MIN_DURATION_SECONDS = 2400;
    static final int MAX_DURATION_SECONDS = 21600;
    static final double MAX_WATCHED_PERCENT = 50.0;

    private MorningEpisodePolicy() {}

    static String select(Map<String, Integer> newestFirst, Map<String, Double> account) {
        if (newestFirst == null || account == null) return null;
        for (Map.Entry<String, Integer> entry : newestFirst.entrySet()) {
            String id = entry.getKey();
            Integer seconds = entry.getValue();
            if (!validId(id) || seconds == null || seconds <= MIN_DURATION_SECONDS
                    || seconds > MAX_DURATION_SECONDS) continue;
            Double watched = account.get(id);
            if (watched == null || Double.isNaN(watched) || Double.isInfinite(watched)
                    || watched < 0.0 || watched > MAX_WATCHED_PERCENT) continue;
            return id;
        }
        return null;
    }

    static boolean validId(String id) {
        return id != null && id.matches("[A-Za-z0-9_-]{11}");
    }
}
