package dev.yossi.morningsesame;
import android.content.Context;
import java.util.LinkedHashMap;
import java.util.Map;
final class AlarmReceiver {
    static int routes,checks;
    static Map<String,Integer> parseDurations(String encoded) {
        Map<String,Integer> result=new LinkedHashMap<>();
        if(encoded==null)return result;
        for(String row:encoded.split(";")){
            int comma=row.indexOf(',');if(comma<0)continue;
            String id=row.substring(0,comma);if(!MorningEpisodePolicy.validId(id))continue;
            try {int seconds=Integer.parseInt(row.substring(comma+1));if(seconds>0&&seconds<=86400)result.put(id,seconds);}
            catch(NumberFormatException ignored){}
        }
        return result;
    }
    static void routePlayback(Context c,String r,String v) {if(RunCoordinator.active)routes++;}
    static void schedulePlay(Context c,String r,String v) {if(RunCoordinator.active)checks++;}
}
