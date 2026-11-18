package android.content;
import java.util.HashMap;
public class Intent {
  public final String action; public String packageName,className;
  public final HashMap<String,Object> extras=new HashMap<>();
  public Intent(String action){this.action=action;}
  public Intent setClassName(String pkg,String cls){packageName=pkg;className=cls;return this;}
  public Intent putExtra(String key,String value){extras.put(key,value);return this;}
  public Intent putExtra(String key,long value){extras.put(key,value);return this;}
  public String getStringExtra(String key){return (String)extras.get(key);}
  public long getLongExtra(String key,long fallback){Object v=extras.get(key);return v instanceof Number?((Number)v).longValue():fallback;}
}
