package android.app;
import android.os.Bundle;
public final class BroadcastOptions {
  private boolean share;
  public static BroadcastOptions makeBasic(){return new BroadcastOptions();}
  public BroadcastOptions setShareIdentityEnabled(boolean enabled){share=enabled;return this;}
  public Bundle toBundle(){Bundle b=new Bundle();b.put("share_identity",share);return b;}
}
