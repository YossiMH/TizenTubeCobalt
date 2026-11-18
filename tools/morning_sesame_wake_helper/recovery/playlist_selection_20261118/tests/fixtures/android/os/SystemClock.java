package android.os;
public final class SystemClock {
    public static long now;
    public static boolean realtime;
    public static long elapsedRealtime() {return realtime ? System.nanoTime()/1000000L : now;}
}
