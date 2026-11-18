package dev.yossi.morningsesame;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.Map;

/** Optional public read-only source check; never reads account history or starts playback. */
public final class MorningPlaylistLiveCheck {
    public static void main(String[]args)throws Exception{
        android.os.SystemClock.realtime=true;
        long started=android.os.SystemClock.elapsedRealtime();
        String html=new String(Files.readAllBytes(Paths.get(args[0])),StandardCharsets.UTF_8);
        MorningPlaylistPage first=MorningPlaylistPage.initial(html);
        System.out.println("initial_members="+first.videos.size()+" has_continuation="+(first.continuation!=null));
        Map<String,Integer> videos=MorningPlaylistSource.fetch(null,"read-only-public-check",html);
        System.out.println("qualifying_upload_order_count="+videos.size());
        System.out.println("elapsed_ms="+(android.os.SystemClock.elapsedRealtime()-started));
        if(args.length>1){
            StringBuilder rows=new StringBuilder();
            for(Map.Entry<String,Integer> entry:videos.entrySet())rows.append(entry.getKey()).append(',').append(entry.getValue()).append(';');
            Files.write(Paths.get(args[1]),rows.toString().getBytes(StandardCharsets.UTF_8));
        }
        int shown=0;for(Map.Entry<String,Integer> entry:videos.entrySet()){
            if(shown++>=10)break;System.out.println(entry.getKey()+","+entry.getValue());
        }
    }
}
