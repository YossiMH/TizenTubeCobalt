package dev.yossi.morningsesame;

import android.app.AlarmManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Build;
import android.util.Log;

import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class AlarmReceiver extends BroadcastReceiver {
    private static final String TAG = "MorningSesame";
    private static final String PREFS = "morning_sesame";
    private static final String KEY_PLAYED = "played_ids";
    private static final String KEY_PENDING = "pending_video";
    private static final String KEY_CANDIDATES = "candidate_list";
    private static final String DIAL_URL = "http://127.0.0.1:8012/apps/YouTube";
    private static final long PLAY_AFTER_VERIFY_MS = 10_000L;
    private static final Pattern PROGRESS_XML =
           Pattern.compile("<ms_progress>(.*?)</ms_progress>", Pattern.DOTALL);

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
        if (WakeAndPlayActivity.ACTION_VERIFY.equals(action)) {
            if (isVideoId(videoId)) {
                routePlayback(context, action, videoId);
            } else {
                PendingResult pending = goAsync();
                new Thread(() -> {
                    try {
                        selectAndVerify(context);
                    } catch (Exception e) {
                        Log.e(TAG, "Account-progress selection failed", e);
                    } finally {
                        pending.finish();
                    }
                }, "MorningSesameSelect").start();
            }
            return;
        }
        if (WakeAndPlayActivity.ACTION_PLAY.equals(action)) {
            routePlayback(context, action, videoId);
            return;
        }

        routeScheduledRun(context);
    }

    private static void routeScheduledRun(Context context) {
        Intent handoff = new Intent("dev.yossi.onnkeyguard.MORNING_RUN")
                .setClassName("dev.yossi.onnkeyguard",
                        "dev.yossi.onnkeyguard.MorningPlaybackReceiver");
        context.sendBroadcast(handoff);
        Log.i(TAG, "Alarm receiver routed scheduled run through Onn Key Guard");
    }

    private static void selectAndVerify(Context context) throws Exception {
        SharedPreferences prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        List<String> candidates = parseCandidates(prefs.getString(KEY_CANDIDATES, ""));
        if (candidates.isEmpty()) {
            Log.e(TAG, "No stored qualifying candidates for verification");
            return;
        }

        Set<String> played = new HashSet<>(
                prefs.getStringSet(KEY_PLAYED, new HashSet<>()));
        JSONObject progress = readProgressWithRetry();

        String chosen = null;
        if (progress != null && progress.length() > 0) {
            for (String candidate : candidates) {
                if (played.contains(candidate) || !progress.has(candidate)) continue;
                double pct = progress.optDouble(candidate, 100.0);
                if (pct < 50.0) {
                    chosen = candidate;
                    Log.i(TAG, "Selected " + candidate
                            + " from account progress (" + pct + "% watched)");
                    break;
                }
            }
        }

        if (chosen == null) {
            for (String candidate : candidates) {
                if (!played.contains(candidate)) {
                    chosen = candidate;
                    Log.i(TAG, "Selected " + candidate + " using local play-history fallback");
                    break;
                }
            }
        }
        if (chosen == null) {
            chosen = candidates.get(0);
            Log.i(TAG, "All recent candidates were previously played; cycling to " + chosen);
        }

        prefs.edit().putString(KEY_PENDING, chosen).apply();
        routePlayback(context, WakeAndPlayActivity.ACTION_VERIFY, chosen);
        schedulePlay(context, chosen);
    }

    private static JSONObject readProgressWithRetry() {
        for (int attempt = 0; attempt < 6; attempt++) {
            try {
                String xml = fetch(DIAL_URL);
                if (xml.contains("<yumi>morning-sesame</yumi>")) {
                    Matcher matcher = PROGRESS_XML.matcher(xml);
                    if (matcher.find()) {
                        String json = unescapeXml(matcher.group(1));
                        JSONObject result = new JSONObject(json);
                        if (result.length() > 0) return result;
                    }
                }
            } catch (Exception e) {
                if (attempt == 5) Log.w(TAG, "Signed-in progress feed unavailable: " + e);
            }
            try {
                Thread.sleep(1_000L);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }
        return null;
    }

    private static List<String> parseCandidates(String encoded) {
        List<String> out = new ArrayList<>();
        if (encoded == null || encoded.isEmpty()) return out;
        for (String row : encoded.split(";")) {
            int comma = row.indexOf(',');
            String id = comma > 0 ? row.substring(0, comma) : row;
            if (isVideoId(id) && !out.contains(id)) out.add(id);
        }
        return out;
    }

    private static void schedulePlay(Context context, String videoId) {
        AlarmManager am = (AlarmManager) context.getSystemService(Context.ALARM_SERVICE);
        Intent intent = new Intent(context, AlarmReceiver.class)
                .setAction(WakeAndPlayActivity.ACTION_PLAY)
                .putExtra(WakeAndPlayActivity.EXTRA_VIDEO, videoId);
        PendingIntent pi = PendingIntent.getBroadcast(
                context, 702, intent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        long when = System.currentTimeMillis() + PLAY_AFTER_VERIFY_MS;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, when, pi);
        } else {
            am.setExact(AlarmManager.RTC_WAKEUP, when, pi);
        }
        Log.i(TAG, "Scheduled playback handoff for " + videoId);
    }

    private static void routePlayback(Context context, String action, String videoId) {
        if (!isVideoId(videoId)) {
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
            SharedPreferences prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
            Set<String> played = new HashSet<>(
                    prefs.getStringSet(KEY_PLAYED, new HashSet<>()));
            played.add(videoId);
            if (played.size() > 250) {
                played.clear();
                played.add(videoId);
            }
            prefs.edit()
                 .putStringSet(KEY_PLAYED, played)
                 .remove(KEY_PENDING)
                 .remove(KEY_CANDIDATES)
                 .apply();
            Log.i(TAG, "Recorded played video " + videoId);
        }
    }

    private static boolean isVideoId(String value) {
        return value != null && value.matches("[A-Za-z0-9_-]{11}");
    }

    private static String fetch(String urlText) throws Exception {
        HttpURLConnection connection = (HttpURLConnection) new URL(urlText).openConnection();
        connection.setConnectTimeout(2_000);
        connection.setReadTimeout(2_000);
        try {
            int status = connection.getResponseCode();
            if (status < 200 || status >= 300) throw new IllegalStateException("HTTP " + status);
            InputStream input = connection.getInputStream();
            BufferedReader reader = new BufferedReader(
                    new InputStreamReader(input, StandardCharsets.UTF_8));
            StringBuilder out = new StringBuilder();
            char[] buffer = new char[4096];
            int read;
            while ((read = reader.read(buffer)) != -1) {
                out.append(buffer, 0, read);
                if (out.length() > 1_000_000) throw new IllegalStateException("DIAL response too large");
            }
            return out.toString();
        } finally {
            connection.disconnect();
        }
    }

    private static String unescapeXml(String value) {
        return value.replace("&quot;", "\"")
                .replace("&apos;", "'")
                .replace("&lt;", "<")
                .replace("&gt;", ">")
                .replace("&amp;", "&");
    }
}
