package com.rkh.vpn.core

/**
 * SIMORGH JNI wrapper for tun2proxy Android library.
 * Uses the stable Android fd-run ABI from tun2proxy v0.7.x.
 * Do not use the v0.8.x CLI ABI here: it can terminate the Android process
 * on fatal native errors and it removed tun2proxy_with_fd_run from some builds.
 */
object Tun2ProxyBridge {
    init {
        System.loadLibrary("simorghtun2proxybridge")
    }

    @JvmStatic
    private external fun startTun2proxyNative(
        proxyUrl: String,
        tunFd: Int,
        closeFdOnDrop: Boolean,
        tunMtu: Int,
        dnsStrategy: Int,
        verbosity: Int
    ): Int

    @JvmStatic
    private external fun stopTun2proxyNative()

    @JvmStatic
    private external fun closeFdNative(fd: Int): Int

    @JvmStatic
    private external fun armExitOnReturnNative()

    @JvmStatic
    private external fun disarmExitOnReturnNative()

    @JvmStatic
    external fun lastNativeError(): String

    fun requestStop() {
        runCatching { stopTun2proxyNative() }
    }

    fun closeRawFd(fd: Int): Int = closeFdNative(fd)

    fun armExitOnReturn() {
        runCatching { armExitOnReturnNative() }
    }

    fun disarmExitOnReturn() {
        runCatching { disarmExitOnReturnNative() }
    }

    fun runWithFd(
        proxyUrl: String,
        tunFd: Int,
        closeFdOnDrop: Boolean = false,
        tunMtu: Int = 1500,
        dnsStrategy: Int = DNS_OVER_TCP,
        verbosity: Int = VERBOSITY_INFO
    ): Int = startTun2proxyNative(proxyUrl, tunFd, closeFdOnDrop, tunMtu, dnsStrategy, verbosity)

    const val DNS_VIRTUAL: Int = 0
    const val DNS_OVER_TCP: Int = 1
    const val DNS_DIRECT: Int = 2

    const val VERBOSITY_ERROR: Int = 1
    const val VERBOSITY_WARN: Int = 2
    const val VERBOSITY_INFO: Int = 3
    const val VERBOSITY_DEBUG: Int = 4
}
