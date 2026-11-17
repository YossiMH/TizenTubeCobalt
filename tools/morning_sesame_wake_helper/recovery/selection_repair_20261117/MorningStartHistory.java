package dev.yossi.morningsesame;

import java.util.Calendar;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.TimeZone;

/** Bounded confirmed-start receipts, independent of measured watch percentages. */
final class MorningStartHistory {
    static final int MAX_IDS = 1024;
    static final int MAX_CHARS = MAX_IDS * 33;

    private MorningStartHistory() {}

    static Map<String, Long> decode(String encoded) {
        Map<String, Long> result = new LinkedHashMap<>();
        if (encoded == null || encoded.length() > MAX_CHARS) return result;
        for (String row : encoded.split(";")) {
            int comma = row.indexOf(',');
            if (comma != 11 || result.size() >= MAX_IDS) continue;
            String id = row.substring(0, comma);
            if (!MorningEpisodePolicy.validId(id)) continue;
            try {
                long when = Long.parseLong(row.substring(comma + 1));
                if (when >= 0) result.put(id, when);
            } catch (NumberFormatException ignored) {}
        }
        return result;
    }

    static String record(Map<String, Long> ledger, String id, long now) {
        Map<String, Long> ordered = new LinkedHashMap<>(ledger);
        if (MorningEpisodePolicy.validId(id) && now > 0L) {
            ordered.remove(id);
            ordered.put(id, now);
        }
        while (ordered.size() > MAX_IDS) ordered.remove(ordered.keySet().iterator().next());
        StringBuilder out = new StringBuilder(ordered.size() * 26);
        for (Map.Entry<String, Long> entry : ordered.entrySet()) {
            Long when = entry.getValue();
            if (!MorningEpisodePolicy.validId(entry.getKey()) || when == null || when < 0) continue;
            if (out.length() > 0) out.append(';');
            out.append(entry.getKey()).append(',').append(when);
        }
        return out.toString();
    }

    static Set<String> recent(Map<String, Long> ledger, long now) {
        Calendar yesterday = Calendar.getInstance(TimeZone.getTimeZone("America/Los_Angeles"));
        yesterday.setTimeInMillis(now);
        yesterday.set(Calendar.HOUR_OF_DAY, 0);
        yesterday.set(Calendar.MINUTE, 0);
        yesterday.set(Calendar.SECOND, 0);
        yesterday.set(Calendar.MILLISECOND, 0);
        yesterday.add(Calendar.DAY_OF_MONTH, -1);
        long threshold = yesterday.getTimeInMillis();
        Set<String> result = new HashSet<>();
        for (Map.Entry<String, Long> entry : ledger.entrySet()) {
            if (entry.getValue() != null && entry.getValue() > 0 && entry.getValue() >= threshold)
                result.add(entry.getKey());
        }
        return result;
    }
}
