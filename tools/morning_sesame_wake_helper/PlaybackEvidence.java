package dev.yossi.morningsesame;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Pure, testable interpretation of fresh, advancing player evidence. */
final class PlaybackEvidence {
    private static final Pattern VIDEO = Pattern.compile("<ms_playing>([A-Za-z0-9_-]{11})</ms_playing>");
    private static final Pattern AT = Pattern.compile("<ms_playing_at>([0-9]+)</ms_playing_at>");
    private static final Pattern POSITION = Pattern.compile("<ms_position>([0-9]+(?:\\.[0-9]+)?)</ms_position>");
    final long at;
    final double position;

    private PlaybackEvidence(long at, double position) {
        this.at = at;
        this.position = position;
    }

    static PlaybackEvidence read(String xml, String expectedVideo, long runStarted, long now) {
        if (xml == null || runStarted <= 0 || expectedVideo == null
                || !xml.contains("<yumi>morning-sesame</yumi>")
                || !xml.contains("<ms_state>playing</ms_state>")) return null;
        Matcher video = VIDEO.matcher(xml), at = AT.matcher(xml), position = POSITION.matcher(xml);
        if (!video.find() || !expectedVideo.equals(video.group(1))
                || !at.find() || !position.find()) return null;
        try {
            long sampledAt = Long.parseLong(at.group(1));
            double sampledPosition = Double.parseDouble(position.group(1));
            if (sampledAt < runStarted || sampledAt > now + 1000L
                    || now - sampledAt > 5000L || !Double.isFinite(sampledPosition)
                    || sampledPosition < 0.0) return null;
            return new PlaybackEvidence(sampledAt, sampledPosition);
        } catch (NumberFormatException ex) {
            return null;
        }
    }

    static boolean advances(PlaybackEvidence first, PlaybackEvidence second) {
        return first != null && second != null && second.at > first.at
                && second.position > first.position + 0.05;
    }
}
