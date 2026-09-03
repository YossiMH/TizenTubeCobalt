package dev.yossi.morningsesame;

import android.app.Activity;
import android.app.AlarmManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.icu.util.HebrewCalendar;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.PowerManager;
import android.util.Log;
import android.view.WindowManager;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.Calendar;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Executors;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class WakeAndPlayActivity extends Activity {
    private static final String TAG = "MorningSesame";
    private static final String TARGET_PACKAGE = "io.gh.yossim.tizentube.cobalt";
    private static final String TARGET_ACTIVITY = "dev.cobalt.app.MainActivity";
    private static final String CHANNEL_URL = "https://www.youtube.com/@SesameStreetClassics/videos";
    private static final String PREFS = "morning_sesame";
    private static final String KEY_PLAYED = "played_ids";
    private static final String KEY_PENDING = "pending_video";
    private static final String KEY_CANDIDATES = "candidate_list";
    private static final int MIN_SECONDS = 30 * 60;

    public static final String ACTION_SETUP = "dev.yossi.morningsesame.SETUP";
    public static final String ACTION_RUN = "dev.yossi.morningsesame.RUN";
    public static final String ACTION_VERIFY = "dev.yossi.morningsesame.VERIFY";
    public static final String ACTION_PLAY = "dev.yossi.morningsesame.PLAY";
    static final String EXTRA_VIDEO = "video_id";
    private static final String EXTRA_TEST_DELAY = "test_delay_ms";

    private static final int REQ_RUN = 700;
    private static final int REQ_VERIFY = 701;
    private static final int REQ_PLAY = 702;
    private static final long VERIFY_DELAY_MS = 65_000L;
    private static final long PLAY_DELAY_MS = 75_000L;
    private static final long WAKE_TIMEOUT_MS = 130_000L;

    private static final Pattern VIDEO_ENTRY = Pattern.compile(
            "\"videoWithContextRenderer\".*?\"lengthText\".*?"
                    + "\"text\":\"([0-9:]+)\".*?"
                    + "\"videoId\":\"([A-Za-z0-9_-]{11})\"",
            Pattern.DOTALL);

    private static PowerManager.WakeLock wakeLock;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        String action = getIntent().getAction();
        if (ACTION_SETUP.equals(action)) {
            long testDelay = getIntent().getLongExtra(EXTRA_TEST_DELAY, 0L);
            if (testDelay > 0L) {
                scheduleRunAt(this, System.currentTimeMillis() + testDelay);
                Log.i(TAG, "Scheduled one-time acceptance run in " + testDelay + " ms");
            } else {
                scheduleNext(this);
            }
            finish();
            return;
        }

        if (ACTION_VERIFY.equals(action) || ACTION_PLAY.equals(action)) {
            wakeScreen(35_000L);
            String videoId = getIntent().getStringExtra(EXTRA_VIDEO);
            if (videoId == null || !videoId.matches("[A-Za-z0-9_-]{11}")) {
                videoId = prefs().getString(KEY_PENDING, null);
            }
            if (videoId != null) {
                handoffVideo(videoId, ACTION_PLAY.equals(action) ? "playback" : "verification");
                if (ACTION_PLAY.equals(action)) {
                    recordPlayed(videoId);
                    prefs().edit().remove(KEY_PENDING).apply();
                    new Handler(Looper.getMainLooper()).postDelayed(WakeAndPlayActivity::releaseWakeLock, 15_000L);
                }
            }
            finish();
            return;
        }

        if (ACTION_RUN.equals(action)) {
            scheduleNext(this);
            beginScheduledRun();
            return;
        }

        Uri direct = getIntent().getData();
        if (direct != null) {
            String videoId = direct.getQueryParameter("v");
            if (videoId != null && videoId.matches("[A-Za-z0-9_-]{11}")) {
                scheduleNext(this);
                beginWithVideo(videoId);
                return;
            }
        }

        scheduleNext(this);
        finish();
    }

    private void beginScheduledRun() {
        wakeScreen(WAKE_TIMEOUT_MS);
        Executors.newSingleThreadExecutor().execute(() -> {
            try {
                Map<String, Integer> candidates = fetchCandidates();
                if (candidates.isEmpty()) {
                    Log.e(TAG, "No qualifying >30 minute Sesame Street Classics video found");
                    runOnUiThread(() -> {
                        releaseWakeLock();
                        finish();
                    });
                    return;
                }
                prefs().edit()
                    .putString(KEY_CANDIDATES, serializeCandidates(candidates))
                    .remove(KEY_PENDING)
                    .apply();
                Log.i(TAG, "Prepared " + candidates.size() + " qualifying videos for account-progress selection");
                runOnUiThread(this::beginDeferredSelection);
            } catch (Exception e) {
                Log.e(TAG, "Video selection failed", e);
                runOnUiThread(() -> {
                    releaseWakeLock();
                    finish();
                });
            }
        });
    }

    private void beginDeferredSelection() {
        wakeScreen(WAKE_TIMEOUT_MS);
        try {
            Intent warm = new Intent(Intent.ACTION_MAIN);
            warm.setClassName(TARGET_PACKAGE, TARGET_ACTIVITY);
            warm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
            startActivity(warm);
            Log.i(TAG, "TizenSub+ warm-up requested before account-progress selection");
        } catch (Exception e) {
            Log.e(TAG, "TizenSub+ warm-up failed", e);
            releaseWakeLock();
            finish();
            return;
        }
        schedulePhase(ACTION_VERIFY, REQ_VERIFY, VERIFY_DELAY_MS, null);
        Log.i(TAG, "Scheduled account-progress selection and verification");
    }

    private void beginWithVideo(String videoId) {
        wakeScreen(WAKE_TIMEOUT_MS);
        prefs().edit().putString(KEY_PENDING, videoId).apply();

        try {
            Intent warm = new Intent(Intent.ACTION_MAIN);
            warm.setClassName(TARGET_PACKAGE, TARGET_ACTIVITY);
            warm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
            startActivity(warm);
            Log.i(TAG, "TizenSub+ warm-up requested for " + videoId);
        } catch (Exception e) {
            Log.e(TAG, "TizenSub+ warm-up failed", e);
            releaseWakeLock();
            finish();
            return;
        }

        schedulePhase(ACTION_VERIFY, REQ_VERIFY, VERIFY_DELAY_MS, videoId);
        schedulePhase(ACTION_PLAY, REQ_PLAY, PLAY_DELAY_MS, videoId);
        Log.i(TAG, "Staged verification/playback handoffs for " + videoId);
    }

    private void schedulePhase(String action, int requestCode, long delayMs, String videoId) {
        AlarmManager am = (AlarmManager) getSystemService(ALARM_SERVICE);
        Intent intent = new Intent(this, AlarmReceiver.class).setAction(action);
        if (videoId != null && videoId.matches("[A-Za-z0-9_-]{11}")) {
            intent.putExtra(EXTRA_VIDEO, videoId);
        }
        PendingIntent pi = PendingIntent.getBroadcast(
                this, requestCode, intent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        setExact(am, System.currentTimeMillis() + delayMs, pi);
    }

    private void handoffVideo(String videoId, String phase) {
        try {
            Uri url = Uri.parse("https://www.youtube.com/watch?v=" + videoId);
            Intent play = new Intent(Intent.ACTION_VIEW, url);
            play.setPackage(TARGET_PACKAGE);
            play.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(play);
            Log.i(TAG, "TizenSub+ " + phase + " handoff requested for " + videoId);
        } catch (Exception e) {
            Log.e(TAG, "TizenSub+ " + phase + " handoff failed", e);
        }
    }

    private Map<String, Integer> fetchCandidates() throws Exception {
        String html = decodeHexEscapes(fetch(CHANNEL_URL));
        Matcher matcher = VIDEO_ENTRY.matcher(html);
        Map<String, Integer> ordered = new LinkedHashMap<>();
        while (matcher.find() && ordered.size() < 60) {
            int duration = parseDuration(matcher.group(1));
            String videoId = matcher.group(2);
            if (duration > MIN_SECONDS && !ordered.containsKey(videoId)) {
                ordered.put(videoId, duration);
            }
        }
        return ordered;
    }

    private static String serializeCandidates(Map<String, Integer> candidates) {
        StringBuilder out = new StringBuilder();
        for (Map.Entry<String, Integer> entry : candidates.entrySet()) {
            if (out.length() > 0) out.append(';');
            out.append(entry.getKey()).append(',').append(entry.getValue());
        }
        return out.toString();
    }

    private static String decodeHexEscapes(String input) {
        StringBuilder output = new StringBuilder(input.length());
        for (int i = 0; i < input.length(); i++) {
            char current = input.charAt(i);
            if (current == '\\' && i + 3 < input.length()
                    && input.charAt(i + 1) == 'x') {
                int high = Character.digit(input.charAt(i + 2), 16);
                int low = Character.digit(input.charAt(i + 3), 16);
                if (high >= 0 && low >= 0) {
                    output.append((char) ((high << 4) | low));
                    i += 3;
                    continue;
                }
            }
            output.append(current);
        }
        return output.toString();
    }

    private static int parseDuration(String text) {
        String[] parts = text.split(":");
        try {
            if (parts.length == 2) return Integer.parseInt(parts[0]) * 60 + Integer.parseInt(parts[1]);
            if (parts.length == 3) {
                return Integer.parseInt(parts[0]) * 3600
                        + Integer.parseInt(parts[1]) * 60
                        + Integer.parseInt(parts[2]);
            }
        } catch (NumberFormatException ignored) {}
        return 0;
    }

    private static String fetch(String urlText) throws Exception {
        HttpURLConnection connection = (HttpURLConnection) new URL(urlText).openConnection();
        connection.setConnectTimeout(15_000);
        connection.setReadTimeout(20_000);
        connection.setInstanceFollowRedirects(true);
        connection.setRequestProperty("User-Agent", "Mozilla/5.0 (Android TV; Morning Sesame)");
        connection.setRequestProperty("Accept-Language", "en-US,en;q=0.9");
        try {
            int status = connection.getResponseCode();
            if (status < 200 || status >= 300) throw new IllegalStateException("HTTP " + status);
            InputStream input = connection.getInputStream();
            BufferedReader reader = new BufferedReader(new InputStreamReader(input, StandardCharsets.UTF_8));
            StringBuilder out = new StringBuilder(1_200_000);
            char[] buffer = new char[8192];
            int read;
            while ((read = reader.read(buffer)) != -1) {
                out.append(buffer, 0, read);
                if (out.length() > 3_000_000) throw new IllegalStateException("Channel page unexpectedly large");
            }
            return out.toString();
        } finally {
            connection.disconnect();
        }
    }

    private void recordPlayed(String videoId) {
        Set<String> played = new HashSet<>(prefs().getStringSet(KEY_PLAYED, new HashSet<>()));
        played.add(videoId);
        if (played.size() > 250) {
            played.clear();
            played.add(videoId);
        }
        prefs().edit().putStringSet(KEY_PLAYED, played).apply();
        Log.i(TAG, "Recorded played video " + videoId);
    }

    private SharedPreferences prefs() {
        return getSharedPreferences(PREFS, MODE_PRIVATE);
    }

    private void wakeScreen(long timeoutMs) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            setTurnScreenOn(true);
            setShowWhenLocked(true);
        }
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON
                | WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        PowerManager power = (PowerManager) getSystemService(POWER_SERVICE);
        if (power == null) return;
        if (wakeLock == null) {
            wakeLock = power.newWakeLock(PowerManager.FULL_WAKE_LOCK
                            | PowerManager.ACQUIRE_CAUSES_WAKEUP
                            | PowerManager.ON_AFTER_RELEASE,
                    "MorningSesame:Wake");
            wakeLock.setReferenceCounted(false);
        }
        if (!wakeLock.isHeld()) wakeLock.acquire(timeoutMs);
    }

    private static void releaseWakeLock() {
        if (wakeLock != null && wakeLock.isHeld()) wakeLock.release();
    }

    public static void scheduleNext(Context context) {
        Calendar now = Calendar.getInstance();
        for (int offset = 0; offset < 16; offset++) {
            Calendar candidate = (Calendar) now.clone();
            candidate.add(Calendar.DAY_OF_MONTH, offset);
            candidate.set(Calendar.HOUR_OF_DAY, 7);
            candidate.set(Calendar.MINUTE, 0);
            candidate.set(Calendar.SECOND, 0);
            candidate.set(Calendar.MILLISECOND, 0);
            if (candidate.getTimeInMillis() <= now.getTimeInMillis() + 1_000L) continue;
            if (candidate.get(Calendar.DAY_OF_WEEK) == Calendar.SATURDAY) continue;
            if (isYomTov(candidate)) continue;
            scheduleRunAt(context, candidate.getTimeInMillis());
            Log.i(TAG, "Next morning run: " + candidate.getTime());
            return;
        }
        Log.e(TAG, "Could not find next allowed morning within 16 days");
    }

    private static void scheduleRunAt(Context context, long whenMillis) {
        AlarmManager am = (AlarmManager) context.getSystemService(ALARM_SERVICE);
        Intent intent = new Intent(context, AlarmReceiver.class).setAction(ACTION_RUN);
        PendingIntent pi = PendingIntent.getBroadcast(
                context, REQ_RUN, intent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        am.cancel(pi);
        setExact(am, whenMillis, pi);
    }

    private static void setExact(AlarmManager am, long whenMillis, PendingIntent pi) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, whenMillis, pi);
        } else {
            am.setExact(AlarmManager.RTC_WAKEUP, whenMillis, pi);
        }
    }

    private static boolean isYomTov(Calendar day) {
        HebrewCalendar hebrew = new HebrewCalendar();
        hebrew.setTimeInMillis(day.getTimeInMillis());
        int month = hebrew.get(HebrewCalendar.MONTH);
        int date = hebrew.get(HebrewCalendar.DATE);
        return (month == HebrewCalendar.TISHRI
                    && (date == 1 || date == 2 || date == 10
                        || date == 15 || date == 16 || date == 22 || date == 23))
                || (month == HebrewCalendar.NISAN
                    && (date == 15 || date == 16 || date == 21 || date == 22))
                || (month == HebrewCalendar.SIVAN && (date == 6 || date == 7));
    }

    private static final class Candidate {
        final String videoId;
        final int durationSeconds;
        Candidate(String videoId, int durationSeconds) {
            this.videoId = videoId;
            this.durationSeconds = durationSeconds;
        }
    }
}
