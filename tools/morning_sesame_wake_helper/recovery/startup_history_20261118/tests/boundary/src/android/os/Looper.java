package android.os;
public final class Looper {
  private static final Looper MAIN=new Looper();
  private static final ThreadLocal<Looper> CURRENT=new ThreadLocal<>();
  public static Looper getMainLooper(){return MAIN;}
  public static Looper myLooper(){return CURRENT.get();}
  public static void mainThread(boolean value){ if(value) CURRENT.set(MAIN);else CURRENT.remove(); }
}
