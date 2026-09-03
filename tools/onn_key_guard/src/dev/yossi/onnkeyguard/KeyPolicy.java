package dev.yossi.onnkeyguard;

public final class KeyPolicy {
    private KeyPolicy() {}
    public static boolean shouldBlock(boolean interactive, boolean lockScreenVisible, int keyCode) {
        if (interactive && !lockScreenVisible) return false;
        return !isAllowedWhileLocked(keyCode);
    }
    public static boolean isAllowedWhileLocked(int keyCode) {
        if (keyCode >= 7 && keyCode <= 26) return true;
        if (keyCode >= 0x55 && keyCode <= 0x5b) return true;
        if (keyCode >= 0x7e && keyCode <= 0x82) return true;
        if (keyCode >= 0x90 && keyCode <= 0x99) return true;
        if (keyCode >= 0xde && keyCode <= 0xe0) return true;
        if (keyCode >= 0x104 && keyCode <= 0x107) return true;
        if (keyCode >= 0x10c && keyCode <= 0x113) return true;
        switch (keyCode) {
            case 3: case 4: case 0x3d: case 0x3e: case 0x42: case 0x43:
            case 0x6f: case 0xa0: case 0xa4: case 0xa6: case 0xa7: case 0xe2:
                return true;
            default: return false;
        }
    }
}
