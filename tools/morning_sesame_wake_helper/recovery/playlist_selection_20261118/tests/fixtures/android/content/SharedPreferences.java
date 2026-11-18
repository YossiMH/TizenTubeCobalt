package android.content;
import java.util.Set;
public interface SharedPreferences {
    String getString(String key,String fallback);
    Set<String> getStringSet(String key,Set<String> fallback);
    Editor edit();
    interface Editor {
        Editor putString(String key,String value);
        Editor putStringSet(String key,Set<String> value);
        boolean commit();
    }
}
