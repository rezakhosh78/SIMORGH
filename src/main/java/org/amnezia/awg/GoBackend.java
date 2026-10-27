/* SPDX-License-Identifier: Apache-2.0 */
package org.amnezia.awg;

/** JNI surface exported by the official AmneziaWG Android Go runtime. */
public final class GoBackend {
    static {
        System.loadLibrary("wg-go");
    }

    private GoBackend() {}

    public static native String awgBuildId();

    public static native int awgTurnOn(String interfaceName, int tunFd, String settings);

    public static native int awgStartProxy(String settings, String addresses, String dns, int mtu, int secondLayerSocksPort);

    public static native int awgProxyPort(int handle, int finalGateway);

    public static native void awgTurnOff(int handle);

    public static native int awgGetSocketV4(int handle);

    public static native int awgGetSocketV6(int handle);

    public static native String awgVersion();
}
