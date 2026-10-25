package com.rkh.vpn.core

/** JNI owner for the embedded PingNG native Desync SOCKS proxy. */
class DesyncProxy {
    companion object {
        init { System.loadLibrary("PingNG") }
    }

    @Volatile private var fd: Int = -1

    @Synchronized
    fun prepare(args: Array<String>) {
        check(fd < 0) { "Desync proxy is already prepared" }
        fd = jniCreateSocketWithCommandLine(args).also {
            check(it >= 0) { "PingNG rejected the Desync arguments" }
        }
    }

    fun runLoop(): Int {
        val socket = fd
        check(socket >= 0) { "Desync proxy is not prepared" }
        return jniStartProxy(socket)
    }

    @Synchronized
    fun stop(): Int {
        val socket = fd
        if (socket < 0) return 0
        fd = -1
        return jniStopProxy(socket)
    }

    private external fun jniCreateSocketWithCommandLine(args: Array<String>): Int
    private external fun jniStartProxy(fd: Int): Int
    private external fun jniStopProxy(fd: Int): Int
}
