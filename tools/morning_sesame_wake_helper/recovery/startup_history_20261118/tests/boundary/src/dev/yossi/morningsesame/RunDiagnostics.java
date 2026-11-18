package dev.yossi.morningsesame;
import android.content.Context;
import java.util.LinkedHashMap;
import java.util.Map;
final class RunDiagnostics {
  static String lastReason;
  static Map<String,Long> lastValues;
  static Map<String,Long> values(Object... pairs){Map<String,Long> out=new LinkedHashMap<>();for(int i=0;i+1<pairs.length;i+=2)out.put((String)pairs[i],((Number)pairs[i+1]).longValue());return out;}
  static void event(Context c,String run,String phase,String reason,Map<String,Long> values){lastReason=reason;lastValues=values;}
}
