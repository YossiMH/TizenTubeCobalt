package dev.yossi.onnkeyguard;

/** One DUMP-authorized after-hours test; only a real signature-owned alarm may consume it. */
public final class MorningWindowGrant {
    private long scheduledWall = -1L, armedElapsed = -1L, expiresElapsed = -1L;
    private int helperUid = -1, fallbackSeconds;
    private String consumedRun = "";
    private long cutoffWall = -1L, fallbackWall = -1L;

    public synchronized boolean arm(long scheduled, int uid, int fallback, int duration,
            long now, long elapsed) {
        if (uid < 10000 || now < 0 || elapsed < 0 || scheduled < now - 1000L
                || scheduled > now + 60000L || duration < 30 || duration > 180
                || fallback < 5 || fallback >= duration || scheduled - now + fallback * 1000L >= duration * 1000L
                || live(elapsed)) return false;
        scheduledWall = scheduled; helperUid = uid; fallbackSeconds = fallback;
        armedElapsed = elapsed; expiresElapsed = elapsed + duration * 1000L;
        consumedRun = ""; cutoffWall = fallbackWall = -1L;
        return true;
    }

    public synchronized boolean consume(String run, long start, long issued, int senderUid,
            long now, long elapsed) {
        if (!live(elapsed) || consumedRun.length() > 0 || run == null
                || !run.matches("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}")
                || senderUid != helperUid || start < scheduledWall - 1000L || start > scheduledWall + 5000L
                || issued < start || issued > scheduledWall + 5000L || now < issued - 1000L
                || now > scheduledWall + 10000L || expiresElapsed - elapsed <= fallbackSeconds * 1000L)
            return false;
        consumedRun = run;
        cutoffWall = now + expiresElapsed - elapsed;
        fallbackWall = now + fallbackSeconds * 1000L;
        return true;
    }

    public synchronized boolean live(long elapsed) {
        return armedElapsed >= 0 && elapsed >= armedElapsed && elapsed < expiresElapsed;
    }
    public synchronized long cutoffWall() { return cutoffWall; }
    public synchronized long fallbackWall() { return fallbackWall; }
    public synchronized long expiresElapsed() { return expiresElapsed; }
    public synchronized long scheduledWall() { return scheduledWall; }
    public synchronized String consumedRun() { return consumedRun; }
    public synchronized void cancel() { expiresElapsed = -1L; }
}
