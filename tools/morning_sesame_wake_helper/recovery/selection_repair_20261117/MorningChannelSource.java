package dev.yossi.morningsesame;

import android.content.Context;
import android.os.SystemClock;
import android.util.Log;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import org.json.JSONObject;

/** Bounded pagination of the same channel's authenticated-by-origin public Videos shelf. */
final class MorningChannelSource {
    private static final int MAX_PAGES = 12;
    private static final long PAGE_BUDGET_MS = 40_000L;

    private MorningChannelSource() {}

    static Map<String, Integer> fetch(Context context, String runId, String initialHtml) throws Exception {
        ensureCurrent(context, runId);
        MorningChannelPage page = MorningChannelPage.initial(initialHtml);
        JSONObject client = page.client;
        Map<String, Integer> result = new LinkedHashMap<>(page.videos);
        Set<String> requested = new HashSet<>();
        long deadline = SystemClock.elapsedRealtime() + Math.min(PAGE_BUDGET_MS,
                Math.max(0L, RunCoordinator.remaining(context, runId) - 90_000L));
        int pages = 1;
        while (client != null && page.continuation != null && pages < MAX_PAGES
                && result.size() < MorningChannelPage.MAX_CANDIDATES
                && SystemClock.elapsedRealtime() < deadline && requested.add(page.continuation)) {
            ensureCurrent(context, runId);
            try {
                String response = post(context, runId, client, page.continuation, deadline);
                page = MorningChannelPage.continuation(response);
                pages++;
                for (Map.Entry<String, Integer> entry : page.videos.entrySet()) {
                    if (result.size() >= MorningChannelPage.MAX_CANDIDATES) break;
                    if (!result.containsKey(entry.getKey())) result.put(entry.getKey(), entry.getValue());
                }
            } catch (InterruptedException cancelled) { throw cancelled; }
            catch (Exception failedPage) {
                // Keep verified newer pages, never substitute an unrelated or previously watched video.
                Log.w("MorningSesame", "Older episode page unavailable; retained " + result.size() + " qualifying source entries");
                break;
            }
        }
        ensureCurrent(context, runId);
        Log.i("MorningSesame", "Read " + pages + " newest-first Sesame channel pages, " + result.size() + " episodes longer than 40 minutes");
        return result;
    }

    private static String post(Context context, String runId, JSONObject client, String token, long deadline) throws Exception {
        int remaining = (int) Math.max(1L, deadline - SystemClock.elapsedRealtime());
        HttpURLConnection connection = (HttpURLConnection) new URL(
                "https://www.youtube.com/youtubei/v1/browse?prettyPrint=false").openConnection();
        connection.setConnectTimeout(Math.min(8_000, remaining));
        connection.setReadTimeout(Math.min(8_000, remaining));
        connection.setInstanceFollowRedirects(false);
        connection.setRequestMethod("POST");
        connection.setDoOutput(true);
        connection.setRequestProperty("Content-Type", "application/json; charset=UTF-8");
        connection.setRequestProperty("User-Agent", "Mozilla/5.0 (Android TV; Morning Sesame)");
        connection.setRequestProperty("Accept-Language", "en-US,en;q=0.9");
        byte[] request = new JSONObject().put("context", new JSONObject().put("client", client))
                .put("continuation", token).toString().getBytes(StandardCharsets.UTF_8);
        connection.setFixedLengthStreamingMode(request.length);
        try {
            ensureCurrent(context, runId);
            try (OutputStream output = connection.getOutputStream()) { output.write(request); }
            if (connection.getResponseCode() != 200) throw new IllegalStateException("continuation_http_error");
            StringBuilder body = new StringBuilder();
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(connection.getInputStream(), StandardCharsets.UTF_8))) {
                char[] buffer = new char[8192];
                int count;
                while ((count = reader.read(buffer)) != -1) {
                    ensureCurrent(context, runId);
                    if (SystemClock.elapsedRealtime() >= deadline) throw new IllegalStateException("continuation_deadline");
                    if (body.length() + count > 3_000_000) throw new IllegalStateException("continuation_too_large");
                    body.append(buffer, 0, count);
                }
            }
            return body.toString();
        } finally { connection.disconnect(); }
    }

    private static void ensureCurrent(Context context, String runId) throws InterruptedException {
        if (Thread.currentThread().isInterrupted() || !RunCoordinator.startupWorkAllowed(context, runId)
                || !RunCoordinator.keepAwake(context, runId)) throw new InterruptedException("morning_startup_cancelled");
    }
}
