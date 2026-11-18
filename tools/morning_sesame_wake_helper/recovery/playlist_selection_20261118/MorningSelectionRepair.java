package dev.yossi.morningsesame;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Log;
import java.util.Map;
import org.json.JSONObject;

/** Keeps the installed receiver's run fencing and playback routing around the new selector. */
final class MorningSelectionRepair {
    private MorningSelectionRepair() {}

    static void selectAndVerify(Context context, String runId, JSONObject progress) {
        if (!RunCoordinator.checkpoint(context, runId)) return;
        SharedPreferences prefs = context.getSharedPreferences("morning_sesame", Context.MODE_PRIVATE);
        Map<String, Integer> durations = AlarmReceiver.parseDurations(prefs.getString("candidate_list", ""));
        final Map<String, Double> account;
        try {
            account = MorningYouTubeHistory.parse(progress, System.currentTimeMillis());
        } catch (Exception invalidHistory) {
            Log.w("MorningSesame", "Fresh complete signed-in playlist history is unavailable");
            RunCoordinator.finish(context, runId, "failed", "account_selection_failed");
            return;
        }
        String selected = MorningEpisodePolicy.select(durations, account);
        RunDiagnostics.event(context, runId, "account_progress",
                selected == null ? "no_candidates" : "account_progress_selected", RunDiagnostics.values(
                        "candidate_count", durations.size(), "progress_count", account.size(),
                        "account_progress_percent", selected == null ? -1L : Math.round(account.get(selected))));
        if (selected == null) {
            Log.w("MorningSesame", "No playlist episode meets >40 minutes and <=50% watched in YouTube history");
            RunCoordinator.finish(context, runId, "failed", "no_eligible_candidate");
            return;
        }
        if (!RunCoordinator.storePending(context, runId, selected)) return;
        Log.i("MorningSesame", "Selected newest qualifying full episode " + selected);
        RunCoordinator.note(context, runId, "play_requested", selected, 0);
        AlarmReceiver.routePlayback(context, runId, selected);
        AlarmReceiver.schedulePlay(context, runId, selected);
    }

    /** Retained ABI for the installed confirmation hook. Starts are not watch-history evidence. */
    static void recordConfirmedStart(Context context, String videoId) {}
}
