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
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.Map;

public final class MorningChannelSourceTest {
    private static int checks,requests,status=200;
    private static String body;
    private static boolean cancel,uniqueTokens;
    private static long advance;
    private static ByteArrayOutputStream lastRequest;
    private static void check(boolean value,String message){checks++;if(!value)throw new AssertionError(message);}
    private static void reset(){RunCoordinator.reset();SystemClock.now=0;requests=0;status=200;cancel=false;uniqueTokens=false;advance=0;}
    public static void main(String[] args)throws Exception {
        final String html=new String(Files.readAllBytes(Paths.get(args[0])),StandardCharsets.UTF_8);
        final String fixture=new String(Files.readAllBytes(Paths.get(args[1])),StandardCharsets.UTF_8);
        URL.setURLStreamHandlerFactory(protocol->"https".equals(protocol)?new URLStreamHandler(){
            protected URLConnection openConnection(URL u){
                check("www.youtube.com".equals(u.getHost())&&"/youtubei/v1/browse".equals(u.getPath()),"pagination remains on intended public origin");
                requests++;
                return new HttpURLConnection(u){
                    public void disconnect(){}
                    public boolean usingProxy(){return false;}
                    public void connect(){}
                    public OutputStream getOutputStream(){lastRequest=new ByteArrayOutputStream();return lastRequest;}
                    public int getResponseCode(){SystemClock.now+=advance;if(cancel)RunCoordinator.active=false;return status;}
                    public InputStream getInputStream(){String value=uniqueTokens?body.replace("fixture-next-token","page-"+requests):body;return new ByteArrayInputStream(value.getBytes(StandardCharsets.UTF_8));}
                };
            }
        }:null);
        reset();body=fixture;
        Map<String,Integer> got=MorningChannelSource.fetch(null,"run",html);
        check(got.size()>21&&got.containsKey("4K3e1SV9m8g"),"older source entries available after initial pool");
        check(requests==1,"repeated continuation token cannot loop");
        check(got.keySet().iterator().next().equals("vGPvcQCDVGY"),"pagination keeps newest entries first");
        check(lastRequest.toString("UTF-8").contains("MWEB"),"request uses parsed client identity");
        check(RunCoordinator.wakeChecks>0,"existing startup wake and cancellation checks remain active");
        reset();body=fixture;status=503;
        check(MorningChannelSource.fetch(null,"run",html).size()==21,"continuation failure retains only verified newer source");
        reset();RunCoordinator.active=false;
        try{MorningChannelSource.fetch(null,"old",html);throw new AssertionError("cancel expected");}
        catch(InterruptedException expected){check(requests==0,"obsolete run never requests network");}
        reset();body=fixture;cancel=true;
        try{MorningChannelSource.fetch(null,"cancelled",html);throw new AssertionError("cancel expected");}
        catch(InterruptedException expected){check(requests==1,"cancellation during read does not return a runnable result");}
        reset();body=fixture;uniqueTokens=true;
        MorningChannelSource.fetch(null,"run",html);
        check(requests==11,"maximum12total source pages enforced");
        reset();body=fixture;uniqueTokens=true;advance=10000;
        MorningChannelSource.fetch(null,"run",html);
        check(requests==4,"total40second pagination budget bounds changing continuations");
        reset();body="{\"recommendations\":[{\"videoRenderer\":{\"videoId\":\"maliciousxx\",\"lengthText\":{\"simpleText\":\"59:00\"}}}]}";
        check(MorningChannelSource.fetch(null,"run",html).size()==21,"unrelated response entries never added");
        System.out.println("MorningChannelSource: "+checks+" integration assertions passed");
    }
}
