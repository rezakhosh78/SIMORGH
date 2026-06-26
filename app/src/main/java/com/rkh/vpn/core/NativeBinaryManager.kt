package com.rkh.vpn.core

import android.content.Context
import android.os.Build
import com.rkh.vpn.data.RKhVpnLogStore
import java.io.File

/**
 * Android 10+ commonly prevents executing binaries copied to the app files directory.
 * For binary-core mode we therefore package executables as native libraries:
 *   app/src/main/jniLibs/<abi>/libxray.so
 *   app/src/main/jniLibs/<abi>/libtun2socks.so
 * PackageManager extracts them to applicationInfo.nativeLibraryDir with executable permission.
 */
class NativeBinaryManager(private val context: Context) {
    private val abi: String = Build.SUPPORTED_ABIS.firstOrNull().orEmpty()

    fun prepare(binaryName: String): File {
        val nativeName = "lib${binaryName}.so"
        val nativeDir = File(context.applicationInfo.nativeLibraryDir ?: "")
        val nativeFile = File(nativeDir, nativeName)
        val nativeDirListing = nativeDir.listFiles()?.joinToString { "${it.name}(${it.length()}b,exec=${it.canExecute()})" } ?: "<empty or inaccessible>"

        if (isUsable(nativeFile)) {
            log("Using packaged native binary for $binaryName: ${nativeFile.absolutePath} (${nativeFile.length()} bytes), canExecute=${nativeFile.canExecute()}, ABI=$abi")
            return nativeFile
        }

        val legacyAssetPath = "bin/$abi/$binaryName"
        val legacyFile = File(context.filesDir, "native-bin/$abi/$binaryName")
        val legacyExists = legacyFile.exists()
        if (legacyExists) {
            log("Ignoring cached runtime binary ${legacyFile.absolutePath}. Android may mount app data as noexec; use packaged jniLibs native binary instead.")
        }

        throw IllegalStateException(
            "Missing executable packaged native binary: app/src/main/jniLibs/$abi/$nativeName. " +
                "Runtime/assets binaries like assets/$legacyAssetPath cannot be executed reliably on Android 10+ and caused Permission denied. " +
                "Run download_native_binaries_windows.bat before build, or manually put $binaryName renamed to $nativeName in app/src/main/jniLibs/$abi/. " +
                "nativeLibraryDir=${nativeDir.absolutePath}, files=$nativeDirListing, supported ABIs=${Build.SUPPORTED_ABIS.joinToString()}"
        )
    }

    private fun isUsable(file: File): Boolean =
        file.exists() && file.isFile && file.length() > 100_000L

    private fun log(message: String, throwable: Throwable? = null) =
        RKhVpnLogStore.append(context, "CoreBin", message, throwable)
}
