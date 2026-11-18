package dev.yossi.morningsesame;

import java.util.Locale;

/* JADX INFO: loaded from: classes.dex */
final class StartupFencePolicy {

    enum State {
        PENDING,
        LIVE,
        CANCELLED,
        EXPIRED,
        COMPLETED,
        UNKNOWN
    }

    private StartupFencePolicy() {
    }

    static boolean accepts(State state, State state2, long j, long j2, long j3) {
        return (state == State.CANCELLED || state == State.EXPIRED || state == State.COMPLETED || state2 == State.UNKNOWN || state2 == State.PENDING || j <= j2 || j <= 0 || j > 1000 + j3 || j3 - j > 3000) ? false : true;
    }

    static boolean permits(State state, long j, long j2, long j3, long j4) {
        return state == State.LIVE && j >= 0 && j2 >= j && j2 - j <= 3000 && j3 > j4;
    }

    static State parse(String str) {
        try {
            return State.valueOf(str.toUpperCase(Locale.ROOT));
        } catch (RuntimeException e) {
            return State.UNKNOWN;
        }
    }
}
