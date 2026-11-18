package android.content;
public abstract class BroadcastReceiver {
  private String resultData;
  public abstract void onReceive(Context context,Intent intent);
  public String getResultData(){return resultData;}
  public void setResultData(String value){resultData=value;}
  public static class PendingResult {
    public boolean finished,throwOnFinish;
    public void finish(){if(throwOnFinish)throw new IllegalStateException("fixture finish failed");finished=true;}
  }
}
