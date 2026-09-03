package dev.yossi.onnkeyguard;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.util.Log;

public final class MorningPlaybackReceiver extends BroadcastReceiver {
    private static final String TAG = "OnnKeyGuard";
    public static final String ACTION_PLAY = "dev.yossi.onnkeyguard.MORNING_PLAY";
    public static final String EXTRA_VIDEO = "video_id";
    private static final String TARGET_PACKAGE = "io.gh.yossim.tizentube.cobalt";
    private static final String TARGET_ACTIVITY = "dev.cobalt.app.MainActivity";

    @Override public void onReceive(Context context, Intent intent) {
        if (intent == null || !ACTION_PLAY.equals(intent.getAction())) return;
        String videoId = intent.getStringExtra(EXTRA_VIDEO);
        if (videoId == null || !videoId.matches("[A-Za-z0-9_-]{11}")) {
            Log.w(TAG, "Morning playback ignored invalid video id");
            return;
        }
        Uri url = Uri.parse("https://www.youtube.com/watch?v=" + videoId);
        Intent play = new Intent(Intent.ACTION_VIEW, url);
        play.setClassName(TARGET_PACKAGE, TARGET_ACTIVITY);
        play.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        try {
            context.startActivity(play);
            Log.i(TAG, "Morning playback handoff requested for " + videoId);
        } catch (Exception e) {
            Log.e(TAG, "Morning playback handoff failed for " + videoId, e);
        }
    }
}
