package com.ravan.core

/** Builds argv for the MIT-licensed warp-plus WARP backend without using a shell. */
object RavanWarpPlus {
    fun command(
        executable: String,
        localSocksPort: Int,
        warpKey: String? = null,
        endpoint: String? = null,
        nested: Boolean = false,
        dns: String = "1.1.1.1",
        cacheDir: String? = null
    ): List<String> {
        require(localSocksPort in 1..65535) { "local SOCKS port is invalid" }
        val args = mutableListOf(executable, "--bind", "127.0.0.1:$localSocksPort", "--dns", dns)
        if (!endpoint.isNullOrBlank()) args += listOf("--endpoint", endpoint)
        if (!warpKey.isNullOrBlank()) args += listOf("--key", warpKey)
        if (!cacheDir.isNullOrBlank()) args += listOf("--cache-dir", cacheDir)
        if (nested) args += "--gool"
        return args
    }
}
