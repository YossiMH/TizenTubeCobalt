package android.content;
import android.os.Bundle;
import android.os.Handler;
public abstract class Context {
  public static final int MODE_PRIVATE=0;
  public Context getApplicationContext(){return this;}
  public abstract SharedPreferences getSharedPreferences(String name,int mode);
  public abstract void sendOrderedBroadcast(Intent intent,String permission,BroadcastReceiver result,Handler scheduler,int code,String data,Bundle extras);
  public abstract void sendOrderedBroadcast(Intent intent,String permission,Bundle options,BroadcastReceiver result,Handler scheduler,int code,String data,Bundle extras);
  public abstract void sendBroadcast(Intent intent);
}
