package dev.yossi.morningsesame;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.ArrayList;
import org.json.JSONArray;
import org.json.JSONObject;

public final class MorningChannelPageTest {
    private static int checks;
    private static void check(boolean value, String message) {
        checks++;
        if (!value) throw new AssertionError(message);
    }
    private static void reject(String html, String expected) throws Exception {
        try { MorningChannelPage.initial(html); throw new AssertionError("expected " + expected); }
        catch (IllegalArgumentException actual) { check(expected.equals(actual.getMessage()), "specific rejection: " + actual); }
    }
    private static JSONObject video(String id, String duration) throws Exception {
        return new JSONObject().put("videoRenderer", new JSONObject().put("videoId",id)
                .put("lengthText",new JSONObject().put("simpleText",duration)));
    }
    private static String synthetic(JSONArray videos) throws Exception {
        JSONObject shelf = new JSONObject().put("richGridRenderer",new JSONObject().put("contents",videos));
        JSONObject tab = new JSONObject().put("tabRenderer",new JSONObject().put("selected",true)
                .put("title","Videos").put("content",shelf));
        JSONObject root = new JSONObject().put("metadata",new JSONObject().put("channelMetadataRenderer",
                new JSONObject().put("externalId",MorningChannelPage.CHANNEL_ID)))
                .put("contents",new JSONObject().put("twoColumnBrowseResultsRenderer",
                        new JSONObject().put("tabs",new JSONArray().put(tab))));
        return "var ytInitialData = " + root + ";";
    }
    public static void main(String[] args) throws Exception {
        String html = new String(Files.readAllBytes(Paths.get(args[0])),StandardCharsets.UTF_8);
        String next = new String(Files.readAllBytes(Paths.get(args[1])),StandardCharsets.UTF_8);
        MorningChannelPage initial = MorningChannelPage.initial(html), older = MorningChannelPage.continuation(next);
        check(initial.videos.keySet().iterator().next().equals("vGPvcQCDVGY"), "real latest upload remains first");
        check(initial.videos.get("vGPvcQCDVGY")==3580, "real 59:40 duration parsed");
        check(initial.videos.size()==21, "real initial page filters nine shorts");
        check(!initial.videos.containsKey("lttiNzPqpos"), "real short excluded");
        check(older.videos.keySet().iterator().next().equals("4K3e1SV9m8g"), "real continuation ordering preserved");
        check(older.videos.size()>10, "older continuation expands the finite initial pool");
        check("fixture-next-token".equals(initial.continuation), "selected-shelf continuation found");
        check("MWEB".equals(initial.client.getString("clientName")), "actual dynamic client configuration retained");
        check(initial.client.length()==4, "request context excludes unrelated visitor metadata");
        JSONArray items=new JSONArray().put(video("aaaaaaaaaaa","40:00"))
                .put(video("bbbbbbbbbbb","40:01")).put(video("ccccccccccc","2:05:00"))
                .put(video("bbbbbbbbbbb","59:00")).put(video("ddddddddddd","1:65"));
        MorningChannelPage sample=MorningChannelPage.initial(synthetic(items));
        check(sample.videos.size()==2,"strict duration boundary, duplicate and malformed duration handling");
        check(new ArrayList<>(sample.videos.keySet()).get(0).equals("bbbbbbbbbbb"),"desktop videos preserve chronological array order");
        check(sample.videos.get("bbbbbbbbbbb")==2401,"duplicate does not replace original duration");
        check(sample.videos.get("ccccccccccc")==7500,"hour duration parsed");
        reject(synthetic(items).replace(MorningChannelPage.CHANNEL_ID,"UC_WRONG"),"unexpected_channel");
        reject(synthetic(items).replace("\"selected\":true","\"selected\":false"),"selected_videos_shelf_missing");
        String wrong=synthetic(items).replace("var ytInitialData = {","var ytInitialData = {\"sort\":{\"chipViewModel\":{\"text\":\"Popular\",\"selected\":true}},");
        reject(wrong,"channel_not_newest_first");
        check(MorningChannelPage.continuation("{\"recommendations\":"+items+"}").videos.isEmpty(),"unrelated continuation recommendations ignored");
        reject("<html>consent</html>","initial_data_missing");
        System.out.println("MorningChannelPage: "+checks+" assertions passed against public Nov 17 source fixtures and edge cases");
    }
}
