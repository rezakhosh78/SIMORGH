package ca.psiphon;

/* loaded from: classes.dex */
public class Tun2SocksJniLoader {
    static {
        System.loadLibrary("tun2socks");
    }

    private static native void initTun2socksLogger(String str, String str2);

    public static void initializeLogger(String str, String str2) {
        initTun2socksLogger(str.replace('.', '/'), str2);
    }

    public static native void runTun2Socks(int i2, int i3, String str, String str2, String str3, String str4, String str5, int i4);

    public static native void terminateTun2Socks();
}
