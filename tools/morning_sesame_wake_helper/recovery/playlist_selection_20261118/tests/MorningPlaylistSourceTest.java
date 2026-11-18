package dev.yossi.morningsesame;
import android.os.SystemClock;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLConnection;
import java.net.URLStreamHandler;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import org.json.JSONObject;

/** Only the HTTPS transport and Android run/wake boundary are faked. Production parsers drive requests. */
public final class MorningPlaylistSourceTest {
    static int checks,requests;
    static final List<Reply> replies=new ArrayList<>();
    static final class Reply {String body;int status=200;long advance;boolean cancel;Reply(String body){this.body=body;}}
    static void check(boolean yes,String why){checks++;if(!yes)throw new AssertionError(why);}
    static void reset(){RunCoordinator.reset();SystemClock.now=0;requests=0;replies.clear();}
    static Reply reply(String body){Reply r=new Reply(body);replies.add(r);return r;}
    interface Checked {void run()throws Exception;}
    static void failed(Checked task,String why)throws Exception{
        try{task.run();throw new AssertionError(why);}catch(IllegalStateException|IllegalArgumentException expected){checks++;}
    }
    public static void main(String[]args)throws Exception{
        URL.setURLStreamHandlerFactory(protocol->"https".equals(protocol)?new URLStreamHandler(){
            protected URLConnection openConnection(URL url){
                check("www.youtube.com".equals(url.getHost())||"m.youtube.com".equals(url.getHost()),"all public requests remain on YouTube origin");
                if(requests>=replies.size())throw new AssertionError("unexpected network request "+url);
                Reply response=replies.get(requests++);
                return new HttpURLConnection(url){
                    ByteArrayOutputStream body;
                    public void disconnect(){}
                    public boolean usingProxy(){return false;}
                    public void connect(){}
                    public OutputStream getOutputStream(){body=new ByteArrayOutputStream();return body;}
                    public int getResponseCode(){
                        if(body!=null){
                            check("/youtubei/v1/browse".equals(url.getPath()),"continuation uses browse endpoint");
                            JSONObject sent=new JSONObject(new String(body.toByteArray(),StandardCharsets.UTF_8));
                            check(sent.has("continuation")&&!sent.has("browseId"),"request only follows scoped continuation");
                            check("MWEB".equals(sent.getJSONObject("context").getJSONObject("client").getString("clientName")),"clean public client identity");
                        }else check("m.youtube.com".equals(url.getHost())&&"/@SesameStreetClassics/videos".equals(url.getPath()),"upload order uses exact mobile Latest route without cross-origin redirects");
                        SystemClock.now+=response.advance;if(response.cancel)RunCoordinator.active=false;return response.status;
                    }
                    public InputStream getInputStream(){return new ByteArrayInputStream(response.body.getBytes(StandardCharsets.UTF_8));}
                };
            }
        }:null);
        reset();reply(TestPages.playlistNext(null,TestPages.A,TestPages.C));
        reply(TestPages.channel("older","outside0001",TestPages.A));reply(TestPages.channelNext(null,TestPages.B,TestPages.C));
        Map<String,Integer> got=MorningPlaylistSource.fetch(null,"run",TestPages.playlist("more",TestPages.B));
        check(new ArrayList<>(got.keySet()).equals(Arrays.asList(TestPages.A,TestPages.B,TestPages.C)),"playlist-added order replaced by true Latest upload order");
        check(!got.containsKey("outside0001"),"newer channel video outside playlist never selected");
        check(requests==3,"both membership and Latest continuations followed");
        check(RunCoordinator.wakeChecks>0,"existing cancellation/wake fences remain active");
        reset();reply(TestPages.playlistNext("loop",TestPages.B));
        failed(()->MorningPlaylistSource.fetch(null,"run",TestPages.playlist("loop",TestPages.A)),"repeated playlist token cannot fake completion");
        check(requests==1,"no channel query on incomplete playlist");
        reset();reply("{}").status=503;
        failed(()->MorningPlaylistSource.fetch(null,"run",TestPages.playlist("next",TestPages.A)),"failed playlist page has no membership fallback");
        reset();RunCoordinator.active=false;
        try{MorningPlaylistSource.fetch(null,"obsolete",TestPages.playlist(null,TestPages.A));throw new AssertionError("cancel expected");}
        catch(InterruptedException expected){check(requests==0,"obsolete run never reaches network");}
        reset();reply(TestPages.playlistNext(null,TestPages.B)).cancel=true;
        try{MorningPlaylistSource.fetch(null,"phone-cancel",TestPages.playlist("next",TestPages.A));throw new AssertionError("cancel expected");}
        catch(InterruptedException expected){check(requests==1,"cancellation during read yields no candidate map");}
        reset();reply(TestPages.playlistNext(null,TestPages.B)).advance=40000;
        failed(()->MorningPlaylistSource.fetch(null,"run",TestPages.playlist("next",TestPages.A)),"total startup page budget enforced");
        reset();for(int i=0;i<15;i++)reply(TestPages.playlistNext("p"+(i+2),TestPages.B));
        failed(()->MorningPlaylistSource.fetch(null,"run",TestPages.playlist("p1",TestPages.A)),"16-page membership bound enforced");
        check(requests==15,"no unbounded membership pagination");
        reset();reply(TestPages.channel("older",TestPages.A));reply("{}").status=503;
        got=MorningPlaylistSource.fetch(null,"run",TestPages.playlist(null,TestPages.B,TestPages.A));
        check(got.size()==1&&got.containsKey(TestPages.A),"older upload failure retains only verified newest playlist prefix");
        reset();String[] ids=new String[35];for(int i=0;i<ids.length;i++)ids[i]=String.format("synthetic%02d",i);
        reply(TestPages.playlistNext(null,Arrays.copyOfRange(ids,20,35)));
        reply(TestPages.channel("older",Arrays.copyOfRange(ids,0,20)));
        reply(TestPages.channelNext(null,Arrays.copyOfRange(ids,20,35)));
        got=MorningPlaylistSource.fetch(null,"run",TestPages.playlist("more",Arrays.copyOfRange(ids,0,20)));
        check(got.size()==35&&got.containsKey(ids[34]),"pool extends beyond original21 episodes");
        JSONObject account=TestPages.history(System.currentTimeMillis(),ids[34],0);
        check(ids[34].equals(MorningEpisodePolicy.select(got,MorningYouTubeHistory.parse(account,System.currentTimeMillis()))),"older eligible episode survives exhausted first pages");
        System.out.println("MorningPlaylistSource: "+checks+" bounded transport and source assertions passed");
    }
}
