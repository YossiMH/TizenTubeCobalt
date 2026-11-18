package android.os;
public final class SystemClock {
  private static final long BASE=System.nanoTime();
  private static long offset;
  public static long elapsedRealtime(){ return 10000L+(System.nanoTime()-BASE)/1000000L+offset; }
  public static void advance(long ms){ offset+=ms; }
}
