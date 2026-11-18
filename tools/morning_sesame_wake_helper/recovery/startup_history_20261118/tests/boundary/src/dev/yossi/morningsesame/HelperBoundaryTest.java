package dev.yossi.morningsesame;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import org.json.JSONArray;
import org.json.JSONObject;

/** Independent boundaries: real reader/parser/selector and deployed worker continuation. */
public final class HelperBoundaryTest {
  static int checks;
  static final String A="aaaaaaaaaaa",B="bbbbbbbbbbb",C="ccccccccccc",D="ddddddddddd";
  static final String PLAYLIST="PLxKlPmCFYxy9y498dCB1tIddaxZz3OQCA";
  static final class Preferences implements SharedPreferences {
    final Map<String,Object> data=new HashMap<>();
    public long getLong(String k,long fallback){Object v=data.get(k);return v instanceof Number?((Number)v).longValue():fallback;}
    public String getString(String k,String fallback){Object v=data.get(k);return v instanceof String?(String)v:fallback;}
  }
  static final class Transport extends Context {
    final Preferences prefs=new Preferences();
    final ArrayList<String> replies=new ArrayList<>();
    final ArrayList<Intent> cancellations=new ArrayList<>();
    Intent request;
    String permission,initialData;
    Bundle options,initialExtras;
    Handler scheduler;
    BroadcastReceiver result;
    int calls,modernCalls,legacyCalls,initialCode;
    boolean noReply;
    Runnable beforeReply;
    public SharedPreferences getSharedPreferences(String name,int mode){check("morning_sesame".equals(name),"owned preferences only");return prefs;}
    public void sendOrderedBroadcast(Intent i,String p,BroadcastReceiver r,Handler h,int c,String d,Bundle e){legacyCalls++;deliver(i,p,null,r,h,c,d,e);}
    public void sendOrderedBroadcast(Intent i,String p,Bundle o,BroadcastReceiver r,Handler h,int c,String d,Bundle e){modernCalls++;deliver(i,p,o,r,h,c,d,e);}
    void deliver(Intent i,String p,Bundle o,BroadcastReceiver r,Handler h,int c,String d,Bundle e){
      calls++;request=i;permission=p;options=o;result=r;scheduler=h;initialCode=c;initialData=d;initialExtras=e;
      if(noReply)return;
      if(beforeReply!=null)beforeReply.run();
      r.setResultData(replies.isEmpty()?d:replies.remove(0));r.onReceive(this,i);
    }
    public void sendBroadcast(Intent intent){cancellations.add(intent);}
  }
  interface Change {void apply(JSONObject object)throws Exception;}
  static void check(boolean value,String why){if(!value)throw new AssertionError(why);checks++;}
  static void equal(Object actual,Object expected,String why){check(expected==null?actual==null:expected.equals(actual),why+" actual="+actual+" expected="+expected);}
  static JSONObject envelope(long capture)throws Exception{
    return new JSONObject().put("__youtube_history",new JSONObject().put("schema",1)
      .put("source","youtube_playlist").put("playlist_id",PLAYLIST)
      .put("captured_at_ms",capture).put("signed_in",true).put("complete",true)
      .put("covered_ids",new JSONArray().put(A).put(B).put(C).put(D))
      .put("progress",new JSONObject().put(A,100).put(B,49).put(C,50).put(D,50.01)));
  }
  static JSONObject reply(String run,long start,long end,long now)throws Exception{
    return new JSONObject().put("schema",1).put("ok",true).put("state","ready")
      .put("run_id",run).put("started_at_ms",start).put("deadline_ms",end)
      .put("progress",envelope(now));
  }
  static void reject(JSONObject base,String run,long start,long end,long now,Change change,String name)throws Exception{
    JSONObject modified=new JSONObject(base.toString());change.apply(modified);
    try{MorningHistoryReply.parse(modified.toString(),run,start,end,now);throw new AssertionError(name+" accepted");}
    catch(IllegalArgumentException expected){checks++;}
  }
  static Transport setup(String run)throws Exception{
    Transport t=new Transport();long now=System.currentTimeMillis();
    t.prefs.data.put("run_started_at",now-1000);RunCoordinator.reset(run,now+299000);
    Handler.reset();AlarmReceiver.reset();Looper.mainThread(false);Build.VERSION.SDK_INT=34;
    return t;
  }
  static String ready(Transport t,String run)throws Exception{
    return reply(run,t.prefs.getLong("run_started_at",-1),RunCoordinator.deadline,System.currentTimeMillis()).toString();
  }
  static void parserToSelector()throws Exception{
    long now=System.currentTimeMillis(),start=now-1000,end=start+300000;String run="boundary-run";
    JSONObject original=reply(run,start,end,now);
    JSONObject parsed=MorningHistoryReply.parse(original.toString(),run,start,end,now);
    Map<String,Double> history=MorningYouTubeHistory.parse(parsed,now);
    LinkedHashMap<String,Integer> durations=new LinkedHashMap<>();
    durations.put(A,3500);durations.put(B,3500);durations.put(C,3500);durations.put(D,3500);
    equal(MorningEpisodePolicy.select(durations,history),B,"real nested reply selects newest <=50% eligible");
    durations.remove(B);equal(MorningEpisodePolicy.select(durations,history),C,"50% boundary is included");
    durations.put(C,2400);equal(MorningEpisodePolicy.select(durations,history),null,"exact40min and >50% excluded");
    durations.put(C,2401);equal(MorningEpisodePolicy.select(durations,history),C,"strictly over40min included");
    durations.put(C,21601);equal(MorningEpisodePolicy.select(durations,history),null,"existing6h maximum retained");
    parsed.getJSONObject("__youtube_history").getJSONObject("progress").put(B,0);
    equal(original.getJSONObject("progress").getJSONObject("__youtube_history").getJSONObject("progress").getDouble(B),49.0,"returned envelope is independent");
    reject(original,run,start,end,now,x->x.put("schema","1"),"string schema");
    reject(original,run,start,end,now,x->x.put("run_id","another-run"),"wrong run");
    reject(original,run,start,end,now,x->x.put("started_at_ms",start-1),"wrong start");
    reject(original,run,start,end,now,x->x.put("deadline_ms",end+1),"changed deadline");
    reject(original,run,start,end,now,x->x.put("ok",false),"denied response");
    reject(original,run,start,end,now,x->x.put("ok","true"),"truthy nonboolean");
    for(String state:new String[]{"pending","unavailable","cancelled","unknown"})
      reject(original,run,start,end,now,x->x.put("state",state),"nonready "+state);
    reject(original,run,start,end,now,x->x.remove("progress"),"missing nested payload");
    reject(original,run,start,end,now,x->x.getJSONObject("progress").getJSONObject("__youtube_history").put("captured_at_ms",start-1),"predates run");
    reject(original,run,start,end,now,x->x.getJSONObject("progress").getJSONObject("__youtube_history").put("captured_at_ms",now+1),"future history");
    reject(original,run,start,end,now,x->x.getJSONObject("progress").getJSONObject("__youtube_history").put("captured_at_ms",now-300001),"stale history");
    reject(original,run,start,end,now,x->x.getJSONObject("progress").getJSONObject("__youtube_history").put("signed_in",false),"signed out");
    reject(original,run,start,end,now,x->x.getJSONObject("progress").getJSONObject("__youtube_history").put("complete",false),"partial pages");
    reject(original,run,start,end,now,x->x.getJSONObject("progress").getJSONObject("__youtube_history").put("playlist_id","wrong"),"wrong playlist");
    reject(original,run,start,end,now,x->x.getJSONObject("progress").getJSONObject("__youtube_history").getJSONObject("progress").put(A,101),"invalid percentage");
    reject(original,run,start,end,now,x->x.getJSONObject("progress").getJSONObject("__youtube_history").getJSONArray("covered_ids").put("zzzzzzzzzzz"),"uncovered membership");
    for(String encoded:new String[]{null,"",new String(new char[65537]).replace('\0','x')}){
      try{MorningHistoryReply.parse(encoded,run,start,end,now);throw new AssertionError("invalid size accepted");}catch(IllegalArgumentException expected){checks++;}
    }
    try{MorningHistoryReply.parse(original.toString(),run,start,end,end);throw new AssertionError("expired request accepted");}catch(IllegalArgumentException expected){checks++;}
    equal(MorningHistoryReply.reason(new Exception("credential-like opaque diagnostic")),"history_reply_invalid","unknown exceptions are sanitized");
  }
  static void orderedShim()throws Exception{
    Transport t=setup("shim");Intent intent=new Intent("fixture");Handler scheduler=new Handler(Looper.getMainLooper());Bundle extras=new Bundle();extras.put("original",true);
    BroadcastReceiver receiver=new BroadcastReceiver(){public void onReceive(Context c,Intent i){}};
    MorningOrderedBroadcast.send(t,intent,"permission",receiver,scheduler,17,"initial",extras);
    equal(t.modernCalls,1,"API34 uses options overload");equal(t.legacyCalls,0,"API34 does not use legacy overload");
    equal(t.options.get("share_identity"),true,"API34 shares actual sender identity");
    check(t.request==intent&&t.result==receiver&&t.scheduler==scheduler&&t.initialExtras==extras,"all ordered arguments preserved");
    equal(t.permission,"permission","permission preserved");equal(t.initialCode,17,"initial code preserved");equal(t.initialData,"initial","initial data preserved");
    Build.VERSION.SDK_INT=33;MorningOrderedBroadcast.send(t,intent,"permission",receiver,scheduler,17,"initial",extras);
    equal(t.legacyCalls,1,"API33 uses legacy overload");equal(t.options,null,"API33 does not fabricate options");
  }
  static void realReader()throws Exception{
    String run="actual-reader";Transport t=setup(run);t.replies.add(ready(t,run));
    JSONObject progress=MorningHistoryReader.read(t,run);
    check(progress!=null,"real reader unwraps nested ready reply");equal(t.calls,1,"successful read uses one bounded attempt");
    equal(t.request.action,"dev.yossi.onnkeyguard.MORNING_STARTUP_AUTH","signature-owned action");
    equal(t.request.packageName,"dev.yossi.onnkeyguard","explicit guard package");
    equal(t.request.className,"dev.yossi.onnkeyguard.MorningPlaybackReceiver","explicit guard receiver");
    equal(t.request.getStringExtra("command"),"history","read-only history command");
    equal(t.request.getStringExtra("run_generation"),run,"real reader binds run");
    equal(t.request.getLongExtra("run_started_wall_ms",-1),t.prefs.getLong("run_started_at",-1),"real reader binds start");
    equal(t.request.getLongExtra("run_deadline_wall_ms",-1),RunCoordinator.deadline,"real reader binds deadline");
    equal(RunDiagnostics.lastReason,"history_ready","read success diagnostic");

    t=setup(run);JSONObject pending=new JSONObject(ready(t,run)).put("state","pending");t.replies.add(pending.toString());t.replies.add(ready(t,run));
    check(MorningHistoryReader.read(t,run)!=null,"pending response is retried into real ready proof");equal(t.calls,2,"retry remains bounded and explicit");
    t=setup(run);t.replies.add(ready(t,run));t.beforeReply=()->RunCoordinator.active=false;
    equal(MorningHistoryReader.read(t,run),null,"late response after cancellation never enters selection");equal(RunDiagnostics.lastReason,"history_cancelled","late cancellation reason");
    t=setup(run);Looper.mainThread(true);
    equal(MorningHistoryReader.read(t,run),null,"main thread cannot block on ordered reply");equal(t.calls,0,"wrong thread performs no transport");Looper.mainThread(false);
    t=setup(run);RunCoordinator.active=false;
    equal(MorningHistoryReader.read(t,run),null,"cancelled run never requests history");equal(t.calls,0,"cancelled run has no transport");
    t=setup(run);RunCoordinator.remaining=30;t.noReply=true;long begin=System.nanoTime();
    equal(MorningHistoryReader.read(t,run),null,"missing ordered reply returns unavailable");
    check((System.nanoTime()-begin)/1000000<1000,"timeout honors remaining run budget");equal(t.calls,1,"no work after bounded timeout");
    t=setup(run);Thread.currentThread().interrupt();
    equal(MorningHistoryReader.read(t,run),null,"interruption cancels reader");check(Thread.currentThread().isInterrupted(),"reader preserves interrupt flag");Thread.interrupted();
  }
  static void fixtureCandidates(){AlarmReceiver.durations.put(A,3500);AlarmReceiver.durations.put(B,3500);}
  static void freshFence(Transport t){t.prefs.data.put("startup_fence_state","live");t.prefs.data.put("startup_fence_sample_wall",System.currentTimeMillis());t.prefs.data.put("startup_fence_received_elapsed",SystemClock.elapsedRealtime());t.prefs.data.put("startup_fence_deadline_wall",RunCoordinator.deadline);}
  static void continuationBoundary()throws Exception{
    String run="deferred-real-run";Transport t=setup(run);fixtureCandidates();
    t.prefs.data.put("startup_fence_state","live");t.prefs.data.put("startup_fence_sample_wall",System.currentTimeMillis()-1000);
    t.prefs.data.put("startup_fence_received_elapsed",SystemClock.elapsedRealtime()-1000);t.prefs.data.put("startup_fence_deadline_wall",RunCoordinator.deadline);
    JSONObject parsed=MorningHistoryReply.parse(ready(t,run),run,t.prefs.getLong("run_started_at",-1),RunCoordinator.deadline,System.currentTimeMillis());
    MorningSelectionRepair.selectAndVerify(t,run,parsed);
    equal(RunCoordinator.selected,B,"actual selection adapter stores authoritative candidate");equal(AlarmReceiver.routed,0,"selection cannot route while receiver owns worker");equal(AlarmReceiver.scheduled,1,"existing backup continuation scheduled");
    BroadcastReceiver.PendingResult result=new BroadcastReceiver.PendingResult();WorkerFenceContinuation.finishReceiver(result);
    check(result.finished,"receiver released before fence continuation");check(Handler.pending()>0,"post-release continuation enqueued");
    Handler.runDue();equal(AlarmReceiver.routed,0,"pre-release live sample cannot satisfy worker fence");check(RunCoordinator.active,"waiting for new sample does not cancel run");
    freshFence(t);SystemClock.advance(50);Handler.runDue();equal(AlarmReceiver.routed,1,"fresh post-worker sample routes exactly once");
    equal(AlarmReceiver.routedRun,run,"deferred route keeps same run");equal(AlarmReceiver.routedVideo,B,"deferred route keeps selected video");
    SystemClock.advance(50);Handler.runDue();equal(AlarmReceiver.routed,1,"continuation cannot replay selected route");

    t=setup(run);fixtureCandidates();MorningSelectionRepair.selectAndVerify(t,run,envelope(System.currentTimeMillis()));RunCoordinator.active=false;
    WorkerFenceContinuation.finishReceiver(new BroadcastReceiver.PendingResult());Handler.runDue();equal(AlarmReceiver.routed,0,"phone cancellation before release prevents deferred route");
    t=setup(run);fixtureCandidates();MorningSelectionRepair.selectAndVerify(t,run,envelope(System.currentTimeMillis()));
    WorkerFenceContinuation.finishReceiver(new BroadcastReceiver.PendingResult());RunCoordinator.runId="newer-run";freshFence(t);SystemClock.advance(50);Handler.runDue();
    equal(AlarmReceiver.routed,0,"stale continuation cannot route newer run");check(RunCoordinator.active,"stale continuation does not cancel newer run");equal(t.cancellations.size(),0,"no unrelated guard cancellation");

    t=setup(run);fixtureCandidates();MorningSelectionRepair.selectAndVerify(t,run,envelope(System.currentTimeMillis()));
    WorkerFenceContinuation.finishReceiver(new BroadcastReceiver.PendingResult());SystemClock.advance(3001);Handler.runDue();
    equal(AlarmReceiver.routed,0,"missing fresh fence never routes");equal(RunCoordinator.reason,"startup_worker_fence_deadline","fence deadline recorded");
    equal(t.cancellations.size(),1,"deadline sends one matching guard cancellation");equal(t.cancellations.get(0).getStringExtra("run_generation"),run,"cancellation bound to failed run");

    t=setup(run);fixtureCandidates();RunCoordinator.pendingAllowed=false;MorningSelectionRepair.selectAndVerify(t,run,envelope(System.currentTimeMillis()));
    WorkerFenceContinuation.finishReceiver(new BroadcastReceiver.PendingResult());equal(Handler.pending(),0,"failed pending write does not create continuation");equal(AlarmReceiver.scheduled,0,"failed pending write schedules no backup");
    t=setup(run);fixtureCandidates();JSONObject invalid=envelope(System.currentTimeMillis());invalid.getJSONObject("__youtube_history").put("complete",false);MorningSelectionRepair.selectAndVerify(t,run,invalid);
    WorkerFenceContinuation.finishReceiver(new BroadcastReceiver.PendingResult());equal(RunCoordinator.reason,"account_selection_failed","invalid account proof fails real selection adapter");equal(Handler.pending(),0,"invalid proof never reaches worker continuation");
  }
  public static void main(String[] args)throws Exception{
    parserToSelector();orderedShim();realReader();continuationBoundary();
    System.out.println(checks+" independent helper boundary assertions passed");
  }
}
