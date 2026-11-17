package dev.yossi.morningsesame;
import android.content.Context;
import android.content.SharedPreferences;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import org.json.JSONObject;

public final class MorningSelectionRepairTest {
    private static int checks;
    private static final String A="aaaaaaaaaaa",B="bbbbbbbbbbb";
    private static void check(boolean value,String message) {checks++;if(!value)throw new AssertionError(message);}
    private static final class Prefs implements SharedPreferences, SharedPreferences.Editor {
        final Map<String,Object> values=new HashMap<>();
        public String getString(String key,String fallback){Object value=values.get(key);return value instanceof String?(String)value:fallback;}
        @SuppressWarnings("unchecked") public Set<String> getStringSet(String key,Set<String> fallback){Object value=values.get(key);return value instanceof Set?new HashSet<>((Set<String>)value):fallback;}
        public SharedPreferences.Editor edit(){return this;}
        public SharedPreferences.Editor putString(String key,String value){values.put(key,value);return this;}
        public SharedPreferences.Editor putStringSet(String key,Set<String> value){values.put(key,new HashSet<>(value));return this;}
        public boolean commit(){return true;}
    }
    private static void reset(){RunCoordinator.reset();AlarmReceiver.routes=0;AlarmReceiver.checks=0;RunDiagnostics.result=null;}
    public static void main(String[] args)throws Exception {
        Prefs p=new Prefs();Context c=new Context(p);
        p.putString("candidate_list",A+",3600;"+B+",5400").commit();reset();
        MorningSelectionRepair.selectAndVerify(c,"run",new JSONObject().put(A,90).put(B,25));
        check(B.equals(RunCoordinator.selected),"known watched first candidate cannot enter fallback");
        check(AlarmReceiver.routes==1&&AlarmReceiver.checks==1,"eligible request uses original route and confirmation");
        check(!p.values.containsKey("selection_confirmed_starts_v1"),"selection itself does not fabricate a confirmed start");
        p.putStringSet("played_ids",new HashSet<>(java.util.Arrays.asList(A,B))).commit();reset();
        MorningSelectionRepair.selectAndVerify(c,"run",new JSONObject().put(A,0).put(B,0));
        check("no_eligible_candidate".equals(RunCoordinator.reason),"stale account zero cannot resurrect legacy starts");
        check(AlarmReceiver.routes==0&&AlarmReceiver.checks==0,"exhaustion schedules no repeating playback");
        reset();JSONObject observed=new JSONObject().put(A,20).put(B,100);
        MorningSelectionRepair.selectAndVerify(c,"run",new JSONObject().put(A,20).put(B,100).put("__local_observed",observed));
        check(A.equals(RunCoordinator.selected),"fresh actual small interruption can qualify a legacy-started episode");
        MorningSelectionRepair.recordConfirmedStart(c,A);
        Map<String,Long> ledger=MorningStartHistory.decode(p.getString("selection_confirmed_starts_v1",""));
        check(ledger.containsKey(B),"confirmed-start migration retains legacy receipt before old250 reset");
        check(ledger.get(A)>0L&&ledger.get(B)==0L,"confirmed timestamp is separate from unknown legacy time");
        p.putStringSet("played_ids",Collections.emptySet()).commit();reset();
        MorningSelectionRepair.selectAndVerify(c,"run",new JSONObject().put(A,0).put(B,0).put("__local_observed",observed));
        check(RunCoordinator.selected==null,"receipt survives legacy reset and blocks immediate replay");
        reset();RunCoordinator.active=false;
        MorningSelectionRepair.selectAndVerify(c,"obsolete",new JSONObject());
        check(AlarmReceiver.routes==0&&RunDiagnostics.result==null,"cancelled run does no selection work");
        p.values.clear();p.putString("candidate_list",A+",3600").commit();reset();RunCoordinator.pending=false;
        MorningSelectionRepair.selectAndVerify(c,"cancelled-during-persist",new JSONObject().put(A,0));
        check(AlarmReceiver.routes==0&&AlarmReceiver.checks==0,"failed current-run commit never routes playback");
        System.out.println("MorningSelectionRepair: "+checks+" integration assertions passed");
    }
}
