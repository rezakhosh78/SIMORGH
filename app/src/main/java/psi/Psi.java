package psi;

import go.Seq;

/* loaded from: classes.dex */
public abstract class Psi {
    public static final String CrashTracebackLevelAll = "all";
    public static final String CrashTracebackLevelSingle = "single";
    public static final String CrashTracebackLevelSystem = "system";

    private static final class proxyPsiphonProvider implements Seq.Proxy, PsiphonProvider {
        private final int refnum;

        proxyPsiphonProvider(int i2) {
            this.refnum = i2;
            Seq.trackGoRef(i2, this);
        }

        @Override // psi.PsiphonProvider
        public native String bindToDevice(long j2);

        @Override // psi.PsiphonProvider
        public native String getDNSServersAsString();

        @Override // psi.PsiphonProvider, psi.PsiphonProviderNetwork
        public native String getNetworkID();

        @Override // psi.PsiphonProvider, psi.PsiphonProviderNetwork
        public native long hasIPv6Route();

        @Override // psi.PsiphonProvider, psi.PsiphonProviderNetwork
        public native long hasNetworkConnectivity();

        @Override // psi.PsiphonProvider, psi.PsiphonProviderNetwork
        public native String iPv6Synthesize(String str);

        @Override // go.Seq.GoObject
        public final int incRefnum() {
            Seq.incGoRef(this.refnum, this);
            return this.refnum;
        }

        @Override // psi.PsiphonProvider, psi.PsiphonProviderNoticeHandler
        public native void notice(String str);
    }

    private static final class proxyPsiphonProviderFeedbackHandler implements Seq.Proxy, PsiphonProviderFeedbackHandler {
        private final int refnum;

        proxyPsiphonProviderFeedbackHandler(int i2) {
            this.refnum = i2;
            Seq.trackGoRef(i2, this);
        }

        @Override // go.Seq.GoObject
        public final int incRefnum() {
            Seq.incGoRef(this.refnum, this);
            return this.refnum;
        }

        @Override // psi.PsiphonProviderFeedbackHandler
        public native void sendFeedbackCompleted(Exception exc);
    }

    private static final class proxyPsiphonProviderNetwork implements Seq.Proxy, PsiphonProviderNetwork {
        private final int refnum;

        proxyPsiphonProviderNetwork(int i2) {
            this.refnum = i2;
            Seq.trackGoRef(i2, this);
        }

        @Override // psi.PsiphonProviderNetwork
        public native String getNetworkID();

        @Override // psi.PsiphonProviderNetwork
        public native long hasIPv6Route();

        @Override // psi.PsiphonProviderNetwork
        public native long hasNetworkConnectivity();

        @Override // psi.PsiphonProviderNetwork
        public native String iPv6Synthesize(String str);

        @Override // go.Seq.GoObject
        public final int incRefnum() {
            Seq.incGoRef(this.refnum, this);
            return this.refnum;
        }
    }

    private static final class proxyPsiphonProviderNoticeHandler implements Seq.Proxy, PsiphonProviderNoticeHandler {
        private final int refnum;

        proxyPsiphonProviderNoticeHandler(int i2) {
            this.refnum = i2;
            Seq.trackGoRef(i2, this);
        }

        @Override // go.Seq.GoObject
        public final int incRefnum() {
            Seq.incGoRef(this.refnum, this);
            return this.refnum;
        }

        @Override // psi.PsiphonProviderNoticeHandler
        public native void notice(String str);
    }

    static {
        Seq.touch();
        _init();
    }

    private Psi() {
    }

    private static native void _init();

    public static native void appResumed();

    public static native void configureCrashHandling(String str, String str2);

    public static native String exportExchangePayload();

    public static native String getBuildInfo();

    public static native long getPacketTunnelMTU();

    public static native String homepageFilePath(String str);

    public static native boolean importExchangePayload(String str);

    public static native boolean importPushPayload(byte[] bArr);

    public static native void networkChanged();

    public static native void noticeUserLog(String str);

    public static native String noticesFilePath(String str);

    public static native String oldNoticesFilePath(String str);

    public static native void reconnectTunnel();

    public static native void resetCrashHandling();

    public static native void setDynamicConfig(String str, String str2);

    public static native void start(String str, String str2, String str3, PsiphonProvider psiphonProvider, boolean z2, boolean z3, boolean z4);

    public static native void startSendFeedback(String str, String str2, String str3, PsiphonProviderFeedbackHandler psiphonProviderFeedbackHandler, PsiphonProviderNetwork psiphonProviderNetwork, PsiphonProviderNoticeHandler psiphonProviderNoticeHandler, boolean z2, boolean z3);

    public static native void stop();

    public static native void stopSendFeedback();

    public static void touch() {
    }

    public static native String upgradeDownloadFilePath(String str);

    public static native void writeRuntimeProfiles(String str, long j2, long j3);
}
