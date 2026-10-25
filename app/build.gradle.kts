import java.net.HttpURLConnection
import java.net.URI
import java.security.MessageDigest
import java.util.zip.ZipFile

plugins {
    id("com.android.application") version "8.13.2"
    id("org.jetbrains.kotlin.android") version "2.0.21"
    id("org.jetbrains.kotlin.plugin.compose") version "2.0.21"
}

// SIMORGH VPN: Public/Private mode, stable native binaries, liquid glass cards.
// Native downloader is restricted to official GitHub release URLs only.
// The default Xray v26.10.21 ARM64 archive is verified against its official SHA-256.
// Gradle properties can select another Xray release and provide its checksum:
//   -Psimorgh.xrayVersion=<Xray release tag; default v26.10.21>
//   -Psimorgh.xrayArm64ZipSha256=<expected xray zip sha256; optional override>
//   -Psimorgh.stormDnsTermuxArm64ZipSha256=<expected StormDNS Termux ARM64 zip sha256>
//   -Psimorgh.tun2proxyAndroidLibsZipSha256=<expected tun2proxy android libs zip sha256; optional; default uses v0.7.20 because it exports the Android fd-run ABI>
//   -Psimorgh.refreshStormDns=true|false  (default: true)
//   -Psimorgh.refreshTun2proxy=true|false  (default: true)
// Build automatically downloads native binaries and packages them as jniLibs:
//   app/src/main/jniLibs/arm64-v8a/libxray.so
//   app/src/main/jniLibs/arm64-v8a/libstormdns.so
//   app/src/main/jniLibs/arm64-v8a/libsoar.so (built from native/soar with verified Go)
//   app/src/main/jniLibs/arm64-v8a/libdnstt.so and libwg-go.so
//   app/src/main/jniLibs/<abi>/libtun2proxy.so
//   app/src/main/jniLibs/<abi>/libsimorghtun2proxybridge.so

android {
    namespace = "com.rkh.vpn"
    compileSdk = 36
    // Pin the Android NDK used by the native integrations and the SoarDNS build.
    ndkVersion = "30.0.14904198"

    defaultConfig {
        applicationId = "com.rkh.simorgh"
        minSdk = 29
        targetSdk = 36
        versionCode = 200000
        versionName = "v2.0.0"

        // StormDNS/Xray runtime binaries are ARM64 Android builds; keep native build packaging aligned.
        ndk {
            abiFilters.add("arm64-v8a")
        }
    }

    packaging {
        resources.excludes += setOf("META-INF/DEPENDENCIES", "META-INF/LICENSE*", "META-INF/NOTICE*")
        jniLibs.useLegacyPackaging = true
    jniLibs.keepDebugSymbols += setOf("**/libxray.so", "**/libnipovpn.so", "**/libstormdns.so", "**/libtun2proxy.so", "**/libsimorghtun2proxybridge.so", "**/libwarpplus.so", "**/libwg-go.so", "**/libdnstt.so", "**/libsoar.so")
    }

    buildTypes {
        release {
            // Disabled for app-only release builds to avoid R8 Java heap OOM on low-memory systems.
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
        debug { isMinifyEnabled = false }
    }

    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
    buildFeatures { compose = true }

    lint {
        checkReleaseBuilds = false
        abortOnError = false
    }

    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.22.1"
        }
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2024.12.01")
    implementation(composeBom)
    androidTestImplementation(composeBom)

    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.work:work-runtime-ktx:2.10.0")


    debugImplementation("androidx.compose.ui:ui-tooling")
}

val arm64NativeDir = layout.projectDirectory.dir("src/main/jniLibs/arm64-v8a")
val nativeTmpDir = layout.projectDirectory.dir("build/native-download-tmp")
val xrayAssetDir = layout.projectDirectory.dir("src/main/assets/xray")
val configuredXrayVersion = providers.gradleProperty("simorgh.xrayVersion").orNull
    ?.trim()
    ?.removePrefix("v")
val xrayVersion = configuredXrayVersion
    ?.takeIf { it.matches(Regex("""\d+(?:\.\d+)+""")) }
    ?: "26.10.21"
val stormDnsAssetDir = layout.projectDirectory.dir("src/main/assets/stormdns")

fun previewDownloadedFile(file: File): String {
    return runCatching {
        file.inputStream().use { input ->
            val bytes = ByteArray(minOf(file.length(), 512L).toInt())
            val read = input.read(bytes)
            if (read <= 0) "" else String(bytes, 0, read, Charsets.UTF_8).replace("\r", " ").replace("\n", " ").take(240)
        }
    }.getOrDefault("")
}

fun validateZipArchive(file: File, label: String) {
    if (!file.exists()) throw GradleException("Downloaded $label does not exist: ${file.path}")
    if (file.length() < 100_000L) {
        throw GradleException("Downloaded $label is too small to be a valid release ZIP: ${file.path} (${file.length()} bytes). Preview: ${previewDownloadedFile(file)}")
    }
    if (!isZipArchive(file)) {
        throw GradleException("Downloaded $label is not a ZIP file: ${file.path} (${file.length()} bytes). Preview: ${previewDownloadedFile(file)}")
    }
    try {
        ZipFile(file).use { zip ->
            val entries = zip.entries()
            if (!entries.hasMoreElements()) throw GradleException("Downloaded $label ZIP has no entries: ${file.path}")
        }
    } catch (t: Throwable) {
        throw GradleException("Downloaded $label is a corrupt/incomplete ZIP: ${file.path} (${file.length()} bytes). Delete app/build/native-download-tmp and rebuild. Cause: ${t.message}. Preview: ${previewDownloadedFile(file)}", t)
    }
}

fun downloadWithFallback(urls: List<String>, outFile: File, label: String) {
    outFile.parentFile.mkdirs()
    var lastError: Throwable? = null
    for (url in urls.distinct()) {
        try {
            val uri = URI(url)
            val host = uri.host?.lowercase() ?: ""
            if (host != "github.com") {
                throw GradleException("Refusing non-official native binary source for $label: $url")
            }
            println("[SIMORGH native] Downloading $label from official GitHub release: $url")
            if (outFile.exists()) outFile.delete()
            val connection = uri.toURL().openConnection()
            connection.connectTimeout = 30_000
            connection.readTimeout = 180_000
            connection.setRequestProperty("User-Agent", "SIMORGH-Gradle-NativeDownloader")
            connection.setRequestProperty("Accept", "application/octet-stream, application/zip, */*")
            connection.getInputStream().use { input ->
                outFile.outputStream().use { output -> input.copyTo(output) }
            }
            val len = outFile.length()
            println("[SIMORGH native] Downloaded $label: $len bytes => ${outFile.path}")
            if (outFile.name.lowercase().endsWith(".zip")) {
                validateZipArchive(outFile, label)
            }
            return
        } catch (t: Throwable) {
            lastError = t
            if (outFile.exists()) outFile.delete()
            println("[SIMORGH native] FAILED $label URL: $url")
            println("[SIMORGH native] Reason: ${t.message}")
        }
    }
    throw GradleException("All official GitHub download URLs failed for $label. Last error: ${lastError?.message}")
}

fun fileSha256(file: File): String {
    val digest = MessageDigest.getInstance("SHA-256")
    file.inputStream().use { input ->
        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
        while (true) {
            val read = input.read(buffer)
            if (read <= 0) break
            digest.update(buffer, 0, read)
        }
    }
    return digest.digest().joinToString("") { "%02x".format(it) }
}

fun verifySha256(file: File, expectedSha256: String?, label: String) {
    val expected = expectedSha256?.trim()?.replace(":", "")?.lowercase().orEmpty()
    if (expected.isBlank()) {
        println("[SIMORGH native] SHA-256 check for $label is optional and was not provided.")
        return
    }
    val actual = fileSha256(file)
    if (actual != expected) {
        throw GradleException("SHA-256 mismatch for $label. Expected=$expected Actual=$actual File=${file.path}")
    }
    println("[SIMORGH native] SHA-256 verified for $label: $actual")
}

fun extractBinaryFromZip(zipFile: File, wantedName: String, outFile: File, minBytes: Long) {
    outFile.parentFile.mkdirs()
    validateZipArchive(zipFile, wantedName)
    ZipFile(zipFile).use { zip ->
        val entries = zip.entries().toList()
        println("[SIMORGH native] Zip entries for $wantedName from ${zipFile.name}:")
        entries.take(40).forEach { println("[SIMORGH native]  - ${it.name} (${it.size} bytes)") }
        val candidate = entries.firstOrNull { e ->
            val name = e.name.substringAfterLast('/').lowercase()
            !e.isDirectory && when (wantedName) {
                "xray" -> name == "xray" || name == "xray.exe"
                "tun2socks" -> name == "tun2socks" || name == "tun2socks.exe" || name.startsWith("tun2socks-")
                else -> false
            }
        } ?: throw GradleException("Could not find $wantedName executable inside ${zipFile.name}")
        zip.getInputStream(candidate).use { input ->
            outFile.outputStream().use { output -> input.copyTo(output) }
        }
    }
    val len = outFile.length()
    if (len < minBytes) throw GradleException("Extracted $wantedName is too small: ${outFile.path} ($len bytes)")
    outFile.setExecutable(true, false)
    println("[SIMORGH native] Placed $wantedName => ${outFile.path} ($len bytes)")
}

fun extractAssetFromZip(zipFile: File, wantedName: String, outFile: File, minBytes: Long) {
    outFile.parentFile.mkdirs()
    validateZipArchive(zipFile, wantedName)
    ZipFile(zipFile).use { zip ->
        val candidate = zip.entries().toList().firstOrNull { e ->
            !e.isDirectory && e.name.substringAfterLast('/').equals(wantedName, ignoreCase = true)
        } ?: throw GradleException("Could not find $wantedName inside ${zipFile.name}")
        zip.getInputStream(candidate).use { input ->
            outFile.outputStream().use { output -> input.copyTo(output) }
        }
    }
    val len = outFile.length()
    if (len < minBytes) throw GradleException("Extracted $wantedName is too small: ${outFile.path} ($len bytes)")
    println("[SIMORGH native] Placed Xray asset $wantedName => ${outFile.path} ($len bytes)")
}


fun extractStormDnsClientArchive(zipFile: File, outBinary: File, configOut: File, resolversOut: File) {
    outBinary.parentFile.mkdirs()
    configOut.parentFile.mkdirs()
    resolversOut.parentFile.mkdirs()
    validateZipArchive(zipFile, "StormDNS client archive")
    ZipFile(zipFile).use { zip ->
        val entries = zip.entries().toList()
        println("[SIMORGH native] Zip entries for StormDNS client from ${zipFile.name}:")
        entries.take(60).forEach { println("[SIMORGH native]  - ${it.name} (${it.size} bytes)") }
        fun copyEntry(name: String, outFile: File, minBytes: Long) {
            val entry = entries.firstOrNull { e -> !e.isDirectory && e.name.substringAfterLast('/').equals(name, ignoreCase = true) }
                ?: throw GradleException("Could not find $name inside ${zipFile.name}")
            zip.getInputStream(entry).use { input -> outFile.outputStream().use { output -> input.copyTo(output) } }
            if (outFile.length() < minBytes) throw GradleException("Extracted $name is too small: ${outFile.path} (${outFile.length()} bytes)")
        }
        val binaryEntry = entries.firstOrNull { e ->
            val name = e.name.substringAfterLast('/').lowercase()
            !e.isDirectory && !name.endsWith(".toml") && !name.endsWith(".txt") && !name.endsWith(".md") && !name.endsWith(".sh") &&
                (name == "client" || name == "stormdns" || name == "stormdns_client" || name.startsWith("stormdns_client"))
        } ?: throw GradleException("Could not find StormDNS client executable inside ${zipFile.name}")
        zip.getInputStream(binaryEntry).use { input -> outBinary.outputStream().use { output -> input.copyTo(output) } }
        if (outBinary.length() < 100_000L) throw GradleException("Extracted StormDNS client is too small: ${outBinary.path} (${outBinary.length()} bytes)")
        outBinary.setExecutable(true, false)
        copyEntry("client_config.toml", configOut, 500L)
        copyEntry("client_resolvers.txt", resolversOut, 10L)
    }
    println("[SIMORGH native] Placed StormDNS Termux/Android ARM64 client => ${outBinary.path} (${outBinary.length()} bytes)")
    println("[SIMORGH native] Placed StormDNS config assets => ${configOut.path}, ${resolversOut.path}")
}

fun extractTun2ProxyAndroidLibs(zipFile: File) {
    val abiNames = listOf("arm64-v8a", "armeabi-v7a", "x86", "x86_64")
    validateZipArchive(zipFile, "tun2proxy Android libs archive")
    ZipFile(zipFile).use { zip ->
        val entries = zip.entries().toList()
        for (abi in abiNames) {
            val entry = entries.firstOrNull { e ->
                val p = e.name.replace('\\', '/')
                !e.isDirectory && (p.endsWith("/$abi/libtun2proxy.so") || p == "$abi/libtun2proxy.so")
            } ?: throw GradleException("Could not find libtun2proxy.so for $abi inside ${zipFile.name}")
            val outFile = layout.projectDirectory.file("src/main/jniLibs/$abi/libtun2proxy.so").asFile
            outFile.parentFile.mkdirs()
            zip.getInputStream(entry).use { input -> outFile.outputStream().use { output -> input.copyTo(output) } }
            if (outFile.length() < 100_000L || !isElfBinary(outFile)) throw GradleException("Extracted tun2proxy $abi library is invalid: ${outFile.path} (${outFile.length()} bytes)")
            println("[SIMORGH native] Placed tun2proxy $abi => ${outFile.path} (${outFile.length()} bytes)")
        }
    }
}

fun validateXrayAsset(fileName: String, minBytes: Long) {
    val file = xrayAssetDir.file(fileName).asFile
    if (!file.exists()) throw GradleException("Missing Xray asset after setup: ${file.path}")
    if (file.length() < minBytes) throw GradleException("Xray asset is too small/invalid: ${file.path} (${file.length()} bytes)")
    println("[SIMORGH native] OK asset: ${file.path} (${file.length()} bytes)")
}


fun elfLoadAlignments(file: File): List<Long> {
    if (!file.exists() || file.length() < 64L) return emptyList()
    val data = file.readBytes()
    fun u16(off: Int): Int = (data[off].toInt() and 0xff) or ((data[off + 1].toInt() and 0xff) shl 8)
    fun u32(off: Int): Long = ((data[off].toLong() and 0xff) or ((data[off + 1].toLong() and 0xff) shl 8) or ((data[off + 2].toLong() and 0xff) shl 16) or ((data[off + 3].toLong() and 0xff) shl 24))
    fun u64(off: Int): Long {
        var v = 0L
        for (i in 0 until 8) v = v or ((data[off + i].toLong() and 0xff) shl (8 * i))
        return v
    }
    if (data[0] != 0x7f.toByte() || data[1] != 'E'.code.toByte() || data[2] != 'L'.code.toByte() || data[3] != 'F'.code.toByte()) return emptyList()
    val is64 = data[4].toInt() == 2
    val phoff = if (is64) u64(32) else u32(28)
    val phentsize = u16(if (is64) 54 else 42)
    val phnum = u16(if (is64) 56 else 44)
    val aligns = mutableListOf<Long>()
    for (i in 0 until phnum) {
        val off = phoff.toInt() + i * phentsize
        if (off + phentsize > data.size) break
        val pType = u32(off)
        if (pType == 1L) { // PT_LOAD
            val alignOff = if (is64) off + 48 else off + 28
            if (alignOff + (if (is64) 8 else 4) <= data.size) aligns += if (is64) u64(alignOff) else u32(alignOff)
        }
    }
    return aligns
}

fun validate16KbPageCompatibleElf(file: File) {
    val aligns = elfLoadAlignments(file)
    if (aligns.isEmpty()) return
    val bad = aligns.filter { it < 0x4000L }
    if (bad.isNotEmpty()) {
        throw GradleException("${file.name} is not Android 16 / 16KB page-size compatible. PT_LOAD alignments=$aligns. Replace this native binary with a 16KB-page-compatible Android build.")
    }
    println("[SIMORGH native] Android 16/16KB page OK: ${file.path} alignments=$aligns")
}

fun validateNativeExecutable(fileName: String, minBytes: Long) {
    val file = arm64NativeDir.file(fileName).asFile
    if (!file.exists()) throw GradleException("Missing native binary after setup: ${file.path}")
    if (file.length() < minBytes) throw GradleException("Native binary is too small/invalid: ${file.path} (${file.length()} bytes)")
    validate16KbPageCompatibleElf(file)
    println("[SIMORGH native] OK: ${file.path} (${file.length()} bytes)")
}

fun validateNativeFile(fileName: String, minBytes: Long) {
    val file = arm64NativeDir.file(fileName).asFile
    if (!file.exists()) throw GradleException("Missing native binary after setup: ${file.path}")
    if (file.length() < minBytes) throw GradleException("Native binary is too small/invalid: ${file.path} (${file.length()} bytes)")
    if (!isElfBinary(file)) throw GradleException("Native binary is not an ELF executable: ${file.path}")
    println("[SIMORGH native] OK executable: ${file.path} (${file.length()} bytes)")
}



fun fileMagic(file: File, bytes: Int = 4): ByteArray {
    if (!file.exists() || file.length() < bytes) return ByteArray(0)
    return file.inputStream().use { input -> ByteArray(bytes).also { input.read(it) } }
}

fun isZipArchive(file: File): Boolean {
    val magic = fileMagic(file)
    return magic.size >= 4 && magic[0] == 0x50.toByte() && magic[1] == 0x4b.toByte()
}

fun isElfBinary(file: File): Boolean {
    val magic = fileMagic(file)
    return magic.size >= 4 && magic[0] == 0x7f.toByte() && magic[1] == 'E'.code.toByte() && magic[2] == 'L'.code.toByte() && magic[3] == 'F'.code.toByte()
}


tasks.register("ensureArm64NativeBinaries") {
    group = "build setup"
    description = "Auto-downloads Xray, official StormDNS Termux/Android ARM64, and tun2proxy Android JNI libraries."
    doLast {
        val outDir = arm64NativeDir.asFile
        outDir.mkdirs()
        val expectedXrayZipSha256 = providers.gradleProperty("simorgh.xrayArm64ZipSha256").orNull
            ?: if (xrayVersion == "26.10.21") {
                "d81604f35a56c98ea6091142c1c744f4895c7384bc2e1c85cd910612c70794e9"
            } else {
                null
            }
        val expectedStormDnsZipSha256 = providers.gradleProperty("simorgh.stormDnsTermuxArm64ZipSha256").orNull
        val expectedTun2proxyZipSha256 = providers.gradleProperty("simorgh.tun2proxyAndroidLibsZipSha256").orNull
        val refreshXray = providers.gradleProperty("simorgh.refreshXray").orNull?.toBooleanStrictOrNull() ?: true
        val refreshStormDns = providers.gradleProperty("simorgh.refreshStormDns").orNull?.toBooleanStrictOrNull() ?: true
        val refreshTun2proxy = providers.gradleProperty("simorgh.refreshTun2proxy").orNull?.toBooleanStrictOrNull() ?: true
        val libXray = File(outDir, "libxray.so")
        val libStormDns = File(outDir, "libstormdns.so")
        val libTun2proxyArm64 = File(outDir, "libtun2proxy.so")

        val xrayZip = nativeTmpDir.file("xray-arm64-v8a-v$xrayVersion.zip").asFile
        val geositeDat = xrayAssetDir.file("geosite.dat").asFile
        val geoipDat = xrayAssetDir.file("geoip.dat").asFile
        val needsXrayZip =
            refreshXray ||
            !libXray.exists() || libXray.length() < 1_000_000L ||
            !geositeDat.exists() || geositeDat.length() < 100_000L ||
            !geoipDat.exists() || geoipDat.length() < 100_000L

        if (needsXrayZip) {
            downloadWithFallback(
                listOf(
                    "https://github.com/XTLS/Xray-core/releases/download/v$xrayVersion/Xray-android-arm64-v8a.zip"
                ),
                xrayZip,
                "xray/arm64-v8a + geo assets"
            )
            verifySha256(xrayZip, expectedXrayZipSha256, "xray/arm64-v8a zip")
        }

        if (refreshXray || !libXray.exists() || libXray.length() < 1_000_000L) {
            extractBinaryFromZip(xrayZip, "xray", libXray, 1_000_000L)
        }

        if (refreshXray || !geositeDat.exists() || geositeDat.length() < 100_000L) {
            extractAssetFromZip(xrayZip, "geosite.dat", geositeDat, 100_000L)
        }
        if (refreshXray || !geoipDat.exists() || geoipDat.length() < 100_000L) {
            extractAssetFromZip(xrayZip, "geoip.dat", geoipDat, 100_000L)
        }

        val stormDnsZip = nativeTmpDir.file("StormDNS_Client_Termux_ARM64.zip").asFile
        val stormDnsConfig = stormDnsAssetDir.file("client_config.toml").asFile
        val stormDnsResolvers = stormDnsAssetDir.file("client_resolvers.txt").asFile
        if (refreshStormDns || !libStormDns.exists() || libStormDns.length() < 100_000L || !isElfBinary(libStormDns) || !stormDnsConfig.exists() || !stormDnsResolvers.exists()) {
            downloadWithFallback(
                listOf(
                    "https://github.com/nullroute1970/StormDNS/releases/download/v2026.05.13.223445-87348df/StormDNS_Client_Termux_ARM64.zip",
                    "https://github.com/nullroute1970/StormDNS/releases/latest/download/StormDNS_Client_Termux_ARM64.zip"
                ),
                stormDnsZip,
                "StormDNS official Termux/Android ARM64 client"
            )
            verifySha256(stormDnsZip, expectedStormDnsZipSha256, "StormDNS Termux/Android ARM64 client zip")
            extractStormDnsClientArchive(stormDnsZip, libStormDns, stormDnsConfig, stormDnsResolvers)
        }

        val tun2proxyZip = nativeTmpDir.file("tun2proxy-android-libs-v0.7.20.zip").asFile
        if (refreshTun2proxy || !libTun2proxyArm64.exists() || libTun2proxyArm64.length() < 100_000L || !isElfBinary(libTun2proxyArm64)) {
            downloadWithFallback(
                listOf(
                    "https://github.com/tun2proxy/tun2proxy/releases/download/v0.7.20/tun2proxy-android-libs.zip",
                    "https://github.com/blechschmidt/tun2proxy/releases/download/v0.7.20/tun2proxy-android-libs.zip"
                ),
                tun2proxyZip,
                "tun2proxy Android JNI libraries v0.7.20 fd-run ABI"
            )
            verifySha256(tun2proxyZip, expectedTun2proxyZipSha256, "tun2proxy Android libs zip")
            extractTun2ProxyAndroidLibs(tun2proxyZip)
        }

        validateNativeExecutable("libxray.so", 1_000_000L)
        validateNativeFile("libstormdns.so", 100_000L)
        validateNativeFile("libtun2proxy.so", 100_000L)
        validateNativeExecutable("libnipovpn.so", 1_000_000L)
        val dnsttExecutable = arm64NativeDir.file("libdnstt.so").asFile
        check(dnsttExecutable.setExecutable(true, false) || dnsttExecutable.canExecute()) {
            "DNSTT native core could not be marked executable: ${dnsttExecutable.path}"
        }
        validateNativeExecutable("libdnstt.so", 1_000_000L)
        validateNativeFile("libwg-go.so", 1_000_000L)
        validateXrayAsset("geosite.dat", 100_000L)
        validateXrayAsset("geoip.dat", 100_000L)
    }
}

tasks.register("buildSoarClientAndroid") {
    group = "build setup"
    description = "Builds the PingNG SoarDNS client for SIMORGH ARM64 Android."
    inputs.dir(layout.projectDirectory.dir("native/soar"))
    outputs.file(layout.projectDirectory.file("src/main/jniLibs/arm64-v8a/libsoar.so"))
    doLast {
        val source = layout.projectDirectory.dir("native/soar").asFile
        check(File(source, "go.mod").isFile && File(source, "go.sum").isFile) { "SoarDNS Go source is incomplete: $source" }
        val osName = System.getProperty("os.name").lowercase()
        val archName = System.getProperty("os.arch").lowercase()
        val hostOs = when {
            osName.startsWith("windows") -> "windows"
            osName.startsWith("mac") || osName.startsWith("darwin") -> "darwin"
            osName.startsWith("linux") -> "linux"
            else -> error("Unsupported host OS for SoarDNS build: $osName")
        }
        val hostArch = when (archName) {
            "amd64", "x86_64", "x64" -> "amd64"
            "aarch64", "arm64" -> "arm64"
            else -> error("Unsupported host CPU for SoarDNS build: $archName")
        }
        val hostKey = "$hostOs-$hostArch"
        val hashes = mapOf(
            "windows-amd64" to "20d2ceafb4ed41b96b879010927b28bc92a5be57a7c1801ce365a9ca51d3224a",
            "windows-arm64" to "95cd63bc6b0da77409ba819215afea9ddf5702c55a3b20af3dd90ea95c7b130c",
            "darwin-amd64" to "278d580b32e299fe4a9c990fcf2d02acfe538c7e551a6ee18f9c7164573d2c63",
            "darwin-arm64" to "875cf54a15311eee2c99b9dd67c68c4a49351d489ab622bf2cfd28c8f2078d3c",
            "linux-amd64" to "2b2cfc7148493da5e73981bffbf3353af381d5f93e789c82c79aff64962eb556",
            "linux-arm64" to "9d89a3ea57d141c2b22d70083f2c8459ba3890f2d9e818e7e933b75614936565",
        )
        val expectedHash = hashes[hostKey] ?: error("No verified Go 1.26.3 archive is configured for $hostKey")
        val goVersion = "1.26.3"
        val cacheRoot = File(gradle.gradleUserHomeDir, "caches/simorgh/toolchains/go-$goVersion")
        val installDir = File(cacheRoot, hostKey)
        val goExe = File(installDir, "go/bin/go" + if (hostOs == "windows") ".exe" else "")
        fun isValidGo(): Boolean {
            if (!goExe.isFile) return false
            val probe = ProcessBuilder(goExe.absolutePath, "version").redirectErrorStream(true).start()
            val version = probe.inputStream.bufferedReader().use { it.readText() }
            return probe.waitFor() == 0 && version.contains("go$goVersion")
        }
        if (!isValidGo()) {
            cacheRoot.mkdirs()
            val archiveName = "go$goVersion.$hostOs-$hostArch." + if (hostOs == "windows") "zip" else "tar.gz"
            val archive = File(cacheRoot, archiveName)
            if (!archive.isFile || fileSha256(archive) != expectedHash) {
                archive.delete()
                val partial = File(cacheRoot, "$archiveName.download").apply { delete() }
                println("[SIMORGH native] Downloading Go $goVersion from go.dev for SoarDNS build...")
                val connection = URI("https://go.dev/dl/$archiveName").toURL().openConnection() as HttpURLConnection
                connection.connectTimeout = 30_000
                connection.readTimeout = 600_000
                connection.instanceFollowRedirects = true
                try { connection.inputStream.use { input -> partial.outputStream().use { output -> input.copyTo(output) } } }
                finally { connection.disconnect() }
                check(fileSha256(partial) == expectedHash) { partial.delete(); "Go archive SHA-256 verification failed: $archiveName" }
                check(partial.renameTo(archive)) { "Could not cache Go archive at $archive" }
            }
            val staging = File(cacheRoot, "$hostKey.extracting").apply { deleteRecursively(); mkdirs() }
            val tree = if (hostOs == "windows") zipTree(archive) else tarTree(resources.gzip(archive))
            copy { from(tree); into(staging) }
            val stagedGo = File(staging, "go/bin/go" + if (hostOs == "windows") ".exe" else "")
            check(stagedGo.isFile) { "Go archive did not extract a valid toolchain" }
            if (hostOs != "windows") File(staging, "go").walkTopDown().filter { it.isFile }.forEach { it.setExecutable(true, true) }
            installDir.deleteRecursively()
            check(staging.renameTo(installDir)) { "Could not install Go toolchain under $installDir" }
            archive.delete()
        }
        check(isValidGo()) { "Verified Go $goVersion is unavailable at $goExe" }

        val ndk = android.sdkDirectory.resolve("ndk/${android.ndkVersion}")
        check(ndk.isDirectory) { "Android NDK ${android.ndkVersion} is missing: $ndk" }
        val prebuilt = File(ndk, "toolchains/llvm/prebuilt")
        val ndkHostCandidates = when (hostOs) {
            "windows" -> listOf("windows-x86_64", "windows-arm64")
            "darwin" -> listOf("darwin-x86_64", "darwin-arm64")
            else -> listOf("linux-x86_64", "linux-aarch64", "linux-arm64")
        }
        val ndkHost = ndkHostCandidates.firstOrNull { File(prebuilt, it).isDirectory }
            ?: error("Android NDK host compiler is missing under $prebuilt")
        val toolchainBin = File(prebuilt, "$ndkHost/bin")
        val clang = File(toolchainBin, "clang" + if (hostOs == "windows") ".exe" else "")
        check(clang.isFile) { "Android ARM64 Clang wrapper is missing: $clang" }
        val output = layout.projectDirectory.file("src/main/jniLibs/arm64-v8a/libsoar.so").asFile
        output.parentFile.mkdirs()
        val cache = File(gradle.gradleUserHomeDir, "caches/simorgh")
        val env = mapOf(
            "GOOS" to "android", "GOARCH" to "arm64", "CGO_ENABLED" to "1",
            "CC" to "\"${clang.absolutePath.replace('\\', '/')}\" --target=aarch64-linux-android29",
            "CGO_LDFLAGS" to "-Wl,-z,max-page-size=16384", "GOFLAGS" to "-mod=readonly", "GOTOOLCHAIN" to "local",
            "GOMODCACHE" to File(cache, "gomodcache").absolutePath, "GOCACHE" to File(cache, "gobuildcache").absolutePath,
            "GOPROXY" to (System.getenv("GOPROXY")?.takeIf { it.isNotBlank() } ?: "https://proxy.golang.org|https://goproxy.io|direct"),
        )
        File(env.getValue("GOMODCACHE")).mkdirs()
        File(env.getValue("GOCACHE")).mkdirs()
        val builder = ProcessBuilder(goExe.absolutePath, "build", "-trimpath", "-buildvcs=false", "-buildmode=pie", "-o", output.absolutePath, "./cmd/pingng-soar")
            .directory(source).redirectErrorStream(true)
        builder.environment().putAll(env)
        val process = builder.start()
        val buildOutput = process.inputStream.bufferedReader().use { it.readText() }
        check(process.waitFor() == 0) { "SoarDNS ARM64 build failed:\n$buildOutput" }
        output.setExecutable(true, false)
        check(output.length() > 1_000_000L && isElfBinary(output)) { "Built SoarDNS client is missing or invalid: $output" }
        validate16KbPageCompatibleElf(output)
        println("[SIMORGH native] Built SoarDNS ARM64 client: ${output.path} (${output.length()} bytes)")
    }
}

tasks.named("preBuild") {
    dependsOn("ensureArm64NativeBinaries", "buildSoarClientAndroid")
}
