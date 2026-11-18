package dev.yossi.onnkeyguard;
import android.content.Context;
import android.view.View;
import org.json.JSONObject;
/** Compile-only ABI; the installed rich renderer remains bytecode-identical. */
public final class SchoolReminderMedia {
    public interface Guard { boolean authorized(); }
    public SchoolReminderMedia(Context context, Guard guard, boolean diagnostic) { }
    public View view() { throw new UnsupportedOperationException(); }
    public void start() { throw new UnsupportedOperationException(); }
    public void close() { throw new UnsupportedOperationException(); }
    public void setAudioFocusHeld(boolean held) { throw new UnsupportedOperationException(); }
    public JSONObject status() { throw new UnsupportedOperationException(); }
}
