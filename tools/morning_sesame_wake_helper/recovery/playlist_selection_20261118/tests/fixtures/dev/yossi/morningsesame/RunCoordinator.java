package dev.yossi.morningsesame;
import android.content.Context;
final class RunCoordinator {
    static boolean active=true, pending=true;
    static String selected, reason, outcome;
    static int wakeChecks;
    static boolean checkpoint(Context c,String r) {return active;}
    static boolean startupWorkAllowed(Context c,String r) {return active;}
    static boolean keepAwake(Context c,String r) {wakeChecks++;return active;}
    static long remaining(Context c,String r) {return 300000L;}
    static boolean storePending(Context c,String r,String v) {if(active&&pending){selected=v;return true;}return false;}
    static boolean note(Context c,String r,String p,String v,int a) {return active;}
    static void finish(Context c,String r,String o,String why) {reason=why;outcome=o;active=false;}
    static void reset() {active=true;pending=true;selected=null;reason=null;outcome=null;wakeChecks=0;}
}
