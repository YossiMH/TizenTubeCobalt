package dev.yossi.morningsesame;
import android.content.Context;
import android.content.SharedPreferences;
final class RunCoordinator {
  static String runId,selected,outcome,reason;
  static boolean active,pendingAllowed,startupBound;
  static long deadline,remaining;
  static int stored,confirmed;
  static void reset(String run,long end){runId=run;deadline=end;remaining=300000;active=true;pendingAllowed=true;startupBound=true;stored=confirmed=0;selected=outcome=reason=null;}
  static boolean checkpoint(Context c,String run){return active&&runId.equals(run);}
  static long remaining(Context c,String run){return checkpoint(c,run)?remaining:0;}
  static long startupDeadlineWall(Context c,String run){return deadline;}
  static boolean isStartupBound(Context c,String run){return startupBound;}
  static SharedPreferences prefs(Context c){return c.getSharedPreferences("morning_sesame",0);}
  static boolean storePending(Context c,String run,String video){if(!checkpoint(c,run)||!pendingAllowed)return false;selected=video;stored++;return true;}
  static boolean note(Context c,String run,String phase,String video,int attempt){return checkpoint(c,run);}
  static boolean isActive(Context c,String run){return checkpoint(c,run);}
  static void finish(Context c,String run,String result,String why){if(checkpoint(c,run)){active=false;outcome=result;reason=why;}}
  static void confirmPlayed(Context c,String run,String video){if(checkpoint(c,run))confirmed++;}
}
