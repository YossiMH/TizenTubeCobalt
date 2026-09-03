package dev.yossi.morningsesame;

import android.app.ActivityOptions;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.os.Bundle;
import android.util.Log;

public final class AlarmReceiver extends BroadcastReceiver {
    private static final String TAG = "MorningSesame";

    @Override
    public void onReceive(Context context, Intent intent) {
        if (intent == null) return;
        String action = intent.getAction();
        if (!WakeAndPlayActivity.ACTION_RUN.equals(action)
                && !WakeAndPlayActivity.ACTION_VERIFY.equals(action)
                && !WakeAndPlayActivity.ACTION_PLAY.equals(action)) {
            return;
        }

        String videoId = intent.getStringExtra(WakeAndPlayActivity.EXTRA_VIDEO);
        if (WakeAndPlayActivity.ACTION_VERIFY.equals(action)
                || WakeAndPlayActivity.ACTION_PLAY.equals(action)) {
            routePlayback(context, action, videoId);
            return;
        }

        Intent launch = new Intent(context, WakeAndPlayActivity.class)
                .setAction(action)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);

        Bundle creatorOptions = null;
        Bundle senderOptions = null;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            ActivityOptions creator = ActivityOptions.makeBasic();
            creator.setPendingIntentCreatorBackgroundActivityStartMode(
                    ActivityOptions.MODE_BACKGROUND_ACTIVITY_START_ALLOWED);
            creatorOptions = creator.toBundle();

            ActivityOptions sender = ActivityOptions.makeBasic();
            sender.setPendingIntentBackgroundActivityStartMode(
                    ActivityOptions.MODE_BACKGROUND_ACTIVITY_START_ALLOWED);
            senderOptions = sender.toBundle();
        }

        PendingIntent activity = PendingIntent.getActivity(
                context, requestCode(action), launch,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE,
                creatorOptions);
        try {
            activity.send(context, 0, null, null, null, null, senderOptions);
            Log.i(TAG, "Alarm receiver launched " + action);
        } catch (PendingIntent.CanceledException e) {
            Log.e(TAG, "Alarm receiver launch failed for " + action, e);
        }
    }

    private static void routePlayback(Context context, String action, String videoId) {
        if (videoId == null || !videoId.matches("[A-Za-z0-9_-]{11}")) {
            Log.w(TAG, "Alarm receiver ignored invalid playback id for " + action);
            return;
        }
        Intent handoff = new Intent("dev.yossi.onnkeyguard.MORNING_PLAY")
                .setClassName("dev.yossi.onnkeyguard",
                        "dev.yossi.onnkeyguard.MorningPlaybackReceiver")
                .putExtra("video_id", videoId);
        context.sendBroadcast(handoff);
        Log.i(TAG, "Alarm receiver routed " + action + " for " + videoId);
        if (WakeAndPlayActivity.ACTION_PLAY.equals(action)) {
            java.util.Set<String> played = new java.util.HashSet<>(
                    context.getSharedPreferences("morning_sesame", Context.MODE_PRIVATE)
                            .getStringSet("played_ids", new java.util.HashSet<>()));
            played.add(videoId);
            if (played.size() > 250) {
                played.clear();
                played.add(videoId);
            }
            context.getSharedPreferences("morning_sesame", Context.MODE_PRIVATE)
                    .edit().putStringSet("played_ids", played).remove("pending_video").apply();
            Log.i(TAG, "Recorded played video " + videoId);
        }
    }

    private static int requestCode(String action) {
        if (WakeAndPlayActivity.ACTION_VERIFY.equals(action)) return 801;
        if (WakeAndPlayActivity.ACTION_PLAY.equals(action)) return 802;
        return 800;
    }
}
