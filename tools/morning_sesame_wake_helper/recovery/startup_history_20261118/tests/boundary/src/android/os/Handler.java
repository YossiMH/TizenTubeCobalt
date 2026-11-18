package android.os;
import java.util.ArrayList;
public final class Handler {
  private static final class Job { final Runnable run; final long at; Job(Runnable r,long t){run=r;at=t;} }
  private static final ArrayList<Job> JOBS=new ArrayList<>();
  public static boolean accept=true;
  public Handler(Looper ignored){}
  public boolean post(Runnable run){return postDelayed(run,0);}
  public boolean postDelayed(Runnable run,long delay){if(!accept)return false;JOBS.add(new Job(run,SystemClock.elapsedRealtime()+delay));return true;}
  public static void reset(){JOBS.clear();accept=true;}
  public static int pending(){return JOBS.size();}
  public static void runDue(){
    int count=0;
    while(true){int selected=-1;long at=Long.MAX_VALUE;for(int i=0;i<JOBS.size();i++){
      Job j=JOBS.get(i);if(j.at<=SystemClock.elapsedRealtime()&&j.at<at){selected=i;at=j.at;}}
      if(selected<0)return;if(++count>1000)throw new AssertionError("handler loop");
      JOBS.remove(selected).run.run();
    }
  }
}
