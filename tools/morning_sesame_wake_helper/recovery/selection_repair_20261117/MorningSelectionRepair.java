package dev.yossi.morningsesame;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Log;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.json.JSONObject;

/** Adapter used by the original receiver; all wake, authorization and playback routing stay there. */
final class MorningSelectionRepair {
    private static final String PREFS = "morning_sesame";
    private static final String RECENT_STARTS = "selection_confirmed_starts_v1";

    private MorningSelectionRepair() {}

    static void selectAndVerify(Context context, String runId, JSONObject progress) {
        if (!RunCoordinator.checkpoint(context, runId)) return;
        SharedPreferences prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        Map<String, Integer> durations = AlarmReceiver.parseDurations(prefs.getString("candidate_list", ""));
        Map<String, Double> account = parseProgress(progress, durations.keySet());
        Map<String, Double> observed = parseProgress(progress == null ? null
                : progress.optJSONObject("__local_observed"), durations.keySet());
        Map<String, Long> ledger = MorningStartHistory.decode(prefs.getString(RECENT_STARTS, ""));
        Set<String> starts = new HashSet<>(prefs.getStringSet("played_ids", Collections.emptySet()));
        starts.addAll(ledger.keySet());
        Set<String> recent = MorningStartHistory.recent(ledger, System.currentTimeMillis());
        String selected = MorningEpisodePolicy.select(durations, account, observed, starts, recent);
        String selection = selected == null ? "no_candidates"
                : account.containsKey(selected) ? "account_progress_selected" : "local_history_fallback";
        long percent = selected != null && account.containsKey(selected)
                ? Math.round(account.get(selected)) : -1L;
        RunDiagnostics.event(context, runId, "account_progress", selection, RunDiagnostics.values(
                "candidate_count", durations.size(), "progress_count", account.size(),
                "account_progress_percent", percent));
        if (selected == null) {
            Log.w("MorningSesame", "No eligible episode: require >40 minutes, <=25% watched and no recent repeat");
            RunCoordinator.finish(context, runId, "failed", "no_eligible_candidate");
            return;
        }
        if (!RunCoordinator.storePending(context, runId, selected)) return;
        Log.i("MorningSesame", "Selected newest qualifying episode " + selected + " (" + selection + ")");
        RunCoordinator.note(context, runId, "play_requested", selected, 0);
        AlarmReceiver.routePlayback(context, runId, selected);
        AlarmReceiver.schedulePlay(context, runId, selected);
    }

    /** Called only at the existing independently-confirmed-start checkpoint. */
    static void recordConfirmedStart(Context context, String videoId) {
        if (!MorningEpisodePolicy.validId(videoId)) return;
        SharedPreferences prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        Map<String, Long> ledger = MorningStartHistory.decode(prefs.getString(RECENT_STARTS, ""));
        // Migrate the legacy set before its existing 250-ID reset can discard it.
        List<String> legacy = new ArrayList<>(prefs.getStringSet("played_ids", Collections.emptySet()));
        Collections.sort(legacy);
        for (String id : legacy) {
            if (MorningEpisodePolicy.validId(id) && !ledger.containsKey(id)) ledger.put(id, 0L);
        }
        String encoded = MorningStartHistory.record(ledger, videoId, System.currentTimeMillis());
        if (!prefs.edit().putString(RECENT_STARTS, encoded).commit()) {
            // The original played_ids write still runs; report the missing additional receipt.
            Log.w("MorningSesame", "Unable to persist confirmed-start repeat guard");
        }
    }

    private static Map<String, Double> parseProgress(JSONObject progress, Set<String> ids) {
        Map<String, Double> result = new LinkedHashMap<>();
        if (progress == null) return result;
        for (String id : ids) {
            if (progress.has(id)) result.put(id, progress.optDouble(id, Double.NaN));
        }
        return result;
    }
}
