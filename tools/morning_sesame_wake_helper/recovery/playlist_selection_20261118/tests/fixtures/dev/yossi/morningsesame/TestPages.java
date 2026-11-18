package dev.yossi.morningsesame;

import org.json.JSONArray;
import org.json.JSONObject;

/** Synthetic response shapes taken from the public WEB/MWEB renderers; no account data. */
final class TestPages {
    static final String A="aaaaaaaaaaa", B="bbbbbbbbbbb", C="ccccccccccc";
    static JSONObject history(long timestamp, Object... pairs) throws Exception {
        JSONObject progress=new JSONObject();JSONArray ids=new JSONArray();
        for(int i=0;i<pairs.length;i+=2){ids.put(pairs[i]);progress.put((String)pairs[i],pairs[i+1]);}
        return new JSONObject().put("__youtube_history",new JSONObject().put("schema",1)
                .put("source","youtube_playlist").put("playlist_id",MorningYouTubeHistory.PLAYLIST_ID)
                .put("captured_at_ms",timestamp).put("signed_in",true).put("complete",true)
                .put("covered_ids",ids).put("progress",progress));
    }
    static JSONObject lockup(String id,String playlist,String time) throws Exception {
        return new JSONObject().put("lockupViewModel",new JSONObject().put("contentId",id)
                .put("contentType","LOCKUP_CONTENT_TYPE_VIDEO")
                .put("rendererContext",new JSONObject().put("commandContext",new JSONObject().put("onTap",
                        new JSONObject().put("innertubeCommand",new JSONObject().put("watchEndpoint",
                                new JSONObject().put("videoId",id).put("playlistId",playlist))))))
                .put("contentImage",new JSONObject().put("thumbnailViewModel",new JSONObject().put("overlays",
                        new JSONArray().put(new JSONObject().put("thumbnailBottomOverlayViewModel",new JSONObject().put("badges",
                                new JSONArray().put(new JSONObject().put("thumbnailBadgeViewModel",new JSONObject().put("text",time))))))))));
    }
    static JSONObject continuation(String token) throws Exception {
        return new JSONObject().put("continuationItemViewModel",new JSONObject().put("continuationCommand",
                new JSONObject().put("innertubeCommand",new JSONObject().put("continuationCommand",new JSONObject().put("token",token)))));
    }
    static String playlist(String token,String...ids)throws Exception{
        JSONArray cards=new JSONArray();for(String id:ids)cards.put(lockup(id,MorningYouTubeHistory.PLAYLIST_ID,"59:40"));
        JSONArray sections=new JSONArray().put(new JSONObject().put("itemSectionRenderer",new JSONObject()
                .put("targetId",MorningYouTubeHistory.PLAYLIST_ID).put("contents",cards)));
        if(token!=null)sections.put(continuation(token));
        JSONObject data=new JSONObject().put("microformat",new JSONObject().put("microformatDataRenderer",new JSONObject()
                .put("urlCanonical",MorningPlaylistPage.URL)))
                .put("contents",new JSONObject().put("singleColumnBrowseResultsRenderer",new JSONObject().put("tabs",
                        new JSONArray().put(new JSONObject().put("tabRenderer",new JSONObject().put("selected",true)
                                .put("content",new JSONObject().put("sectionListRenderer",new JSONObject().put("contents",sections))))))));
        return html(data);
    }
    static String playlistNext(String token,String...ids)throws Exception{
        JSONArray cards=new JSONArray();for(String id:ids)cards.put(lockup(id,MorningYouTubeHistory.PLAYLIST_ID,"59:40"));
        if(token!=null)cards.put(continuation(token));
        return new JSONObject().put("onResponseReceivedActions",new JSONArray().put(new JSONObject()
                .put("appendContinuationItemsAction",new JSONObject().put("targetId",MorningYouTubeHistory.PLAYLIST_ID)
                        .put("continuationItems",cards)))).toString();
    }
    static String channel(String token,String...ids)throws Exception{
        JSONArray cards=new JSONArray();for(String id:ids)cards.put(video(id));if(token!=null)cards.put(continuation(token));
        JSONObject data=new JSONObject().put("metadata",new JSONObject().put("channelMetadataRenderer",
                new JSONObject().put("externalId",MorningChannelPage.CHANNEL_ID)))
                .put("sort",new JSONObject().put("chipViewModel",new JSONObject().put("text","Latest").put("selected",true)))
                .put("contents",new JSONObject().put("singleColumnBrowseResultsRenderer",new JSONObject().put("tabs",
                        new JSONArray().put(new JSONObject().put("tabRenderer",new JSONObject().put("selected",true).put("title","Videos")
                                .put("content",new JSONObject().put("sectionListRenderer",new JSONObject().put("contents",cards))))))));
        return html(data);
    }
    static String channelNext(String token,String...ids)throws Exception{
        JSONArray cards=new JSONArray();for(String id:ids)cards.put(video(id));if(token!=null)cards.put(continuation(token));
        return new JSONObject().put("onResponseReceivedActions",new JSONArray().put(new JSONObject()
                .put("appendContinuationItemsAction",new JSONObject().put("continuationItems",cards)))).toString();
    }
    static JSONObject video(String id)throws Exception{
        return new JSONObject().put("videoWithContextRenderer",new JSONObject().put("videoId",id)
                .put("lengthText",new JSONObject().put("simpleText","59:40")));
    }
    static String html(JSONObject data)throws Exception{
        return "<script>ytcfg.set({\"INNERTUBE_CONTEXT\":{\"client\":{\"clientName\":\"MWEB\",\"clientVersion\":\"2.20261118\"}}});</script>"
                +"<script>var ytInitialData = "+data.toString()+";</script>";
    }
}
