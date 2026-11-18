package dev.yossi.morningsesame;
import android.content.Context;
import java.util.LinkedHashMap;
import java.util.Map;
final class AlarmReceiver {
  static final LinkedHashMap<String,Integer> durations=new LinkedHashMap<>();
  static int routed,scheduled;
  static String routedRun,routedVideo;
  static Map<String,Integer> parseDurations(String ignored){return new LinkedHashMap<>(durations);}
  static void routePlayback(Context context,String run,String video){routed++;routedRun=run;routedVideo=video;}
  static void schedulePlay(Context context,String run,String video){scheduled++;}
  static void reset(){durations.clear();routed=scheduled=0;routedRun=routedVideo=null;}
}
