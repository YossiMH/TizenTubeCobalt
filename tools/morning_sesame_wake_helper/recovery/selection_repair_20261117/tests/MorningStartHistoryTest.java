package dev.yossi.morningsesame;

import java.util.Calendar;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.TimeZone;

public final class MorningStartHistoryTest {
    private static int checks;
    private static void check(boolean value, String message) {
        checks++;
        if (!value) throw new AssertionError(message);
    }
    private static long wall(int year, int month, int day, int hour, int minute) {
        Calendar c = Calendar.getInstance();
        c.clear(); c.set(year, month - 1, day, hour, minute);
        return c.getTimeInMillis();
    }
    public static void main(String[] args) {
        TimeZone.setDefault(TimeZone.getTimeZone("America/Los_Angeles"));
        String a="aaaaaaaaaaa", b="bbbbbbbbbbb";
        Map<String,Long> history = new LinkedHashMap<>();
        long yesterday = wall(2026,11,16,7,1), today = wall(2026,11,17,7,4);
        history = MorningStartHistory.decode(MorningStartHistory.record(history,a,yesterday));
        check(MorningStartHistory.recent(history,today).contains(a), "yesterday remains recent despite startup jitter");
        check(!MorningStartHistory.recent(history,wall(2026,11,18,7,0)).contains(a), "older interrupted episode can qualify again");
        history.put(b,0L);
        check(!MorningStartHistory.recent(history,today).contains(b), "legacy unknown date is not fabricated as recent");
        history = MorningStartHistory.decode(MorningStartHistory.record(history,a,today));
        check(history.get(a)==today, "confirmed repeat updates receipt");
        check(history.containsKey(b), "legacy receipt retained independently");
        history.clear();
        for(int i=0;i<1100;i++) history=MorningStartHistory.decode(MorningStartHistory.record(history,String.format("%011d",i),today+i));
        check(history.size()==1024,"receipt count is bounded");
        check(!history.containsKey("00000000000"),"oldest overflow is pruned");
        check(history.containsKey("00000001099"),"newest receipt is retained");
        check(MorningStartHistory.record(history,a,today).length()<=MorningStartHistory.MAX_CHARS,"encoded history is bounded");
        check(MorningStartHistory.decode("bad;aaaaaaaaaaa,-1;bbbbbbbbbbb,bad").isEmpty(),"malformed receipts rejected");
        check(MorningStartHistory.decode(new String(new char[MorningStartHistory.MAX_CHARS+1])).isEmpty(),"oversize input rejected");
        history.clear();history.put(a,wall(2026,11,1,7,0));
        check(MorningStartHistory.recent(history,wall(2026,11,2,7,0)).contains(a),"fall DST previous day retained");
        history.clear();history.put(a,wall(2026,3,8,7,0));
        check(MorningStartHistory.recent(history,wall(2026,3,9,7,0)).contains(a),"spring DST previous day retained");
        long losAngelesEvening=wall(2026,11,17,18,0);
        history.clear();history.put(a,yesterday);
        TimeZone.setDefault(TimeZone.getTimeZone("UTC"));
        check(MorningStartHistory.recent(history,losAngelesEvening).contains(a),"device timezone changes do not move household date boundary");
        System.out.println("MorningStartHistory: "+checks+" behavioral assertions passed");
    }
}
