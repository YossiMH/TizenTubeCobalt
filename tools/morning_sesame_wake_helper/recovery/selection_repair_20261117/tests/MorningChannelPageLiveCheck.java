package dev.yossi.morningsesame;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
/** Optional check of newly retrieved public responses; never used as a deterministic regression fixture. */
public final class MorningChannelPageLiveCheck {
    public static void main(String[] args) throws Exception {
        MorningChannelPage first=MorningChannelPage.initial(new String(Files.readAllBytes(Paths.get(args[0])),StandardCharsets.UTF_8));
        MorningChannelPage next=MorningChannelPage.continuation(new String(Files.readAllBytes(Paths.get(args[1])),StandardCharsets.UTF_8));
        if(first.videos.isEmpty()||next.videos.isEmpty()||first.continuation==null||first.client==null)
            throw new AssertionError("live public pages missing expected shelf/continuation data");
        System.out.println("Live public channel parsing: "+first.videos.size()+" initial episodes, "+next.videos.size()
                +" next-page episodes, newest="+first.videos.keySet().iterator().next());
    }
}
