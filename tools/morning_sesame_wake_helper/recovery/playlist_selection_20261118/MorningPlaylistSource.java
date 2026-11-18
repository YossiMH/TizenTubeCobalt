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

/** Complete exact playlist membership intersected with the channel's verified Latest upload order. */
final class MorningPlaylistSource {
    private static final int MAX_PLAYLIST_PAGES = 16;
    private static final int MAX_CHANNEL_PAGES = 24;
    private static final long PAGE_BUDGET_MS = 40_000L;
    // The mobile user agent is redirected from www to m; use the verified destination directly.
    private static final String LATEST_URL = "https://m.youtube.com/@SesameStreetClassics/videos";

    private MorningPlaylistSource() {}

    static Map<String, Integer> fetch(Context context, String runId, String initialHtml) throws Exception {
        ensureCurrent(context, runId);
        long deadline = SystemClock.elapsedRealtime() + Math.min(PAGE_BUDGET_MS,
                Math.max(0L, RunCoordinator.remaining(context, runId) - 90_000L));
        MorningPlaylistPage playlist = MorningPlaylistPage.initial(initialHtml);
        JSONObject client = playlist.client;
        Set<String> members = new HashSet<>(playlist.videos.keySet());
        Set<String> tokens = new HashSet<>();
        int playlistPages = 1;
        while (playlist.continuation != null) {
            if (client == null || playlistPages >= MAX_PLAYLIST_PAGES || !tokens.add(playlist.continuation))
                throw new IllegalStateException("playlist_membership_incomplete");
            playlist = MorningPlaylistPage.continuation(request(context, runId, null, client, playlist.continuation, deadline));
            playlistPages++;
            members.addAll(playlist.videos.keySet());
            if (members.size() > MorningYouTubeHistory.MAX_VIDEOS)
                throw new IllegalStateException("playlist_membership_too_large");
        }
        MorningChannelPage channel = MorningChannelPage.initial(request(context, runId, LATEST_URL, null, null, deadline));
        client = channel.client;
        Map<String, Integer> result = new LinkedHashMap<>();
        intersect(result, channel.videos, members);
        tokens.clear();
        int channelPages = 1;
        while (channel.continuation != null && result.size() < members.size()
                && channelPages < MAX_CHANNEL_PAGES && SystemClock.elapsedRealtime() < deadline) {
            if (client == null || !tokens.add(channel.continuation)) break;
            try {
                channel = MorningChannelPage.continuation(request(context, runId, null, client, channel.continuation, deadline));
                channelPages++;
                intersect(result, channel.videos, members);
            } catch (InterruptedException cancelled) { throw cancelled; }
            catch (Exception unavailableOlderPage) {
                // Every retained entry belongs to the complete playlist and the verified newest upload prefix.
                Log.w("MorningSesame", "Older uploads unavailable; retained " + result.size() + " exact-playlist episodes");
                break;
            }
        }
        ensureCurrent(context, runId);
        Log.i("MorningSesame", "Read complete playlist across " + playlistPages + " pages; "
                + channelPages + " Latest upload pages yielded " + result.size() + " qualifying full episodes");
        return result;
    }

    static void intersect(Map<String, Integer> result, Map<String, Integer> newestFirst, Set<String> members) {
        for (Map.Entry<String, Integer> entry : newestFirst.entrySet()) {
            if (members.contains(entry.getKey()) && !result.containsKey(entry.getKey()))
                result.put(entry.getKey(), entry.getValue());
        }
    }

    private static String request(Context context, String runId, String getUrl, JSONObject client,
                                  String token, long deadline) throws Exception {
        ensureCurrent(context, runId);
        long remaining = deadline - SystemClock.elapsedRealtime();
        if (remaining <= 0) throw new IllegalStateException("candidate_deadline");
        HttpURLConnection connection = (HttpURLConnection) new URL(getUrl == null
                ? "https://www.youtube.com/youtubei/v1/browse?prettyPrint=false" : getUrl).openConnection();
        connection.setConnectTimeout((int) Math.min(8_000L, remaining));
        connection.setReadTimeout((int) Math.min(8_000L, remaining));
        connection.setInstanceFollowRedirects(false);
        connection.setRequestProperty("User-Agent", "Mozilla/5.0 (Android TV; Morning Sesame)");
        connection.setRequestProperty("Accept-Language", "en-US,en;q=0.9");
        try {
            if (getUrl == null) {
                connection.setRequestMethod("POST");
                connection.setDoOutput(true);
                connection.setRequestProperty("Content-Type", "application/json; charset=UTF-8");
                byte[] bytes = new JSONObject().put("context", new JSONObject().put("client", client))
                        .put("continuation", token).toString().getBytes(StandardCharsets.UTF_8);
                connection.setFixedLengthStreamingMode(bytes.length);
                try (OutputStream output = connection.getOutputStream()) { output.write(bytes); }
            }
            if (connection.getResponseCode() != 200) throw new IllegalStateException("candidate_http_error");
            StringBuilder body = new StringBuilder();
            try (BufferedReader input = new BufferedReader(new InputStreamReader(connection.getInputStream(), StandardCharsets.UTF_8))) {
                char[] buffer = new char[8192];
                int count;
                while ((count = input.read(buffer)) != -1) {
                    ensureCurrent(context, runId);
                    if (SystemClock.elapsedRealtime() >= deadline) throw new IllegalStateException("candidate_deadline");
                    if (body.length() + count > 3_000_000) throw new IllegalStateException("candidate_response_too_large");
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
