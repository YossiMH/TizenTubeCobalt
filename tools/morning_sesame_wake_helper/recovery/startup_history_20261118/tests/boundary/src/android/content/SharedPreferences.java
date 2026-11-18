package android.content;
public interface SharedPreferences {
  long getLong(String key,long fallback);
  String getString(String key,String fallback);
}
