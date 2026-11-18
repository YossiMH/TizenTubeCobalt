package dev.yossi.morningsesame;

import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import org.json.JSONArray;
import org.json.JSONObject;

/** Parses only the selected channel Videos shelf, preserving each array's newest-first order. */
final class MorningChannelPage {
    static final String CHANNEL_ID = "UC7DM7591Xkt1M2D3fs_wAlg";
    static final int MAX_CANDIDATES = 512;
    private static final int MAX_DEPTH = 64;
    private static final int MAX_TOKEN_CHARS = 16384;
    final Map<String, Integer> videos = new LinkedHashMap<>();
    String continuation;
    JSONObject client;

    static MorningChannelPage initial(String html) throws Exception {
        if (html == null || html.length() > 3_000_000) throw new IllegalArgumentException("invalid_channel_page_size");
        JSONObject data = new JSONObject(initialJson(html));
        JSONObject metadata = data.optJSONObject("metadata");
        metadata = metadata == null ? null : metadata.optJSONObject("channelMetadataRenderer");
        if (metadata == null || !CHANNEL_ID.equals(metadata.optString("externalId")))
            throw new IllegalArgumentException("unexpected_channel");
        if (wrongSort(data, 0) || !hasLatestSort(data, 0)) throw new IllegalArgumentException("channel_not_newest_first");
        JSONObject contents = data.optJSONObject("contents");
        JSONObject browse = contents == null ? null : contents.optJSONObject("singleColumnBrowseResultsRenderer");
        if (browse == null && contents != null) browse = contents.optJSONObject("twoColumnBrowseResultsRenderer");
        JSONArray tabs = browse == null ? null : browse.optJSONArray("tabs");
        JSONObject selected = null;
        if (tabs != null) for (int i = 0; i < tabs.length(); i++) {
            JSONObject wrapper = tabs.optJSONObject(i);
            JSONObject tab = wrapper == null ? null : wrapper.optJSONObject("tabRenderer");
            if (tab != null && tab.optBoolean("selected") && "Videos".equals(tab.optString("title"))) {
                selected = tab.optJSONObject("content");
                break;
            }
        }
        if (selected == null) throw new IllegalArgumentException("selected_videos_shelf_missing");
        MorningChannelPage page = new MorningChannelPage();
        page.collect(selected, 0);
        page.client = publicClient(html);
        return page;
    }

    static JSONObject publicClient(String html) throws Exception {
        String contextJson = objectAfter(html, "\"INNERTUBE_CONTEXT\":");
        if (contextJson != null) {
            JSONObject context = new JSONObject(contextJson).optJSONObject("client");
            if (context != null) {
                String name = context.optString("clientName"), version = context.optString("clientVersion");
                if (("MWEB".equals(name) || "WEB".equals(name)) && version.matches("[A-Za-z0-9._-]{1,80}")) {
                    return new JSONObject().put("clientName", name).put("clientVersion", version)
                            .put("hl", "en").put("gl", "US");
                }
            }
        }
        return null;
    }

    static MorningChannelPage continuation(String body) throws Exception {
        if (body == null || body.length() > 3_000_000) throw new IllegalArgumentException("invalid_continuation_size");
        JSONObject data = new JSONObject(body);
        MorningChannelPage page = new MorningChannelPage();
        // Only renderer continuation bodies, never recommendations or unrelated metadata.
        for (String key : new String[]{"onResponseReceivedActions", "onResponseReceivedEndpoints"}) {
            JSONArray actions = data.optJSONArray(key);
            if (actions == null) continue;
            for (int i = 0; i < actions.length(); i++) {
                JSONObject action = actions.optJSONObject(i);
                if (action == null) continue;
                JSONObject append = action.optJSONObject("appendContinuationItemsAction");
                if (append != null) page.collect(append.optJSONArray("continuationItems"), 0);
            }
        }
        JSONObject continuations = data.optJSONObject("continuationContents");
        if (continuations != null) {
            for (String key : new String[]{"sectionListContinuation", "itemSectionContinuation", "gridContinuation", "richGridContinuation"}) {
                JSONObject content = continuations.optJSONObject(key);
                if (content != null) page.collect(content, 0);
            }
        }
        return page;
    }

    private void collect(Object value, int depth) throws Exception {
        if (value == null || depth > MAX_DEPTH || videos.size() >= MAX_CANDIDATES) return;
        if (value instanceof JSONArray) {
            JSONArray array = (JSONArray) value;
            for (int i = 0; i < array.length(); i++) collect(array.opt(i), depth + 1);
            return;
        }
        if (!(value instanceof JSONObject)) return;
        JSONObject object = (JSONObject) value;
        for (String name : new String[]{"videoWithContextRenderer", "videoRenderer", "gridVideoRenderer"}) {
            JSONObject video = object.optJSONObject(name);
            if (video != null) {
                String id = video.optString("videoId");
                int seconds = duration(text(video.optJSONObject("lengthText")));
                if (MorningEpisodePolicy.validId(id) && seconds > MorningEpisodePolicy.MIN_DURATION_SECONDS
                        && seconds <= MorningEpisodePolicy.MAX_DURATION_SECONDS && !videos.containsKey(id))
                    videos.put(id, seconds);
                return;
            }
        }
        JSONObject continuationItem = object.optJSONObject("continuationItemRenderer");
        if (continuationItem != null) {
            JSONObject endpoint = continuationItem.optJSONObject("continuationEndpoint");
            JSONObject command = endpoint == null ? null : endpoint.optJSONObject("continuationCommand");
            if (command != null) setContinuation(command.optString("token"));
            return;
        }
        JSONObject viewModel = object.optJSONObject("continuationItemViewModel");
        if (viewModel != null) {
            JSONObject command = viewModel.optJSONObject("continuationCommand");
            command = command == null ? null : command.optJSONObject("innertubeCommand");
            command = command == null ? null : command.optJSONObject("continuationCommand");
            if (command != null) setContinuation(command.optString("token"));
            return;
        }
        JSONObject next = object.optJSONObject("nextContinuationData");
        if (next != null) setContinuation(next.optString("continuation"));
        Iterator<String> keys = object.keys();
        while (keys.hasNext()) {
            String key = keys.next();
            if (!"continuationEndpoint".equals(key) && !"navigationEndpoint".equals(key)
                    && !"menu".equals(key) && !"trackingParams".equals(key)) collect(object.opt(key), depth + 1);
        }
    }

    private void setContinuation(String value) {
        if (continuation == null && value != null && !value.isEmpty() && value.length() <= MAX_TOKEN_CHARS)
            continuation = value;
    }

    static int duration(String value) {
        if (value == null || !value.matches("[0-9]{1,2}:[0-9]{2}(:[0-9]{2})?")) return 0;
        String[] parts = value.split(":");
        int first = Integer.parseInt(parts[0]), second = Integer.parseInt(parts[1]);
        if (second >= 60) return 0;
        if (parts.length == 2) return first * 60 + second;
        int third = Integer.parseInt(parts[2]);
        if (third >= 60) return 0;
        return first * 3600 + second * 60 + third;
    }

    static String text(JSONObject node) {
        if (node == null) return "";
        String simple = node.optString("simpleText");
        if (!simple.isEmpty()) return simple;
        JSONArray runs = node.optJSONArray("runs");
        StringBuilder out = new StringBuilder();
        if (runs != null) for (int i = 0; i < runs.length(); i++) {
            JSONObject run = runs.optJSONObject(i);
            if (run != null) out.append(run.optString("text"));
        }
        return out.toString();
    }

    private static boolean wrongSort(Object value, int depth) {
        if (depth > MAX_DEPTH || value == null) return false;
        if (value instanceof JSONArray) {
            JSONArray array = (JSONArray) value;
            for (int i = 0; i < array.length(); i++) if (wrongSort(array.opt(i), depth + 1)) return true;
        } else if (value instanceof JSONObject) {
            JSONObject object = (JSONObject) value;
            JSONObject chip = object.optJSONObject("chipViewModel");
            if (chip != null && chip.optBoolean("selected")
                    && ("Popular".equals(chip.optString("text")) || "Oldest".equals(chip.optString("text")))) return true;
            chip = object.optJSONObject("chipCloudChipRenderer");
            if (chip != null && chip.optBoolean("isSelected")) {
                String label = text(chip.optJSONObject("text"));
                if ("Popular".equals(label) || "Oldest".equals(label)) return true;
            }
            Iterator<String> keys = object.keys();
            while (keys.hasNext()) if (wrongSort(object.opt(keys.next()), depth + 1)) return true;
        }
        return false;
    }

    private static boolean hasLatestSort(Object value, int depth) {
        if (depth > MAX_DEPTH || value == null) return false;
        if (value instanceof JSONArray) {
            JSONArray array = (JSONArray) value;
            for (int i = 0; i < array.length(); i++) if (hasLatestSort(array.opt(i), depth + 1)) return true;
        } else if (value instanceof JSONObject) {
            JSONObject object = (JSONObject) value;
            JSONObject chip = object.optJSONObject("chipViewModel");
            if (chip != null && chip.optBoolean("selected") && "Latest".equals(chip.optString("text"))) return true;
            chip = object.optJSONObject("chipCloudChipRenderer");
            if (chip != null && chip.optBoolean("isSelected") && "Latest".equals(text(chip.optJSONObject("text")))) return true;
            Iterator<String> keys = object.keys();
            while (keys.hasNext()) if (hasLatestSort(object.opt(keys.next()), depth + 1)) return true;
        }
        return false;
    }

    static String initialJson(String html) throws Exception {
        int start = html.indexOf("var ytInitialData =");
        if (start >= 0) start += "var ytInitialData =".length();
        else {
            start = html.indexOf("window[\"ytInitialData\"] =");
            if (start >= 0) start += "window[\"ytInitialData\"] =".length();
        }
        if (start < 0) throw new IllegalArgumentException("initial_data_missing");
        while (start < html.length() && Character.isWhitespace(html.charAt(start))) start++;
        if (start >= html.length()) throw new IllegalArgumentException("initial_data_empty");
        char quote = html.charAt(start);
        if (quote == '{') return balancedObject(html, start);
        if (quote != '\'' && quote != '"') throw new IllegalArgumentException("initial_data_format");
        StringBuilder result = new StringBuilder();
        for (int i = start + 1; i < html.length(); i++) {
            char c = html.charAt(i);
            if (c == quote) return result.toString();
            if (c != '\\') { result.append(c); continue; }
            if (++i >= html.length()) break;
            char escape = html.charAt(i);
            if (escape == 'x' || escape == 'u') {
                int digits = escape == 'x' ? 2 : 4;
                if (i + digits >= html.length()) break;
                result.append((char) Integer.parseInt(html.substring(i + 1, i + 1 + digits), 16));
                i += digits;
            } else if (escape == 'n') result.append('\n');
            else if (escape == 'r') result.append('\r');
            else if (escape == 't') result.append('\t');
            else if (escape == 'b') result.append('\b');
            else if (escape == 'f') result.append('\f');
            else result.append(escape);
        }
        throw new IllegalArgumentException("initial_data_unterminated");
    }

    private static String objectAfter(String input, String marker) {
        int start = input.indexOf(marker);
        if (start < 0) return null;
        start += marker.length();
        while (start < input.length() && Character.isWhitespace(input.charAt(start))) start++;
        return start < input.length() && input.charAt(start) == '{' ? balancedObject(input, start) : null;
    }

    private static String balancedObject(String input, int start) {
        int depth = 0;
        boolean quoted = false, escaped = false;
        for (int i = start; i < input.length(); i++) {
            char c = input.charAt(i);
            if (quoted) {
                if (escaped) escaped = false;
                else if (c == '\\') escaped = true;
                else if (c == '"') quoted = false;
            } else if (c == '"') quoted = true;
            else if (c == '{') depth++;
            else if (c == '}' && --depth == 0) return input.substring(start, i + 1);
        }
        throw new IllegalArgumentException("unterminated_json_object");
    }
}
