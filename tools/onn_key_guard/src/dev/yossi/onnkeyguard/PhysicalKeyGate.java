package dev.yossi.onnkeyguard;

import java.util.HashSet;
import java.util.Set;

/** O(1) fallback gate preserving the release of every physical down that this gate consumed. */
public final class PhysicalKeyGate {
    public enum Protection { ENABLED, DISABLED }
    private final Set<Long> blockedDowns = new HashSet<Long>();

    public boolean onEvent(int action, int deviceId, int keyCode, RemoteIdentity.Origin origin,
            Protection protection) {
        long identity = (((long) deviceId) << 32) | (keyCode & 0xffffffffL);
        if (action == 1) return blockedDowns.remove(Long.valueOf(identity));
        if (action == 0 && blockedDowns.contains(Long.valueOf(identity))) return true;
        if (protection != Protection.ENABLED || origin != RemoteIdentity.Origin.PHYSICAL_REMOTE
                || volumeKey(keyCode)) return false;
        if (action == 0) {
            blockedDowns.add(Long.valueOf(identity));
            return true;
        }
        return action == 2;
    }

    public void clear() { blockedDowns.clear(); }

    private static boolean volumeKey(int keyCode) {
        return keyCode == 24 || keyCode == 25 || keyCode == 91 || keyCode == 164;
    }
}
