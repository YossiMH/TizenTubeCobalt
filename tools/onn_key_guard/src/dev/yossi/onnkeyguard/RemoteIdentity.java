package dev.yossi.onnkeyguard;

/** Exact observed identities. Android virtual flags and arbitrary positive device IDs are not origin proof. */
public final class RemoteIdentity {
    public enum Origin { PHYSICAL_REMOTE, SOFTWARE_REMOTE, ASSISTANT, UNKNOWN }
    public static final String PHYSICAL_DESCRIPTOR = "3d8443aec63f42aec455afaaad5531f697701d05";
    public static final String PHONE_DESCRIPTOR = "d76b0dc9b6d844ab25ed6d04f6d43a45d3806d72";

    private RemoteIdentity() { }

    public static Origin classify(int deviceId, String name, int vendorId, int productId,
            String descriptor) {
        if (deviceId == -1) return Origin.ASSISTANT;
        if ("Onn-Remote Keyboard".equals(name) && vendorId == 0x0957 && productId == 0x0031
                && PHYSICAL_DESCRIPTOR.equals(descriptor)) return Origin.PHYSICAL_REMOTE;
        if ("virtual-remote".equals(name) && vendorId == 0x18d1 && productId == 0x0100
                && PHONE_DESCRIPTOR.equals(descriptor)) return Origin.SOFTWARE_REMOTE;
        return Origin.UNKNOWN;
    }
}
