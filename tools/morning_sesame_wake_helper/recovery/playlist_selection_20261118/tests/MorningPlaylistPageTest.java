package dev.yossi.morningsesame;
import java.util.ArrayList;
import java.util.Arrays;
import org.json.JSONArray;
import org.json.JSONObject;

public final class MorningPlaylistPageTest {
    static int checks;
    static void check(boolean yes,String why){checks++;if(!yes)throw new AssertionError(why);}
    interface Checked {void run()throws Exception;}
    static void rejected(Checked task,String why)throws Exception{
        try{task.run();throw new AssertionError(why);}catch(IllegalArgumentException expected){checks++;}
    }
    public static void main(String[]args)throws Exception{
        String html=TestPages.playlist("page2",TestPages.B,TestPages.A);
        MorningPlaylistPage page=MorningPlaylistPage.initial(html);
        check(new ArrayList<>(page.videos.keySet()).equals(Arrays.asList(TestPages.B,TestPages.A)),"playlist membership order retained separately");
        check(page.videos.get(TestPages.A)==3580,"modern thumbnail duration parsed");
        check("page2".equals(page.continuation),"modern continuationItemViewModel recognized");
        check("MWEB".equals(page.client.getString("clientName")),"public client restricted to parsed identity");
        String foreign=html.replace("\"playlistId\":\""+MorningYouTubeHistory.PLAYLIST_ID+"\"","\"playlistId\":\"other\"");
        rejected(()->MorningPlaylistPage.initial(foreign),"foreign watch endpoint cannot establish membership");
        rejected(()->MorningPlaylistPage.initial(html.replace(MorningPlaylistPage.URL,"https://www.youtube.com/playlist?list=other")),"wrong canonical playlist");
        String nested=html.replace("\"contentId\":\""+TestPages.A+"\"","\"contentId\":\""+TestPages.C+"\"");
        check(!MorningPlaylistPage.initial(nested).videos.containsKey(TestPages.C),"mismatched direct endpoint cannot introduce ID");
        MorningPlaylistPage next=MorningPlaylistPage.continuation(TestPages.playlistNext(null,TestPages.C));
        check(next.videos.containsKey(TestPages.C)&&next.continuation==null,"terminal continuation parsed");
        String wrong=TestPages.playlistNext(null,TestPages.C).replace("\"targetId\":\""+MorningYouTubeHistory.PLAYLIST_ID+"\"","\"targetId\":\"other\"");
        rejected(()->MorningPlaylistPage.continuation(wrong),"wrong continuation target rejected");
        rejected(()->MorningPlaylistPage.continuation("{\"recommendations\":["+TestPages.lockup(TestPages.C,MorningYouTubeHistory.PLAYLIST_ID,"59:00")+"]}"),"recommendations not continuation");
        JSONObject legacy=new JSONObject().put("playlistVideoRenderer",new JSONObject().put("videoId",TestPages.C)
                .put("isPlayable",true).put("lengthText",new JSONObject().put("simpleText","1:02:03")));
        JSONObject response=new JSONObject().put("continuationContents",new JSONObject().put("playlistVideoListContinuation",
                new JSONObject().put("contents",new JSONArray().put(legacy))));
        check(MorningPlaylistPage.continuation(response.toString()).videos.get(TestPages.C)==3723,"legacy playlist renderer retained");
        legacy.getJSONObject("playlistVideoRenderer").put("isPlayable",false);
        check(MorningPlaylistPage.continuation(response.toString()).videos.isEmpty(),"unavailable playlist item skipped");
        JSONObject ambiguous=new JSONObject(TestPages.playlistNext("one",TestPages.A));
        ambiguous.getJSONArray("onResponseReceivedActions").getJSONObject(0).getJSONObject("appendContinuationItemsAction")
                .getJSONArray("continuationItems").put(TestPages.continuation("two"));
        rejected(()->MorningPlaylistPage.continuation(ambiguous.toString()),"ambiguous continuation fails closed");
        String channel=TestPages.channel("channel2",TestPages.A,TestPages.B);
        MorningChannelPage latest=MorningChannelPage.initial(channel);
        check("channel2".equals(latest.continuation),"Latest source recognizes modern continuation");
        rejected(()->MorningChannelPage.initial(channel.replace("Latest","Popular")),"Popular cannot masquerade as upload order");
        rejected(()->MorningChannelPage.initial(channel.replace("Latest","Other")),"unknown sort fails upload-order proof");
        rejected(()->MorningChannelPage.initial(channel.replace(MorningChannelPage.CHANNEL_ID,"other-channel")),"channel identity pinned");
        System.out.println("MorningPlaylistPage: "+checks+" renderer and scope assertions passed");
    }
}
