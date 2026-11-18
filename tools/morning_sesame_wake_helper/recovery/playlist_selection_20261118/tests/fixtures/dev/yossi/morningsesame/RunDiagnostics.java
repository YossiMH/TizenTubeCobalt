package dev.yossi.morningsesame;
import android.content.Context;
import java.util.LinkedHashMap;
import java.util.Map;
final class RunDiagnostics {
    static String result;
    static void event(Context c,String r,String p,String value,Map<String,Long> m) {result=value;}
    static Map<String,Long> values(Object... pairs) {
        Map<String,Long> result=new LinkedHashMap<>();
        for(int i=0;i+1<pairs.length;i+=2)result.put((String)pairs[i],((Number)pairs[i+1]).longValue());
        return result;
    }
}
