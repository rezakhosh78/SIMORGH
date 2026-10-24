package psi;

/* loaded from: classes.dex */
public interface PsiphonProvider extends PsiphonProviderNetwork, PsiphonProviderNoticeHandler {
    String bindToDevice(long j2);

    String getDNSServersAsString();

    @Override // psi.PsiphonProviderNetwork
    String getNetworkID();

    @Override // psi.PsiphonProviderNetwork
    long hasIPv6Route();

    @Override // psi.PsiphonProviderNetwork
    long hasNetworkConnectivity();

    @Override // psi.PsiphonProviderNetwork
    String iPv6Synthesize(String str);

    void notice(String str);
}
