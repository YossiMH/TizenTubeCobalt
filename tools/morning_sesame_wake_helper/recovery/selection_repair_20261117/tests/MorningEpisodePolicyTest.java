package dev.yossi.morningsesame;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

public final class MorningEpisodePolicyTest {
    private static int checks;
    private static final String A = "aaaaaaaaaaa", B = "bbbbbbbbbbb", C = "ccccccccccc";

    private static void check(boolean value, String message) {
        checks++;
        if (!value) throw new AssertionError(message);
    }

    private static Map<String, Integer> episodes() {
        Map<String, Integer> result = new LinkedHashMap<>();
        result.put(A, 3600); result.put(B, 5400); result.put(C, 3000);
        return result;
    }

    private static String select(Map<String, Integer> videos, Map<String, Double> account,
            Map<String, Double> local, Set<String> starts) {
        return MorningEpisodePolicy.select(videos, account, local, starts, Collections.emptySet());
    }

    public static void main(String[] args) {
        Map<String, Integer> videos = episodes();
        Map<String, Double> account = new LinkedHashMap<>(), local = new LinkedHashMap<>();
        Set<String> starts = new HashSet<>();
        check(A.equals(select(videos, account, local, starts)), "newest eligible beats longest");
        videos.put(A, 2400);
        check(B.equals(select(videos, account, local, starts)), "exactly 40 minutes rejected");
        videos.put(A, 2401);
        check(A.equals(select(videos, account, local, starts)), "one second over 40 minutes accepted");
        account.put(A, 25.0);
        check(A.equals(select(videos, account, local, starts)), "exactly 75 percent unwatched accepted");
        account.put(A, 25.0001);
        check(B.equals(select(videos, account, local, starts)), "just over 25 percent watched rejected");
        for (Double bad : Arrays.asList(Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY,
                -1.0, 50.0, 75.0, 100.0, 101.0, null)) {
            account.put(A, bad);
            check(B.equals(select(videos, account, local, starts)), "known bad progress cannot enter fallback: " + bad);
        }
        account.clear();
        local.put(A, 80.0);
        check(B.equals(select(videos, account, local, starts)), "local observation survives missing account history");
        account.put(A, 0.0);
        check(B.equals(select(videos, account, local, starts)), "stale zero account cannot undo local progress");
        local.put(A, 5.0);
        account.put(A, 80.0);
        check(B.equals(select(videos, account, local, starts)), "low local progress cannot undo high account progress");
        local.clear(); account.clear();
        starts.add(A);
        account.put(A, 0.0);
        check(B.equals(select(videos, account, local, starts)), "confirmed start blocks stale account replay");
        starts.clear(); account.clear();
        for (String expected : Arrays.asList(A, B, C)) {
            String chosen = select(videos, account, local, starts);
            check(expected.equals(chosen), "consecutive mornings advance in newest-first order");
            starts.add(chosen);
        }
        for (int day = 0; day < 30; day++) {
            check(select(videos, account, local, starts) == null, "exhaustion never loops longest episode day " + day);
        }
        Map<String, Integer> refreshed = new LinkedHashMap<>();
        refreshed.put("ddddddddddd", 3600); refreshed.putAll(videos);
        check("ddddddddddd".equals(select(refreshed, account, local, starts)), "new upload restores eligible playback");
        local.put(A, 4.0);
        check(A.equals(select(videos, account, local, starts)), "old interrupted episode allowed when actual local observation proves eligibility");
        check(MorningEpisodePolicy.select(videos, account, local, starts,
                new HashSet<>(Arrays.asList(A))) == null, "recent interruption does not cause next-morning repeat");
        local.clear();
        starts.clear();
        account.put(A, 90.0); account.put(B, 90.0); account.put(C, 90.0);
        check(select(videos, account, local, starts) == null, "all account-watched produces no eligible candidate");
        account.clear();
        videos.put(A, null); videos.put(B, -1); videos.put(C, 2400);
        check(select(videos, account, local, starts) == null, "unknown or invalid duration cannot qualify");
        videos.put(A, 6 * 3600 + 1);
        check(select(videos, account, local, starts) == null, "unsupported duration cannot launch without protection");
        videos.put(A, 6 * 3600);
        check(A.equals(select(videos, account, local, starts)), "six-hour protection boundary accepted");
        videos.clear(); videos.put("bad", 3600); videos.put(null, 3600);
        check(select(videos, account, local, starts) == null, "invalid IDs rejected");
        check(select(Collections.emptyMap(), null, null, null) == null, "empty sources return no episode");
        check(select(null, null, null, null) == null, "missing sources return no episode");
        check(A.equals(select(episodes(), null, null, null)), "never-started local fallback is explicit");
        System.out.println("MorningEpisodePolicy: " + checks + " behavioral assertions passed");
    }
}
