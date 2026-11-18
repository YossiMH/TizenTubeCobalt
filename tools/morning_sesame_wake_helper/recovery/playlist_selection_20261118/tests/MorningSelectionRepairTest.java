package dev.yossi.morningsesame;
import android.content.Context;
import android.content.SharedPreferences;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import org.json.JSONObject;

public final class MorningSelectionRepairTest {
    private static int checks;
    private static final String A=TestPages.A,B=TestPages.B;
    private static void check(boolean yes,String why){checks++;if(!yes)throw new AssertionError(why);}
    private static final class Prefs implements SharedPreferences,SharedPreferences.Editor {
        final Map<String,Object> values=new HashMap<>();
        public String getString(String k,String fallback){Object v=values.get(k);return v instanceof String?(String)v:fallback;}
        @SuppressWarnings("unchecked") public Set<String> getStringSet(String k,Set<String> fallback){Object v=values.get(k);return v instanceof Set?new HashSet<>((Set<String>)v):fallback;}
        public SharedPreferences.Editor edit(){return this;}
        public SharedPreferences.Editor putString(String k,String v){values.put(k,v);return this;}
        public SharedPreferences.Editor putStringSet(String k,Set<String> v){values.put(k,new HashSet<>(v));return this;}
        public boolean commit(){return true;}
    }
    private static void reset(){RunCoordinator.reset();AlarmReceiver.routes=0;AlarmReceiver.checks=0;RunDiagnostics.result=null;}
    private static JSONObject history(Object...pairs)throws Exception{return TestPages.history(System.currentTimeMillis(),pairs);}
    public static void main(String[]args)throws Exception{
        Prefs p=new Prefs();Context context=new Context(p);p.putString("candidate_list",A+",3600;"+B+",3590");
        reset();MorningSelectionRepair.selectAndVerify(context,"run",history(A,100,B,50));
        check(B.equals(RunCoordinator.selected),"newest100 watched excluded;50 boundary accepted");
        check(AlarmReceiver.routes==1&&AlarmReceiver.checks==1,"original playback and confirmation route retained");
        p.putStringSet("played_ids",new HashSet<>(Arrays.asList(A,B)));
        p.putString("selection_confirmed_starts_v1",A+","+System.currentTimeMillis()+";"+B+","+System.currentTimeMillis());
        reset();MorningSelectionRepair.selectAndVerify(context,"following-run",history(A,10,B,0)
                .put("__local_observed",new JSONObject().put(A,100).put(B,100)).put(A,100));
        check(A.equals(RunCoordinator.selected),"current10 account qualifies despite recent-start and local100");
        Map<String,Object> before=new HashMap<>(p.values);MorningSelectionRepair.recordConfirmedStart(context,A);
        check(before.equals(p.values),"confirmation hook does not fabricate watch progress or new history");
        reset();MorningSelectionRepair.selectAndVerify(context,"next-morning",history(A,100,B,100)
                .put("__local_observed",new JSONObject().put(A,0).put(B,0)));
        check("no_eligible_candidate".equals(RunCoordinator.reason),"all watched produces explicit exhausted result");
        check(AlarmReceiver.routes==0&&AlarmReceiver.checks==0,"no longest/history cycling fallback");
        reset();MorningSelectionRepair.selectAndVerify(context,"history-reset",history(A,0,B,100));
        check(A.equals(RunCoordinator.selected),"actual account-history reset can restore eligibility");
        reset();MorningSelectionRepair.selectAndVerify(context,"partial",history(B,100));
        check(RunCoordinator.selected==null,"uncovered first ID cannot qualify from stale local0");
        reset();JSONObject incomplete=history(A,0);incomplete.getJSONObject("__youtube_history").put("complete",false);
        MorningSelectionRepair.selectAndVerify(context,"incomplete",incomplete);
        check("account_selection_failed".equals(RunCoordinator.reason),"partial snapshot fails account selection");
        check(AlarmReceiver.routes==0,"failed history never launches");
        reset();MorningSelectionRepair.selectAndVerify(context,"legacy",new JSONObject().put(A,0));
        check("account_selection_failed".equals(RunCoordinator.reason),"legacy bare map rejected");
        reset();RunCoordinator.active=false;MorningSelectionRepair.selectAndVerify(context,"cancelled",history(A,0));
        check(AlarmReceiver.routes==0&&RunDiagnostics.result==null,"obsolete run exits before selection");
        reset();RunCoordinator.pending=false;MorningSelectionRepair.selectAndVerify(context,"cancelled-during-persist",history(A,0));
        check(AlarmReceiver.routes==0&&AlarmReceiver.checks==0,"current-run persistence fence preserves phone precedence");
        System.out.println("MorningSelectionRepair: "+checks+" adapter assertions passed");
    }
}
