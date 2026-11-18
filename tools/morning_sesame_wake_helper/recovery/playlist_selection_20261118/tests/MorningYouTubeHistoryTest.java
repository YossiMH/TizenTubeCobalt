package dev.yossi.morningsesame;

import java.util.LinkedHashMap;
import java.util.Map;
import org.json.JSONArray;
import org.json.JSONObject;

public final class MorningYouTubeHistoryTest {
    static int checks;
    static final long NOW=1805580000000L;
    static void check(boolean yes,String why){checks++;if(!yes)throw new AssertionError(why);}
    static void rejected(JSONObject envelope,String why)throws Exception{
        try{MorningYouTubeHistory.parse(envelope,NOW);throw new AssertionError(why);}
        catch(IllegalArgumentException expected){checks++;}
    }
    static JSONObject fresh()throws Exception{return TestPages.history(NOW,TestPages.A,49,TestPages.B,100);}
    public static void main(String[]args)throws Exception{
        Map<String,Integer> candidates=new LinkedHashMap<>();candidates.put(TestPages.A,2401);candidates.put(TestPages.B,3599);
        Map<String,Double> history=MorningYouTubeHistory.parse(fresh(),NOW);
        check(TestPages.A.equals(MorningEpisodePolicy.select(candidates,history)),"49% watched qualifies by upload order");
        candidates.put(TestPages.A,2400);
        check(MorningEpisodePolicy.select(candidates,history)==null,"exact40 minutes excluded and100 watched cannot fallback");
        candidates.put(TestPages.A,21600);
        history=MorningYouTubeHistory.parse(TestPages.history(NOW,TestPages.A,50,TestPages.B,0),NOW);
        check(TestPages.A.equals(MorningEpisodePolicy.select(candidates,history)),"exact50 watched and6hours are accepted");
        candidates.put(TestPages.A,21601);
        check(TestPages.B.equals(MorningEpisodePolicy.select(candidates,history)),"session protection ceiling enforced");
        candidates.put(TestPages.A,3600);
        history=MorningYouTubeHistory.parse(TestPages.history(NOW,TestPages.A,50.00001,TestPages.B,0),NOW);
        check(TestPages.B.equals(MorningEpisodePolicy.select(candidates,history)),"any amount over50 excluded");
        history=MorningYouTubeHistory.parse(TestPages.history(NOW,TestPages.B,0),NOW);
        check(TestPages.B.equals(MorningEpisodePolicy.select(candidates,history)),"uncovered newest ID is unknown rather than unwatched");
        history=MorningYouTubeHistory.parse(TestPages.history(NOW-300000,TestPages.A,10),NOW);
        check(TestPages.A.equals(MorningEpisodePolicy.select(candidates,history)),"five-minute boundary inclusive and partial10 accepted");
        rejected(TestPages.history(NOW-300001,TestPages.A,0),"stale snapshot");
        rejected(TestPages.history(NOW+1,TestPages.A,0),"future snapshot");
        rejected(null,"missing metadata");rejected(new JSONObject().put(TestPages.A,0),"legacy numeric map cannot qualify");
        for(String key:new String[]{"signed_in","complete"}){
            JSONObject bad=fresh();bad.getJSONObject("__youtube_history").put(key,false);rejected(bad,key+"false");
            bad=fresh();bad.getJSONObject("__youtube_history").put(key,"true");rejected(bad,key+"string");
        }
        for(Object schema:new Object[]{2,"1"}){JSONObject bad=fresh();bad.getJSONObject("__youtube_history").put("schema",schema);rejected(bad,"schema");}
        for(String key:new String[]{"playlist_id","source"}){JSONObject bad=fresh();bad.getJSONObject("__youtube_history").put(key,"other");rejected(bad,key);}
        JSONObject bad=fresh();bad.getJSONObject("__youtube_history").put("captured_at_ms",""+NOW);rejected(bad,"string time");
        bad=fresh();bad.getJSONObject("__youtube_history").put("covered_ids",new JSONArray().put(TestPages.A).put(TestPages.A));rejected(bad,"duplicate coverage");
        bad=fresh();bad.getJSONObject("__youtube_history").getJSONObject("progress").remove(TestPages.B);rejected(bad,"missing progress cannot become0");
        for(Object percent:new Object[]{-1,101,"0",JSONObject.NULL}){
            bad=fresh();bad.getJSONObject("__youtube_history").getJSONObject("progress").put(TestPages.A,percent);rejected(bad,"invalid progress");
        }
        bad=fresh();bad.getJSONObject("__youtube_history").put("covered_ids",new JSONArray().put(TestPages.A).put("__metadata_"));rejected(bad,"metadata is not video ID");
        bad=TestPages.history(NOW);rejected(bad,"empty snapshot is not evidence");
        JSONObject independent=fresh().put("__local_observed",new JSONObject().put(TestPages.A,100)).put(TestPages.A,100);
        check(TestPages.A.equals(MorningEpisodePolicy.select(candidates,MorningYouTubeHistory.parse(independent,NOW))),"outer local and merged values ignored");
        history=MorningYouTubeHistory.parse(TestPages.history(NOW,TestPages.A,0),NOW);
        check(TestPages.A.equals(MorningEpisodePolicy.select(candidates,history)),"current account reset accepted without historical maximum");
        System.out.println("MorningYouTubeHistory: "+checks+" policy and snapshot assertions passed");
    }
}
