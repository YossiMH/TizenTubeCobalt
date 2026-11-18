package dev.yossi.morningsesame;

import java.util.LinkedHashMap;
import java.util.Map;
import org.json.JSONArray;
import org.json.JSONObject;

/** Exact playlist membership, including current lockup cards and their continuation view model. */
final class MorningPlaylistPage {
    static final String PLAYLIST_ID = MorningYouTubeHistory.PLAYLIST_ID;
    static final String URL = "https://www.youtube.com/playlist?list=" + PLAYLIST_ID;
    private static final int MAX_DEPTH = 64;
    final Map<String, Integer> videos = new LinkedHashMap<>();
    String continuation;
    JSONObject client;

    static MorningPlaylistPage initial(String html) throws Exception {
        if (html == null || html.length() > 3_000_000) throw new IllegalArgumentException("invalid_playlist_size");
        JSONObject data = new JSONObject(MorningChannelPage.initialJson(html));
        JSONObject microformat = data.optJSONObject("microformat");
        microformat = microformat == null ? null : microformat.optJSONObject("microformatDataRenderer");
        String canonical = microformat == null ? "" : microformat.optString("urlCanonical");
        if (!canonical.matches("https?://(?:www\\.|m\\.)?youtube\\.com/playlist\\?list=" + PLAYLIST_ID + "(?:&[^#]*)?"))
            throw new IllegalArgumentException("unexpected_playlist");
        JSONObject contents = data.optJSONObject("contents");
        JSONObject browse = contents == null ? null : contents.optJSONObject("singleColumnBrowseResultsRenderer");
        if (browse == null && contents != null) browse = contents.optJSONObject("twoColumnBrowseResultsRenderer");
        JSONArray tabs = browse == null ? null : browse.optJSONArray("tabs");
        JSONObject selected = null;
        if (tabs != null) for (int i = 0; i < tabs.length(); i++) {
            JSONObject wrapper = tabs.optJSONObject(i);
            JSONObject tab = wrapper == null ? null : wrapper.optJSONObject("tabRenderer");
            if (tab != null && tab.optBoolean("selected")) { selected = tab.optJSONObject("content"); break; }
        }
        if (selected == null) throw new IllegalArgumentException("playlist_shelf_missing");
        MorningPlaylistPage result = new MorningPlaylistPage();
        result.collect(selected, false, 0);
        if (result.videos.isEmpty()) throw new IllegalArgumentException("playlist_has_no_playable_entries");
        result.client = MorningChannelPage.publicClient(html);
        return result;
    }

    static MorningPlaylistPage continuation(String body) throws Exception {
        if (body == null || body.length() > 3_000_000) throw new IllegalArgumentException("invalid_playlist_continuation_size");
        JSONObject data = new JSONObject(body);
        MorningPlaylistPage result = new MorningPlaylistPage();
        boolean recognized = false;
        for (String key : new String[]{"onResponseReceivedActions", "onResponseReceivedEndpoints"}) {
            JSONArray actions = data.optJSONArray(key);
            if (actions == null) continue;
            for (int i = 0; i < actions.length(); i++) {
                JSONObject action = actions.optJSONObject(i);
                JSONObject append = action == null ? null : action.optJSONObject("appendContinuationItemsAction");
                if (append == null) continue;
                String target = append.optString("targetId");
                if (!target.isEmpty() && !scopeId(target)) continue;
                JSONArray items = append.optJSONArray("continuationItems");
                if (items != null) { recognized = true; result.collect(items, true, 0); }
            }
        }
        JSONObject containers = data.optJSONObject("continuationContents");
        if (containers != null) for (String key : new String[]{"playlistVideoListContinuation", "sectionListContinuation", "itemSectionContinuation"}) {
            JSONObject container = containers.optJSONObject(key);
            if (container != null) { recognized = true; result.collect(container, true, 0); }
        }
        if (!recognized) throw new IllegalArgumentException("playlist_continuation_missing");
        return result;
    }

    private void collect(Object value, boolean scoped, int depth) throws Exception {
        if (depth > MAX_DEPTH) throw new IllegalArgumentException("playlist_too_deep");
        if (value instanceof JSONArray) {
            JSONArray array = (JSONArray) value;
            for (int i = 0; i < array.length(); i++) collect(array.opt(i), scoped, depth + 1);
            return;
        }
        if (!(value instanceof JSONObject)) return;
        JSONObject object = (JSONObject) value;
        String target = object.optString("targetId");
        if (!target.isEmpty()) {
            if (!scopeId(target)) return;
            scoped = true;
        }
        JSONObject lockup = object.optJSONObject("lockupViewModel");
        if (lockup != null) {
            JSONObject endpoint = child(lockup, "rendererContext", "commandContext", "onTap", "innertubeCommand", "watchEndpoint");
            String id = lockup.optString("contentId");
            if ("LOCKUP_CONTENT_TYPE_VIDEO".equals(lockup.optString("contentType"))
                    && endpoint != null && PLAYLIST_ID.equals(endpoint.optString("playlistId"))
                    && id.equals(endpoint.optString("videoId"))) add(id, lockupDuration(lockup));
            return;
        }
        JSONObject legacy = object.optJSONObject("playlistVideoRenderer");
        if (legacy != null) {
            JSONObject endpoint = child(legacy, "navigationEndpoint", "watchEndpoint");
            String playlist = endpoint == null ? "" : endpoint.optString("playlistId");
            if ((PLAYLIST_ID.equals(playlist) || scoped && playlist.isEmpty())
                    && !Boolean.FALSE.equals(legacy.opt("isPlayable"))) {
                String id = legacy.optString("videoId");
                if (endpoint == null || id.equals(endpoint.optString("videoId", id)))
                    add(id, MorningChannelPage.duration(MorningChannelPage.text(legacy.optJSONObject("lengthText"))));
            }
            return;
        }
        JSONObject continuationView = object.optJSONObject("continuationItemViewModel");
        if (continuationView != null) {
            JSONObject command = child(continuationView, "continuationCommand", "innertubeCommand", "continuationCommand");
            if (command != null) setContinuation(command.optString("token"));
            return;
        }
        JSONObject continuationRenderer = object.optJSONObject("continuationItemRenderer");
        if (continuationRenderer != null) {
            JSONObject command = child(continuationRenderer, "continuationEndpoint", "continuationCommand");
            if (command != null) setContinuation(command.optString("token"));
            return;
        }
        JSONObject next = object.optJSONObject("nextContinuationData");
        if (next != null) { setContinuation(next.optString("continuation")); return; }
        JSONObject list = object.optJSONObject("playlistVideoListRenderer");
        if (list != null) {
            String id = list.optString("playlistId");
            if (!id.isEmpty() && !PLAYLIST_ID.equals(id)) return;
            collect(list, scoped || PLAYLIST_ID.equals(id), depth + 1);
            return;
        }
        // Card commands, menus and recommendations never establish membership.
        for (String key : new String[]{"sectionListRenderer", "itemSectionRenderer", "richItemRenderer",
                "contents", "items", "content", "continuations", "continuationItems"})
            if (object.has(key)) collect(object.opt(key), scoped, depth + 1);
    }

    private void add(String id, int seconds) {
        if (!MorningEpisodePolicy.validId(id)) return;
        if (!videos.containsKey(id) && videos.size() >= MorningYouTubeHistory.MAX_VIDEOS)
            throw new IllegalArgumentException("playlist_too_large");
        if (!videos.containsKey(id)) videos.put(id, seconds);
    }

    private void setContinuation(String token) {
        if (token == null || token.isEmpty() || token.length() > 16384)
            throw new IllegalArgumentException("invalid_playlist_continuation");
        if (continuation != null && !continuation.equals(token))
            throw new IllegalArgumentException("ambiguous_playlist_continuation");
        continuation = token;
    }

    private static boolean scopeId(String id) { return PLAYLIST_ID.equals(id) || ("VL" + PLAYLIST_ID).equals(id); }

    private static int lockupDuration(JSONObject lockup) {
        JSONObject thumbnail = child(lockup, "contentImage", "thumbnailViewModel");
        JSONArray overlays = thumbnail == null ? null : thumbnail.optJSONArray("overlays");
        if (overlays != null) for (int i = 0; i < overlays.length(); i++) {
            JSONObject overlay = overlays.optJSONObject(i);
            JSONObject bottom = overlay == null ? null : overlay.optJSONObject("thumbnailBottomOverlayViewModel");
            JSONArray badges = bottom == null ? null : bottom.optJSONArray("badges");
            if (badges != null) for (int j = 0; j < badges.length(); j++) {
                JSONObject badge = badges.optJSONObject(j);
                badge = badge == null ? null : badge.optJSONObject("thumbnailBadgeViewModel");
                int seconds = badge == null ? 0 : MorningChannelPage.duration(badge.optString("text"));
                if (seconds > 0) return seconds;
            }
        }
        return 0;
    }

    private static JSONObject child(JSONObject object, String... keys) {
        for (String key : keys) { object = object == null ? null : object.optJSONObject(key); }
        return object;
    }
}
