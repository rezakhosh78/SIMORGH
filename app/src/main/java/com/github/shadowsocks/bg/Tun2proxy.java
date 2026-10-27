package com.github.shadowsocks.bg;

/** JNI name and signature exported by the upstream tun2proxy Android library. */
public final class Tun2proxy {
    private static final boolean AVAILABLE;

    static {
        boolean loaded;
        try {
            System.loadLibrary("tun2proxy");
            loaded = true;
        } catch (UnsatisfiedLinkError error) {
            loaded = false;
        }
        AVAILABLE = loaded;
    }

    private Tun2proxy() {}

    public static boolean isAvailable() {
        return AVAILABLE;
    }

    public static native int run(String cliArgs, char tunMtu);
    public static native int stop();
}
