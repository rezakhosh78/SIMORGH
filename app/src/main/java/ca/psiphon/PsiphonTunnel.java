package ca.psiphon;

import android.content.Context;
import android.net.ConnectivityManager;
import android.net.LinkProperties;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.NetworkInfo;
import android.net.NetworkRequest;
import android.net.wifi.WifiInfo;
import android.net.wifi.WifiManager;
import android.os.Build;
import android.telephony.TelephonyManager;
import android.text.TextUtils;
import java.io.File;
import java.lang.reflect.InvocationTargetException;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.net.SocketException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import psi.Psi;
import psi.PsiphonProvider;
import psi.PsiphonProviderFeedbackHandler;
import psi.PsiphonProviderNetwork;
import psi.PsiphonProviderNoticeHandler;

/* loaded from: classes.dex */
public class PsiphonTunnel {
    private static final String EMPTY = "";
    private static PsiphonTunnel INSTANCE;
    private final AtomicReference<String> mActiveNetworkDNSServers;
    private final AtomicReference<String> mActiveNetworkType;
    private final AtomicReference<String> mClientPlatformPrefix;
    private final AtomicReference<String> mClientPlatformSuffix;
    private final HostService mHostService;
    private final AtomicBoolean mIsWaitingForNetworkConnectivity;
    private final AtomicInteger mLocalSocksProxyPort;
    private final NetworkMonitor mNetworkMonitor;
    private final AtomicBoolean mVpnMode;

    public static class Exception extends java.lang.Exception {
        private static final long serialVersionUID = 1;

        public Exception(String str) {
            super(str);
        }

        public Exception(String str, Throwable th) {
            super(str + ": " + th.getMessage());
        }
    }

    public interface HostFeedbackHandler {
        void sendFeedbackCompleted(java.lang.Exception exc);
    }

    public interface HostLibraryLoader {
        void loadLibrary(String str);
    }

    public interface HostLogger {
        void onDiagnosticMessage(String str);
    }

    public interface HostService extends HostLogger, HostLibraryLoader {
        void bindToDevice(long j2);

        Context getContext();

        String getPsiphonConfig();

        void onActiveAuthorizationIDs(List<String> list);

        void onApplicationParameters(Object obj);

        void onAvailableEgressRegions(List<String> list);

        void onBytesTransferred(long j2, long j3);

        void onClientAddress(String str);

        void onClientIsLatestVersion();

        void onClientRegion(String str);

        void onClientUpgradeDownloaded(String str);

        void onConnected();

        void onConnectedServerRegion(String str);

        void onConnecting();

        void onExiting();

        void onHomepage(String str);

        void onHttpProxyPortInUse(int i2);

        void onInproxyMustUpgrade();

        void onInproxyProxyActivity(int i2, int i3, int i4, long j2, long j3, Map<String, RegionActivitySnapshot> map, Map<String, RegionActivitySnapshot> map2);

        void onLightProxyAvailable();

        void onListeningHttpProxyPort(int i2);

        void onListeningSocksProxyPort(int i2);

        void onServerAlert(String str, String str2, List<String> list);

        void onSocksProxyPortInUse(int i2);

        void onSplitTunnelRegions(List<String> list);

        void onStartedWaitingForNetworkConnectivity();

        void onStoppedWaitingForNetworkConnectivity();

        void onTrafficRateLimits(long j2, long j3);

        void onUntunneledAddress(String str);

        void onUpstreamProxyError(String str);
    }

    private static class NetworkMonitor {
        private final NetworkChangeListener listener;
        private ConnectivityManager.NetworkCallback networkCallback;

        public interface NetworkChangeListener {
            void onChanged();
        }

        public NetworkMonitor(NetworkChangeListener networkChangeListener) {
            this.listener = networkChangeListener;
        }

        /* JADX INFO: Access modifiers changed from: private */
        public void start(Context context) throws InterruptedException {
            final ConnectivityManager connectivityManager;
            final CountDownLatch countDownLatch = new CountDownLatch(1);
            if (Build.VERSION.SDK_INT >= 21 && (connectivityManager = (ConnectivityManager) context.getSystemService("connectivity")) != null) {
                this.networkCallback = new ConnectivityManager.NetworkCallback() { // from class: ca.psiphon.PsiphonTunnel.NetworkMonitor.1
                    private Network currentActiveNetwork;
                    private boolean isInitialState = true;

                    private void consumeActiveNetwork(Network network) {
                        if (this.isInitialState) {
                            this.isInitialState = false;
                            setCurrentActiveNetworkAndProperties(network);
                        } else {
                            if (network.equals(this.currentActiveNetwork)) {
                                return;
                            }
                            setCurrentActiveNetworkAndProperties(network);
                            if (NetworkMonitor.this.listener != null) {
                                NetworkMonitor.this.listener.onChanged();
                            }
                        }
                    }

                    private void consumeLostNetwork(Network network) {
                        if (network.equals(this.currentActiveNetwork)) {
                            setCurrentActiveNetworkAndProperties(null);
                            if (NetworkMonitor.this.listener != null) {
                                NetworkMonitor.this.listener.onChanged();
                            }
                        }
                    }

                    private void setCurrentActiveNetworkAndProperties(Network network) {
                        this.currentActiveNetwork = network;
                        if (network == null) {
                            PsiphonTunnel.INSTANCE.mActiveNetworkType.set("NONE");
                            PsiphonTunnel.INSTANCE.mActiveNetworkDNSServers.set(EMPTY);
                            PsiphonTunnel.INSTANCE.mHostService.onDiagnosticMessage("NetworkMonitor: clear current active network");
                        } else {
                            String str = "UNKNOWN";
                            try {
                                NetworkCapabilities networkCapabilities = connectivityManager.getNetworkCapabilities(network);
                                if (networkCapabilities != null) {
                                    if (networkCapabilities.hasTransport(4)) {
                                        str = "VPN";
                                    } else if (networkCapabilities.hasTransport(0)) {
                                        str = "MOBILE";
                                    } else if (networkCapabilities.hasTransport(1)) {
                                        str = "WIFI";
                                    }
                                }
                            } catch (java.lang.Exception unused) {
                            }
                            PsiphonTunnel.INSTANCE.mActiveNetworkType.set(str);
                            ArrayList arrayList = new ArrayList();
                            try {
                                LinkProperties linkProperties = connectivityManager.getLinkProperties(network);
                                if (linkProperties != null) {
                                    Iterator it = linkProperties.getDnsServers().iterator();
                                    while (it.hasNext()) {
                                        PsiphonTunnel.addUsableDNSServer(arrayList, (InetAddress) it.next(), linkProperties.getInterfaceName());
                                    }
                                }
                            } catch (java.lang.Exception unused2) {
                            }
                            PsiphonTunnel.INSTANCE.mActiveNetworkDNSServers.set(TextUtils.join(",", arrayList));
                            String str2 = "NetworkMonitor: set current active network " + str;
                            if (!arrayList.isEmpty()) {
                                str2 = str2 + " with DNS";
                            }
                            PsiphonTunnel.INSTANCE.mHostService.onDiagnosticMessage(str2);
                        }
                        countDownLatch.countDown();
                    }

                    @Override // android.net.ConnectivityManager.NetworkCallback
                    public void onAvailable(Network network) {
                        super.onAvailable(network);
                        if (Build.VERSION.SDK_INT >= 26) {
                            return;
                        }
                        consumeActiveNetwork(network);
                    }

                    @Override // android.net.ConnectivityManager.NetworkCallback
                    public void onCapabilitiesChanged(Network network, NetworkCapabilities networkCapabilities) {
                        super.onCapabilitiesChanged(network, networkCapabilities);
                        if (Build.VERSION.SDK_INT >= 23 && networkCapabilities.hasCapability(16)) {
                            consumeActiveNetwork(network);
                        }
                    }

                    @Override // android.net.ConnectivityManager.NetworkCallback
                    public void onLost(Network network) {
                        super.onLost(network);
                        consumeLostNetwork(network);
                    }
                };
                try {
                    NetworkRequest.Builder builderAddCapability = new NetworkRequest.Builder().addCapability(12);
                    if (PsiphonTunnel.INSTANCE.mVpnMode.get()) {
                        builderAddCapability.addCapability(15);
                    } else {
                        builderAddCapability.removeCapability(15);
                    }
                    connectivityManager.requestNetwork(builderAddCapability.build(), this.networkCallback);
                } catch (RuntimeException unused) {
                    this.networkCallback = null;
                }
                countDownLatch.await(1L, TimeUnit.SECONDS);
            }
        }

        /* JADX INFO: Access modifiers changed from: private */
        public void stop(Context context) {
            ConnectivityManager connectivityManager;
            if (this.networkCallback == null || Build.VERSION.SDK_INT < 21 || (connectivityManager = (ConnectivityManager) context.getSystemService("connectivity")) == null) {
                return;
            }
            try {
                connectivityManager.unregisterNetworkCallback(this.networkCallback);
            } catch (RuntimeException unused) {
            }
            this.networkCallback = null;
        }
    }

    private class PsiphonProviderShim implements PsiphonProvider {
        private final PsiphonTunnel mPsiphonTunnel;

        public PsiphonProviderShim(PsiphonTunnel psiphonTunnel) {
            this.mPsiphonTunnel = psiphonTunnel;
        }

        @Override // psi.PsiphonProvider
        public String bindToDevice(long j2) {
            return this.mPsiphonTunnel.bindToDevice(j2);
        }

        @Override // psi.PsiphonProvider
        public String getDNSServersAsString() {
            return this.mPsiphonTunnel.getDNSServers(PsiphonTunnel.this.mHostService.getContext(), PsiphonTunnel.this.mHostService);
        }

        @Override // psi.PsiphonProvider, psi.PsiphonProviderNetwork
        public String getNetworkID() {
            return PsiphonTunnel.getNetworkID(PsiphonTunnel.this.mHostService.getContext(), this.mPsiphonTunnel.isVpnMode());
        }

        @Override // psi.PsiphonProvider, psi.PsiphonProviderNetwork
        public long hasIPv6Route() {
            return PsiphonTunnel.hasIPv6Route(PsiphonTunnel.this.mHostService.getContext(), PsiphonTunnel.this.mHostService);
        }

        @Override // psi.PsiphonProvider, psi.PsiphonProviderNetwork
        public long hasNetworkConnectivity() {
            return this.mPsiphonTunnel.hasNetworkConnectivity();
        }

        @Override // psi.PsiphonProvider, psi.PsiphonProviderNetwork
        public String iPv6Synthesize(String str) {
            return PsiphonTunnel.iPv6Synthesize(str);
        }

        @Override // psi.PsiphonProvider, psi.PsiphonProviderNoticeHandler
        public void notice(String str) {
            try {
                this.mPsiphonTunnel.notice(str);
            } catch (JSONException e2) {
                PsiphonTunnel.this.mHostService.onDiagnosticMessage("Error handling notice " + e2);
            }
        }
    }

    public static class PsiphonTunnelFeedback {
        private final ExecutorService workQueue = Executors.newSingleThreadExecutor();
        private final ExecutorService callbackQueue = Executors.newSingleThreadExecutor();

        /* renamed from: ca.psiphon.PsiphonTunnel$PsiphonTunnelFeedback$1, reason: invalid class name */
        class AnonymousClass1 implements Runnable {
            final /* synthetic */ String val$clientPlatformPrefix;
            final /* synthetic */ String val$clientPlatformSuffix;
            final /* synthetic */ Context val$context;
            final /* synthetic */ String val$diagnosticsJson;
            final /* synthetic */ String val$feedbackConfigJson;
            final /* synthetic */ HostFeedbackHandler val$feedbackHandler;
            final /* synthetic */ HostLogger val$logger;
            final /* synthetic */ String val$uploadPath;

            AnonymousClass1(Context context, String str, String str2, String str3, HostLogger hostLogger, String str4, String str5, HostFeedbackHandler hostFeedbackHandler) {
                this.val$context = context;
                this.val$feedbackConfigJson = str;
                this.val$clientPlatformPrefix = str2;
                this.val$clientPlatformSuffix = str3;
                this.val$logger = hostLogger;
                this.val$diagnosticsJson = str4;
                this.val$uploadPath = str5;
                this.val$feedbackHandler = hostFeedbackHandler;
            }

            @Override // java.lang.Runnable
            public void run() {
                try {
                    Psi.startSendFeedback(PsiphonTunnel.buildPsiphonConfig(this.val$context, this.val$feedbackConfigJson, this.val$clientPlatformPrefix, this.val$clientPlatformSuffix, 0), this.val$diagnosticsJson, this.val$uploadPath, new PsiphonProviderFeedbackHandler() { // from class: ca.psiphon.PsiphonTunnel.PsiphonTunnelFeedback.1.2
                        @Override // psi.PsiphonProviderFeedbackHandler
                        public void sendFeedbackCompleted(final java.lang.Exception exc) {
                            try {
                                PsiphonTunnelFeedback.this.callbackQueue.execute(new Runnable() { // from class: ca.psiphon.PsiphonTunnel.PsiphonTunnelFeedback.1.2.1
                                    @Override // java.lang.Runnable
                                    public void run() {
                                        AnonymousClass1.this.val$feedbackHandler.sendFeedbackCompleted(exc);
                                    }
                                });
                            } catch (RejectedExecutionException unused) {
                            }
                        }
                    }, new PsiphonProviderNetwork() { // from class: ca.psiphon.PsiphonTunnel.PsiphonTunnelFeedback.1.3
                        @Override // psi.PsiphonProviderNetwork
                        public String getNetworkID() {
                            return PsiphonTunnel.getNetworkID(AnonymousClass1.this.val$context, false);
                        }

                        @Override // psi.PsiphonProviderNetwork
                        public long hasIPv6Route() {
                            AnonymousClass1 anonymousClass1 = AnonymousClass1.this;
                            return PsiphonTunnel.hasIPv6Route(anonymousClass1.val$context, anonymousClass1.val$logger);
                        }

                        @Override // psi.PsiphonProviderNetwork
                        public long hasNetworkConnectivity() {
                            return PsiphonTunnel.hasNetworkConnectivity(AnonymousClass1.this.val$context) ? 1L : 0L;
                        }

                        @Override // psi.PsiphonProviderNetwork
                        public String iPv6Synthesize(String str) {
                            return PsiphonTunnel.iPv6Synthesize(str);
                        }
                    }, this.val$logger != null ? new PsiphonProviderNoticeHandler() { // from class: ca.psiphon.PsiphonTunnel.PsiphonTunnelFeedback.1.1
                        @Override // psi.PsiphonProviderNoticeHandler
                        public void notice(String str) {
                            try {
                                try {
                                    JSONObject jSONObject = new JSONObject(str);
                                    final String str2 = jSONObject.getString("noticeType") + ": " + jSONObject.getJSONObject("data");
                                    PsiphonTunnelFeedback.this.callbackQueue.execute(new Runnable() { // from class: ca.psiphon.PsiphonTunnel.PsiphonTunnelFeedback.1.1.1
                                        @Override // java.lang.Runnable
                                        public void run() {
                                            AnonymousClass1.this.val$logger.onDiagnosticMessage(str2);
                                        }
                                    });
                                } catch (java.lang.Exception e2) {
                                    PsiphonTunnelFeedback.this.callbackQueue.execute(new Runnable() { // from class: ca.psiphon.PsiphonTunnel.PsiphonTunnelFeedback.1.1.2
                                        @Override // java.lang.Runnable
                                        public void run() {
                                            AnonymousClass1.this.val$logger.onDiagnosticMessage("Error handling notice " + e2);
                                        }
                                    });
                                }
                            } catch (RejectedExecutionException unused) {
                            }
                        }
                    } : null, false, true);
                } catch (java.lang.Exception e2) {
                    try {
                        PsiphonTunnelFeedback.this.callbackQueue.execute(new Runnable() { // from class: ca.psiphon.PsiphonTunnel.PsiphonTunnelFeedback.1.4
                            @Override // java.lang.Runnable
                            public void run() {
                                AnonymousClass1.this.val$feedbackHandler.sendFeedbackCompleted(new Exception("Error sending feedback", e2));
                            }
                        });
                    } catch (RejectedExecutionException unused) {
                    }
                }
            }
        }

        public void shutdown() {
            this.workQueue.execute(new Runnable() { // from class: ca.psiphon.PsiphonTunnel.PsiphonTunnelFeedback.2
                @Override // java.lang.Runnable
                public void run() {
                    Psi.stopSendFeedback();
                }
            });
            shutdownAndAwaitTermination(this.workQueue);
            shutdownAndAwaitTermination(this.callbackQueue);
        }

        void shutdownAndAwaitTermination(ExecutorService executorService) {
            executorService.shutdown();
            try {
                TimeUnit timeUnit = TimeUnit.SECONDS;
                if (executorService.awaitTermination(5L, timeUnit)) {
                    return;
                }
                executorService.shutdownNow();
                if (executorService.awaitTermination(5L, timeUnit)) {
                    return;
                }
                System.err.println("PsiphonTunnelFeedback: pool did not terminate");
            } catch (InterruptedException unused) {
                executorService.shutdownNow();
                Thread.currentThread().interrupt();
            }
        }

        public void startSendFeedback(Context context, HostFeedbackHandler hostFeedbackHandler, HostLogger hostLogger, String str, String str2, String str3, String str4, String str5) {
            this.workQueue.execute(new AnonymousClass1(context, str, str4, str5, hostLogger, str2, str3, hostFeedbackHandler));
        }
    }

    public static class RegionActivitySnapshot {
        public long bytesDown;
        public long bytesUp;
        public int connectedClients;
        public int connectingClients;
    }

    private PsiphonTunnel(HostService hostService) {
        hostService.loadLibrary("gojni");
        this.mHostService = hostService;
        this.mVpnMode = new AtomicBoolean(false);
        this.mLocalSocksProxyPort = new AtomicInteger(0);
        this.mIsWaitingForNetworkConnectivity = new AtomicBoolean(false);
        this.mClientPlatformPrefix = new AtomicReference<>(EMPTY);
        this.mClientPlatformSuffix = new AtomicReference<>(EMPTY);
        this.mActiveNetworkType = new AtomicReference<>(EMPTY);
        this.mActiveNetworkDNSServers = new AtomicReference<>(EMPTY);
        this.mNetworkMonitor = new NetworkMonitor(new NetworkMonitor.NetworkChangeListener() { // from class: ca.psiphon.PsiphonTunnel.1
            @Override // ca.psiphon.PsiphonTunnel.NetworkMonitor.NetworkChangeListener
            public void onChanged() {
                Psi.networkChanged();
            }
        });
    }

    /* JADX INFO: Access modifiers changed from: private */
    public static void addUsableDNSServer(Collection<String> collection, InetAddress inetAddress, String str) {
        if (inetAddress == null || inetAddress.isAnyLocalAddress() || inetAddress.isMulticastAddress()) {
            return;
        }
        String hostAddress = inetAddress.getHostAddress();
        if (inetAddress.isLinkLocalAddress()) {
            if (!(inetAddress instanceof Inet6Address)) {
                return;
            }
            if (!hostAddress.contains("%")) {
                if (TextUtils.isEmpty(str)) {
                    return;
                }
                hostAddress = hostAddress + "%" + str;
            }
        }
        if (collection.contains(hostAddress)) {
            return;
        }
        collection.add(hostAddress);
    }

    /* JADX INFO: Access modifiers changed from: private */
    public String bindToDevice(long j2) {
        this.mHostService.bindToDevice(j2);
        return EMPTY;
    }

    /* JADX INFO: Access modifiers changed from: private */
    public static String buildPsiphonConfig(Context context, String str, String str2, String str3, Integer num) throws Exception, JSONException {
        JSONObject jSONObject = new JSONObject(str);
        if (!jSONObject.has("DataRootDirectory")) {
            File fileDefaultDataRootDirectory = defaultDataRootDirectory(context);
            if (!fileDefaultDataRootDirectory.exists() && !fileDefaultDataRootDirectory.mkdir()) {
                throw new Exception("failed to create data root directory: " + fileDefaultDataRootDirectory.getPath());
            }
            jSONObject.put("DataRootDirectory", defaultDataRootDirectory(context));
        }
        if (!jSONObject.has("DataStoreDirectory")) {
            jSONObject.put("MigrateDataStoreDirectory", context.getFilesDir());
        }
        if (!jSONObject.has("RemoteServerListDownloadFilename")) {
            jSONObject.put("MigrateRemoteServerListDownloadFilename", new File(context.getFilesDir(), "remote_server_list").getAbsolutePath());
        }
        jSONObject.put("MigrateObfuscatedServerListDownloadDirectory", new File(context.getFilesDir(), "osl").getAbsolutePath());
        if (!jSONObject.has("EstablishTunnelTimeoutSeconds")) {
            jSONObject.put("EstablishTunnelTimeoutSeconds", 0);
        }
        if (num.intValue() != 0 && (!jSONObject.has("LocalSocksProxyPort") || jSONObject.getInt("LocalSocksProxyPort") == 0)) {
            jSONObject.put("LocalSocksProxyPort", num);
        }
        jSONObject.put("DeviceRegion", getDeviceRegion(context));
        StringBuilder sb = new StringBuilder();
        if (str2.length() > 0) {
            sb.append(str2);
        }
        sb.append("Android_");
        sb.append(Build.VERSION.RELEASE);
        sb.append("_");
        sb.append(context.getPackageName());
        if (str3.length() > 0) {
            sb.append(str3);
        }
        jSONObject.put("ClientPlatform", sb.toString().replaceAll("[^\\w\\-\\.]", "_"));
        jSONObject.put("ClientAPILevel", Build.VERSION.SDK_INT);
        return jSONObject.toString();
    }

    private static File defaultDataRootDirectory(Context context) {
        return context.getFileStreamPath("ca.psiphon.PsiphonTunnel.tunnel-core");
    }

    private static Collection<InetAddress> getActiveNetworkDNSServerAddresses(Context context, boolean z2) throws Exception {
        ArrayList arrayList = new ArrayList();
        ConnectivityManager connectivityManager = (ConnectivityManager) context.getSystemService("connectivity");
        if (connectivityManager == null) {
            throw new Exception("getActiveNetworkDNSServerAddresses failed", new Throwable("couldn't get ConnectivityManager system service"));
        }
        try {
            Class<?> cls = Class.forName("android.net.LinkProperties");
            Object objInvoke = ConnectivityManager.class.getMethod("getActiveLinkProperties").invoke(connectivityManager);
            if (objInvoke != null) {
                if (Build.VERSION.SDK_INT < 21) {
                    Collection collection = (Collection) cls.getMethod("getDnses").invoke(objInvoke);
                    if (collection != null) {
                        Iterator it = collection.iterator();
                        while (it.hasNext()) {
                            arrayList.add((InetAddress) it.next());
                        }
                    }
                } else {
                    List dnsServers = a.a(objInvoke).getDnsServers();
                    if (dnsServers != null) {
                        arrayList.addAll(dnsServers);
                    }
                }
            }
        } catch (ClassNotFoundException | IllegalAccessException | IllegalArgumentException | NoSuchMethodException | NullPointerException | InvocationTargetException unused) {
        }
        if (arrayList.isEmpty() && Build.VERSION.SDK_INT >= 21) {
            NetworkRequest.Builder builderAddCapability = new NetworkRequest.Builder().addCapability(12);
            if (z2) {
                builderAddCapability.addCapability(15);
            }
            NetworkRequest networkRequestBuild = builderAddCapability.build();
            final ArrayList arrayList2 = new ArrayList();
            final CountDownLatch countDownLatch = new CountDownLatch(1);
            try {
                ConnectivityManager.NetworkCallback networkCallback = new ConnectivityManager.NetworkCallback() { // from class: ca.psiphon.PsiphonTunnel.2
                    @Override // android.net.ConnectivityManager.NetworkCallback
                    public void onLinkPropertiesChanged(Network network, LinkProperties linkProperties) {
                        if (linkProperties != null) {
                            synchronized (arrayList2) {
                                try {
                                    List dnsServers2 = linkProperties.getDnsServers();
                                    if (dnsServers2 != null) {
                                        arrayList2.addAll(dnsServers2);
                                    }
                                } finally {
                                }
                            }
                        }
                        countDownLatch.countDown();
                    }
                };
                connectivityManager.registerNetworkCallback(networkRequestBuild, networkCallback);
                countDownLatch.await(1L, TimeUnit.SECONDS);
                connectivityManager.unregisterNetworkCallback(networkCallback);
            } catch (InterruptedException unused2) {
                Thread.currentThread().interrupt();
            } catch (RuntimeException unused3) {
            }
            synchronized (arrayList2) {
                arrayList.addAll(arrayList2);
            }
        }
        return arrayList;
    }

    private static Collection<String> getActiveNetworkDNSServers(Context context, boolean z2) throws Exception {
        ArrayList arrayList = new ArrayList();
        Iterator<InetAddress> it = getActiveNetworkDNSServerAddresses(context, z2).iterator();
        while (it.hasNext()) {
            addUsableDNSServer(arrayList, it.next(), null);
        }
        if (arrayList.isEmpty()) {
            throw new Exception("no active network DNS resolver");
        }
        return arrayList;
    }

    /* JADX INFO: Access modifiers changed from: private */
    public String getDNSServers(Context context, HostLogger hostLogger) {
        String str = this.mActiveNetworkDNSServers.get();
        if (!TextUtils.isEmpty(str)) {
            return str;
        }
        try {
            return TextUtils.join(",", getActiveNetworkDNSServers(context, this.mVpnMode.get()));
        } catch (Exception e2) {
            hostLogger.onDiagnosticMessage("failed to get active network DNS resolver: " + e2.getMessage());
            return str;
        }
    }

    public static String getDefaultUpgradeDownloadFilePath(Context context) {
        return Psi.upgradeDownloadFilePath(defaultDataRootDirectory(context).getAbsolutePath());
    }

    private static String getDeviceRegion(Context context) {
        Locale locale;
        String networkCountryIso;
        TelephonyManager telephonyManager = (TelephonyManager) context.getSystemService("phone");
        String country = EMPTY;
        if (telephonyManager != null) {
            if (telephonyManager.getPhoneType() == 2 || (networkCountryIso = telephonyManager.getNetworkCountryIso()) == null) {
                networkCountryIso = EMPTY;
            }
            if (networkCountryIso.length() == 0) {
                String simCountryIso = telephonyManager.getSimCountryIso();
                if (simCountryIso != null) {
                    country = simCountryIso;
                }
            } else {
                country = networkCountryIso;
            }
        }
        if (country.length() == 0 && (locale = Locale.getDefault()) != null) {
            country = locale.getCountry();
        }
        return country.toUpperCase(Locale.US);
    }

    /* JADX INFO: Access modifiers changed from: private */
    /* JADX WARN: Type inference failed for: r0v2, types: [android.net.ConnectivityManager, java.lang.String] */
    public static String getNetworkID(Context context, boolean z2) {
        NetworkCapabilities networkCapabilities;
        ConnectivityManager connectivityManager =
                (ConnectivityManager) context.getSystemService("connectivity");
        NetworkInfo activeNetworkInfo = null;
        if (connectivityManager == null) {
            return "UNKNOWN";
        }
        if (Build.VERSION.SDK_INT >= 23 && !z2) {
            try {
                networkCapabilities =
                        connectivityManager.getNetworkCapabilities(connectivityManager.getActiveNetwork());
            } catch (java.lang.Exception unused) {
                networkCapabilities = null;
            }
            if (networkCapabilities != null && networkCapabilities.hasTransport(4)) {
                return "VPN";
            }
        }
        try {
            activeNetworkInfo = connectivityManager.getActiveNetworkInfo();
        } catch (java.lang.Exception unused2) {
        }
        try {
            if (activeNetworkInfo == null || activeNetworkInfo.getType() != 1) {
                if (activeNetworkInfo == null || activeNetworkInfo.getType() != 0) {
                    return "UNKNOWN";
                }
                TelephonyManager telephonyManager = (TelephonyManager) context.getSystemService("phone");
                if (telephonyManager == null) {
                    return "MOBILE";
                }
                return "MOBILE-" + telephonyManager.getNetworkOperator();
            }
            WifiInfo connectionInfo = ((WifiManager) context.getApplicationContext().getSystemService("wifi")).getConnectionInfo();
            if (connectionInfo == null) {
                return "WIFI";
            }
            String bssid = connectionInfo.getBSSID();
            if (bssid == null) {
                bssid = "NOT_CONNECTED";
            } else if (bssid.equals("02:00:00:00:00:00")) {
                bssid = String.valueOf(connectionInfo.getIpAddress());
            }
            return "WIFI-" + bssid;
        } catch (java.lang.Exception unused3) {
            return "UNKNOWN";
        }
    }

    public static String getUpgradeDownloadFilePath(String str) {
        return Psi.upgradeDownloadFilePath(str);
    }

    private void handlePsiphonNotice(String str) throws JSONException {
        try {
            JSONObject jSONObject = new JSONObject(str);
            String string = jSONObject.getString("noticeType");
            if (string.equals("Tunnels")) {
                int i2 = jSONObject.getJSONObject("data").getInt("count");
                if (i2 == 0) {
                    this.mHostService.onConnecting();
                } else if (i2 == 1) {
                    this.mHostService.onConnected();
                }
            } else {
                int i3 = 0;
                if (string.equals("AvailableEgressRegions")) {
                    JSONArray jSONArray = jSONObject.getJSONObject("data").getJSONArray("regions");
                    ArrayList arrayList = new ArrayList();
                    while (i3 < jSONArray.length()) {
                        arrayList.add(jSONArray.getString(i3));
                        i3++;
                    }
                    this.mHostService.onAvailableEgressRegions(arrayList);
                } else if (string.equals("SocksProxyPortInUse")) {
                    this.mHostService.onSocksProxyPortInUse(jSONObject.getJSONObject("data").getInt("port"));
                } else if (string.equals("HttpProxyPortInUse")) {
                    this.mHostService.onHttpProxyPortInUse(jSONObject.getJSONObject("data").getInt("port"));
                } else if (string.equals("ListeningSocksProxyPort")) {
                    int i4 = jSONObject.getJSONObject("data").getInt("port");
                    setLocalSocksProxyPort(i4);
                    this.mHostService.onListeningSocksProxyPort(i4);
                } else if (string.equals("ListeningHttpProxyPort")) {
                    this.mHostService.onListeningHttpProxyPort(jSONObject.getJSONObject("data").getInt("port"));
                } else {
                    if (string.equals("UpstreamProxyError")) {
                        this.mHostService.onUpstreamProxyError(jSONObject.getJSONObject("data").getString("message"));
                        return;
                    }
                    if (string.equals("ClientUpgradeDownloaded")) {
                        this.mHostService.onClientUpgradeDownloaded(jSONObject.getJSONObject("data").getString("filename"));
                    } else if (string.equals("ClientIsLatestVersion")) {
                        this.mHostService.onClientIsLatestVersion();
                    } else if (string.equals("Homepage")) {
                        this.mHostService.onHomepage(jSONObject.getJSONObject("data").getString("url"));
                    } else if (string.equals("ClientRegion")) {
                        this.mHostService.onClientRegion(jSONObject.getJSONObject("data").getString("region"));
                    } else {
                        if (string.equals("ClientAddress")) {
                            this.mHostService.onClientAddress(jSONObject.getJSONObject("data").getString("address"));
                            return;
                        }
                        if (string.equals("SplitTunnelRegions")) {
                            JSONArray jSONArray2 = jSONObject.getJSONObject("data").getJSONArray("regions");
                            ArrayList arrayList2 = new ArrayList();
                            while (i3 < jSONArray2.length()) {
                                arrayList2.add(jSONArray2.getString(i3));
                                i3++;
                            }
                            this.mHostService.onSplitTunnelRegions(arrayList2);
                        } else {
                            if (string.equals("Untunneled")) {
                                this.mHostService.onUntunneledAddress(jSONObject.getJSONObject("data").getString("address"));
                                return;
                            }
                            if (string.equals("BytesTransferred")) {
                                JSONObject jSONObject2 = jSONObject.getJSONObject("data");
                                this.mHostService.onBytesTransferred(jSONObject2.getLong("sent"), jSONObject2.getLong("received"));
                                return;
                            }
                            if (string.equals("ActiveAuthorizationIDs")) {
                                JSONArray jSONArray3 = jSONObject.getJSONObject("data").getJSONArray("IDs");
                                ArrayList arrayList3 = new ArrayList();
                                while (i3 < jSONArray3.length()) {
                                    arrayList3.add(jSONArray3.getString(i3));
                                    i3++;
                                }
                                this.mHostService.onActiveAuthorizationIDs(arrayList3);
                            } else if (string.equals("TrafficRateLimits")) {
                                JSONObject jSONObject3 = jSONObject.getJSONObject("data");
                                this.mHostService.onTrafficRateLimits(jSONObject3.getLong("upstreamBytesPerSecond"), jSONObject3.getLong("downstreamBytesPerSecond"));
                            } else if (string.equals("Exiting")) {
                                this.mHostService.onExiting();
                            } else if (string.equals("ConnectedServerRegion")) {
                                this.mHostService.onConnectedServerRegion(jSONObject.getJSONObject("data").getString("serverRegion"));
                            } else if (string.equals("ApplicationParameters")) {
                                this.mHostService.onApplicationParameters(jSONObject.getJSONObject("data").get("parameters"));
                            } else if (string.equals("ServerAlert")) {
                                JSONArray jSONArray4 = jSONObject.getJSONObject("data").getJSONArray("actionURLs");
                                ArrayList arrayList4 = new ArrayList();
                                while (i3 < jSONArray4.length()) {
                                    arrayList4.add(jSONArray4.getString(i3));
                                    i3++;
                                }
                                this.mHostService.onServerAlert(jSONObject.getJSONObject("data").getString("reason"), jSONObject.getJSONObject("data").getString("subject"), arrayList4);
                            } else if (string.equals("InproxyMustUpgrade")) {
                                this.mHostService.onInproxyMustUpgrade();
                            } else if (string.equals("InproxyProxyActivity")) {
                                JSONObject jSONObject4 = jSONObject.getJSONObject("data");
                                this.mHostService.onInproxyProxyActivity(jSONObject4.getInt("announcing"), jSONObject4.getInt("connectingClients"), jSONObject4.getInt("connectedClients"), jSONObject4.getLong("bytesUp"), jSONObject4.getLong("bytesDown"), parseRegionActivity(jSONObject4.getJSONObject("personalRegionActivity")), parseRegionActivity(jSONObject4.getJSONObject("commonRegionActivity")));
                            } else if (string.equals("LightProxyAvailable")) {
                                this.mHostService.onLightProxyAvailable();
                            }
                        }
                    }
                }
            }
            this.mHostService.onDiagnosticMessage(string + ": " + jSONObject.getJSONObject("data"));
        } catch (JSONException unused) {
        }
    }

    /* JADX INFO: Access modifiers changed from: private */
    public static long hasIPv6Route(Context context, HostLogger hostLogger) {
        boolean zHasIPv6Route;
        try {
            zHasIPv6Route = hasIPv6Route(context);
        } catch (Exception e2) {
            if (hostLogger != null) {
                hostLogger.onDiagnosticMessage("failed to check IPv6 route: " + e2.getMessage());
            }
            zHasIPv6Route = false;
        }
        return zHasIPv6Route ? 1L : 0L;
    }

    /* JADX INFO: Access modifiers changed from: private */
    public long hasNetworkConnectivity() {
        boolean zHasNetworkConnectivity = hasNetworkConnectivity(this.mHostService.getContext());
        boolean andSet = this.mIsWaitingForNetworkConnectivity.getAndSet(!zHasNetworkConnectivity);
        if (!zHasNetworkConnectivity && !andSet) {
            this.mHostService.onStartedWaitingForNetworkConnectivity();
        } else if (zHasNetworkConnectivity && andSet) {
            this.mHostService.onStoppedWaitingForNetworkConnectivity();
        }
        return zHasNetworkConnectivity ? 1L : 0L;
    }

    /* JADX INFO: Access modifiers changed from: private */
    public static String iPv6Synthesize(String str) {
        return str;
    }

    /* JADX INFO: Access modifiers changed from: private */
    public boolean isVpnMode() {
        return this.mVpnMode.get();
    }

    private String loadPsiphonConfig(Context context) throws java.lang.Exception {
        return buildPsiphonConfig(context, this.mHostService.getPsiphonConfig(), this.mClientPlatformPrefix.get(), this.mClientPlatformSuffix.get(), Integer.valueOf(this.mLocalSocksProxyPort.get()));
    }

    public static synchronized PsiphonTunnel newPsiphonTunnel(HostService hostService) {
        PsiphonTunnel psiphonTunnel;
        try {
            PsiphonTunnel psiphonTunnel2 = INSTANCE;
            if (psiphonTunnel2 != null) {
                psiphonTunnel2.stop();
            }
            psiphonTunnel = new PsiphonTunnel(hostService);
            INSTANCE = psiphonTunnel;
        } catch (Throwable th) {
            throw th;
        }
        return psiphonTunnel;
    }

    /* JADX INFO: Access modifiers changed from: private */
    public void notice(String str) throws JSONException {
        handlePsiphonNotice(str);
    }

    private static Map<String, RegionActivitySnapshot> parseRegionActivity(JSONObject jSONObject) throws JSONException {
        if (jSONObject == null) {
            return Collections.emptyMap();
        }
        HashMap map = new HashMap();
        Iterator<String> itKeys = jSONObject.keys();
        while (itKeys.hasNext()) {
            String next = itKeys.next();
            JSONObject jSONObject2 = jSONObject.getJSONObject(next);
            RegionActivitySnapshot regionActivitySnapshot = new RegionActivitySnapshot();
            regionActivitySnapshot.bytesUp = jSONObject2.getLong("bytesUp");
            regionActivitySnapshot.bytesDown = jSONObject2.getLong("bytesDown");
            regionActivitySnapshot.connectingClients = jSONObject2.getInt("connectingClients");
            regionActivitySnapshot.connectedClients = jSONObject2.getInt("connectedClients");
            map.put(next, regionActivitySnapshot);
        }
        return map;
    }

    private void setLocalSocksProxyPort(int i2) {
        this.mLocalSocksProxyPort.set(i2);
    }

    private void startPsiphon(String str) throws Exception {
        stopPsiphon();
        this.mIsWaitingForNetworkConnectivity.set(false);
        this.mHostService.onDiagnosticMessage("starting Psiphon library");
        try {
            this.mNetworkMonitor.start(this.mHostService.getContext());
            Psi.start(loadPsiphonConfig(this.mHostService.getContext()), str, EMPTY, new PsiphonProviderShim(this), isVpnMode(), false, true);
            this.mHostService.onDiagnosticMessage("Psiphon library started");
        } catch (java.lang.Exception e2) {
            throw new Exception("failed to start Psiphon library", e2);
        }
    }

    private void stopPsiphon() {
        this.mHostService.onDiagnosticMessage("stopping Psiphon library");
        this.mNetworkMonitor.stop(this.mHostService.getContext());
        Psi.stop();
        this.mHostService.onDiagnosticMessage("Psiphon library stopped");
    }

    public synchronized void appResumed() {
        Psi.appResumed();
    }

    public Object clone() throws CloneNotSupportedException {
        throw new CloneNotSupportedException();
    }

    public String exportExchangePayload() {
        return Psi.exportExchangePayload();
    }

    public int getLocalSocksProxyPort() {
        return this.mLocalSocksProxyPort.get();
    }

    public boolean importExchangePayload(String str) {
        return Psi.importExchangePayload(str);
    }

    public boolean importPushPayload(byte[] bArr) {
        return Psi.importPushPayload(bArr);
    }

    public synchronized void reconnectPsiphon() {
        Psi.reconnectTunnel();
    }

    public synchronized void restartPsiphon() {
        stopPsiphon();
        try {
            startPsiphon(EMPTY);
        } catch (Exception e2) {
            this.mHostService.onDiagnosticMessage("failed to restart Psiphon: " + e2.getMessage());
        }
    }

    public void setClientPlatformAffixes(String str, String str2) {
        this.mClientPlatformPrefix.set(str);
        this.mClientPlatformSuffix.set(str2);
    }

    public void setVpnMode(boolean z2) {
        this.mVpnMode.set(z2);
    }

    public synchronized void startTunneling(String str) throws Exception {
        startPsiphon(str);
    }

    public synchronized void stop() {
        stopPsiphon();
        this.mVpnMode.set(false);
        this.mLocalSocksProxyPort.set(0);
    }

    public void writeRuntimeProfiles(String str, int i2, int i3) {
        Psi.writeRuntimeProfiles(str, i2, i3);
    }

    private static boolean hasIPv6Route(Context context) throws Exception {
        try {
            Enumeration<NetworkInterface> networkInterfaces = NetworkInterface.getNetworkInterfaces();
            if (networkInterfaces == null) {
                throw new IllegalStateException("no network interfaces found");
            }
            Iterator it = Collections.list(networkInterfaces).iterator();
            while (it.hasNext()) {
                NetworkInterface networkInterface = (NetworkInterface) it.next();
                if (networkInterface.isUp() && !networkInterface.isLoopback()) {
                    Enumeration<InetAddress> inetAddresses = networkInterface.getInetAddresses();
                    if (inetAddresses == null) {
                        throw new IllegalStateException("no addresses found for network interface " + networkInterface.getName());
                    }
                    Iterator it2 = Collections.list(inetAddresses).iterator();
                    while (it2.hasNext()) {
                        InetAddress inetAddress = (InetAddress) it2.next();
                        if ((inetAddress instanceof Inet6Address) && !inetAddress.isLinkLocalAddress() && !inetAddress.isSiteLocalAddress() && !inetAddress.isMulticastAddress()) {
                            return true;
                        }
                    }
                }
            }
            return false;
        } catch (IllegalStateException e2) {
            throw new Exception("hasIPv6Route failed", e2);
        } catch (NullPointerException e3) {
            throw new Exception("hasIPv6Route failed", e3);
        } catch (SocketException e4) {
            throw new Exception("hasIPv6Route failed", e4);
        }
    }

    /* JADX INFO: Access modifiers changed from: private */
    public static boolean hasNetworkConnectivity(Context context) {
        NetworkInfo activeNetworkInfo;
        ConnectivityManager connectivityManager = (ConnectivityManager) context.getSystemService("connectivity");
        return (connectivityManager == null || (activeNetworkInfo = connectivityManager.getActiveNetworkInfo()) == null || !activeNetworkInfo.isConnected()) ? false : true;
    }
}
