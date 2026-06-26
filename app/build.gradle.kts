import java.net.URI
import java.security.MessageDigest
import java.util.zip.GZIPInputStream
import java.util.zip.ZipFile

plugins {
    id("com.android.application") version "8.13.2"
    id("org.jetbrains.kotlin.android") version "2.0.21"
    id("org.jetbrains.kotlin.plugin.compose") version "2.0.21"
}

// SIMORGH VPN: Public/Private mode, stable native binaries, liquid glass cards.
// Native downloader is restricted to official GitHub release URLs only.
// Optional SHA-256 verification can be enabled with Gradle properties:
//   -Psimorgh.xrayArm64ZipSha256=<expected xray zip sha256>
//   -Psimorgh.tun2socksArm64ZipSha256=<expected tun2socks zip sha256>
//   -Psimorgh.refreshTun2socks=true|false  (default: true)
// Build automatically downloads native binaries and packages them as jniLibs:
//   app/src/main/jniLibs/arm64-v8a/libxray.so
//   app/src/main/jniLibs/arm64-v8a/libtun2socks.so
//   app/src/main/jniLibs/arm64-v8a/libmasterdns.so

android {
    namespace = "com.rkh.vpn"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.rkh.simorgh"
        minSdk = 29
        targetSdk = 36
        versionCode = 120201
        versionName = "v1.2.1"
    }

    packaging {
        resources.excludes += setOf("META-INF/DEPENDENCIES", "META-INF/LICENSE*", "META-INF/NOTICE*")
        jniLibs.useLegacyPackaging = true
        jniLibs.keepDebugSymbols += setOf("**/libxray.so", "**/libtun2socks.so", "**/libnipovpn.so", "**/libmasterdns.so", "**/libstormdns.so")
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

fun downloadWithFallback(urls: List<String>, outFile: File, label: String) {
    outFile.parentFile.mkdirs()
    var lastError: Throwable? = null
    for (url in urls) {
        try {
            val uri = URI(url)
            val host = uri.host?.lowercase() ?: ""
            if (host != "github.com") {
                throw GradleException("Refusing non-official native binary source for $label: $url")
            }
            println("[SIMORGH native] Downloading $label from official GitHub release: $url")
            if (outFile.exists()) outFile.delete()
            uri.toURL().openStream().use { input ->
                outFile.outputStream().use { output -> input.copyTo(output) }
            }
            val len = outFile.length()
            println("[SIMORGH native] Downloaded $label: $len bytes")
            if (len < 100_000L) {
                val preview = runCatching { outFile.readText().take(200) }.getOrDefault("")
                throw GradleException("Downloaded file is too small or not a binary zip. Preview: $preview")
            }
            return
        } catch (t: Throwable) {
            lastError = t
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


val masterDnsReleaseTag = "v2026.06.13.234407-7de2476"

fun githubReleaseDownloadUrls(owner: String, repo: String, tag: String): List<String> {
    val api = URI("https://api.github.com/repos/$owner/$repo/releases/tags/$tag")
    return try {
        val conn = api.toURL().openConnection()
        conn.setRequestProperty("Accept", "application/vnd.github+json")
        conn.setRequestProperty("User-Agent", "SIMORGH-Gradle-Native-Downloader")
        val json = conn.getInputStream().bufferedReader().use { it.readText() }
        Regex("\\\"browser_download_url\\\"\\s*:\\s*\\\"([^\\\"]+)\\\"")
            .findAll(json)
            .map { it.groupValues[1].replace("\\/", "/") }
            .toList()
    } catch (t: Throwable) {
        println("[SIMORGH native] Could not query MasterDNS GitHub release API: ${t.message}")
        emptyList()
    }
}

fun masterDnsReleaseAssetUrls(): List<String> {
    val fromApi = githubReleaseDownloadUrls("masterking32", "MasterDnsVPN", masterDnsReleaseTag)
        .filter { url ->
            val l = url.lowercase()
            l.startsWith("https://github.com/masterking32/masterdnsvpn/releases/download/") &&
                (l.endsWith(".zip") || l.endsWith(".aar") || l.endsWith(".so") || l.endsWith(".tar.gz") || l.endsWith(".tgz") || !l.substringAfterLast('/').contains('.')) &&
                !l.endsWith(".sha256") && !l.endsWith(".sig") && !l.endsWith(".txt") &&
                !l.endsWith(".apk") && !l.endsWith(".aab") &&
                !l.contains("macos") && !l.contains("darwin") &&
                (l.contains("android") || l.contains("linux") || l.contains("arm64") || l.contains("aarch64") || l.contains("masterdns")) &&
                (l.contains("arm64") || l.contains("arm64-v8a") || l.contains("aarch64"))
        }
        .sortedWith(
            compareByDescending<String> {
                val l = it.lowercase()
                var score = 0
                if (l.contains("android")) score += 100
                if (l.contains("arm64-v8a")) score += 80
                if (l.contains("arm64")) score += 60
                if (l.contains("aarch64")) score += 40
                if (l.contains("masterdns")) score += 30
                if (l.contains("client_linux_arm64")) score += 70
                if (l.endsWith(".zip")) score += 20
                if (l.endsWith(".tar.gz") || l.endsWith(".tgz")) score += 15
                if (l.endsWith(".aar")) score += 10
                if (l.contains("linux")) score += 25
                score
            }
        )

    val fallback = listOf(
        // Actual arm64 client assets published by this MasterDnsVPN release.
        "https://github.com/masterking32/MasterDnsVPN/releases/download/$masterDnsReleaseTag/MasterDnsVPN_Client_Linux_ARM64.zip",
        "https://github.com/masterking32/MasterDnsVPN/releases/download/$masterDnsReleaseTag/MasterDnsVPN_Client_Linux_ARM64.tar.gz",
        "https://github.com/masterking32/MasterDnsVPN/releases/download/$masterDnsReleaseTag/MasterDnsVPN_Client_Linux-Legacy_ARM64.zip",
        "https://github.com/masterking32/MasterDnsVPN/releases/download/$masterDnsReleaseTag/MasterDnsVPN_Client_Linux-Legacy_ARM64.tar.gz",
        // Extra Android-style fallbacks if the upstream project adds Android-named assets later.
        "https://github.com/masterking32/MasterDnsVPN/releases/download/$masterDnsReleaseTag/MasterDnsVPN-android-arm64-v8a.zip",
        "https://github.com/masterking32/MasterDnsVPN/releases/download/$masterDnsReleaseTag/masterdnsvpn-android-arm64-v8a.zip",
        "https://github.com/masterking32/MasterDnsVPN/releases/download/$masterDnsReleaseTag/masterdns-android-arm64-v8a.zip",
        "https://github.com/masterking32/MasterDnsVPN/releases/download/$masterDnsReleaseTag/MasterDnsVPN-android-arm64.zip",
        "https://github.com/masterking32/MasterDnsVPN/releases/download/$masterDnsReleaseTag/masterdnsvpn-android-arm64.zip",
        "https://github.com/masterking32/MasterDnsVPN/releases/download/$masterDnsReleaseTag/masterdns-android-arm64.zip",
        "https://github.com/masterking32/MasterDnsVPN/releases/download/$masterDnsReleaseTag/MasterDnsVPN-android-arm64-v8a.tar.gz",
        "https://github.com/masterking32/MasterDnsVPN/releases/download/$masterDnsReleaseTag/masterdnsvpn-android-arm64-v8a.tar.gz",
        "https://github.com/masterking32/MasterDnsVPN/releases/download/$masterDnsReleaseTag/masterdns-android-arm64-v8a.tar.gz",
        "https://github.com/masterking32/MasterDnsVPN/releases/download/$masterDnsReleaseTag/MasterDnsVPN-android-arm64.tar.gz",
        "https://github.com/masterking32/MasterDnsVPN/releases/download/$masterDnsReleaseTag/masterdnsvpn-android-arm64.tar.gz",
        "https://github.com/masterking32/MasterDnsVPN/releases/download/$masterDnsReleaseTag/masterdns-android-arm64.tar.gz"
    )
    return (fromApi + fallback).distinct()
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

fun masterDnsEntryScore(entryName: String): Int {
    val l = entryName.lowercase()
    val base = l.substringAfterLast('/')
    if (base.isBlank()) return -10_000
    if (base.endsWith(".txt") || base.endsWith(".md") || base.endsWith(".json") || base.endsWith(".toml") ||
        base.endsWith(".yml") || base.endsWith(".yaml") || base.endsWith(".sha256") || base.endsWith(".sig") ||
        base.endsWith(".apk") || base.endsWith(".aab")) return -10_000
    if (l.contains("windows") || l.contains("darwin") || l.contains("macos") || l.contains("amd64") || l.contains("x86") ||
        l.contains("386") || l.contains("mips") || l.contains("riscv")) return -10_000

    var score = 0
    if (l.contains("android")) score += 120
    if (l.contains("arm64-v8a")) score += 100
    if (l.contains("arm64")) score += 80
    if (l.contains("aarch64")) score += 60
    if (base == "masterdns" || base == "masterdnsvpn" || base == "stormdns") score += 90
    if (base.startsWith("masterdns") || base.startsWith("masterdnsvpn") || base.startsWith("stormdns")) score += 60
    if (l.contains("masterdns") || l.contains("masterdnsvpn")) score += 40
    if (base.endsWith(".so")) score += 30
    if (base == "client") score += 10
    if (l.contains("linux")) score -= 20
    return score
}


fun isGzipFile(file: File): Boolean {
    val magic = fileMagic(file, 2)
    return magic.size >= 2 && magic[0] == 0x1f.toByte() && magic[1] == 0x8b.toByte()
}

fun tarHeaderName(header: ByteArray): String {
    fun cleanString(start: Int, len: Int): String = header.copyOfRange(start, start + len)
        .takeWhile { it.toInt() != 0 }
        .toByteArray()
        .toString(Charsets.UTF_8)
        .trim()
    val name = cleanString(0, 100)
    val prefix = cleanString(345, 155)
    return if (prefix.isNotBlank()) "$prefix/$name" else name
}

fun tarHeaderSize(header: ByteArray): Long {
    val raw = header.copyOfRange(124, 136)
        .takeWhile { it.toInt() != 0 && it.toInt() != 32 }
        .toByteArray()
        .toString(Charsets.US_ASCII)
        .trim()
    return raw.toLongOrNull(8) ?: 0L
}

fun readTarHeader(input: java.io.InputStream): ByteArray? {
    val header = ByteArray(512)
    var off = 0
    while (off < header.size) {
        val read = input.read(header, off, header.size - off)
        if (read < 0) return if (off == 0) null else throw GradleException("Truncated tar header")
        off += read
    }
    return if (header.all { it.toInt() == 0 }) null else header
}

fun skipExactly(input: java.io.InputStream, bytes: Long) {
    var remaining = bytes
    val buffer = ByteArray(8192)
    while (remaining > 0) {
        val want = if (remaining < buffer.size.toLong()) remaining.toInt() else buffer.size
        val read = input.read(buffer, 0, want)
        if (read < 0) throw GradleException("Unexpected EOF while skipping tar data")
        remaining -= read.toLong()
    }
}

fun copyExactly(input: java.io.InputStream, output: File, bytes: Long) {
    output.parentFile.mkdirs()
    output.outputStream().use { out ->
        var remaining = bytes
        val buffer = ByteArray(64 * 1024)
        while (remaining > 0) {
            val want = if (remaining < buffer.size.toLong()) remaining.toInt() else buffer.size
            val read = input.read(buffer, 0, want)
            if (read < 0) throw GradleException("Unexpected EOF while reading tar entry")
            out.write(buffer, 0, read)
            remaining -= read.toLong()
        }
    }
}

fun extractMasterDnsCoreFromTarGz(downloadedFile: File, outFile: File): Boolean {
    var bestScore = Int.MIN_VALUE
    var found = false
    val tempBest = File(downloadedFile.parentFile, "masterdns-best-from-tar.tmp")
    if (tempBest.exists()) tempBest.delete()
    GZIPInputStream(downloadedFile.inputStream()).use { gzip ->
        while (true) {
            val header = readTarHeader(gzip) ?: break
            val name = tarHeaderName(header)
            val size = tarHeaderSize(header)
            val type = header[156].toInt().toChar()
            val score = if (type == '0' || type == '\u0000') masterDnsEntryScore(name) else -10_000
            if (score > bestScore && score > 0 && size > 0L) {
                copyExactly(gzip, tempBest, size)
                bestScore = score
                found = true
            } else {
                skipExactly(gzip, size)
            }
            val padding = (512 - (size % 512)) % 512
            if (padding > 0) skipExactly(gzip, padding)
        }
    }
    if (found) {
        tempBest.copyTo(outFile, overwrite = true)
        tempBest.delete()
    }
    return found
}

fun extractMasterDnsCore(downloadedFile: File, outFile: File, minBytes: Long) {
    outFile.parentFile.mkdirs()
    if (isElfBinary(downloadedFile)) {
        downloadedFile.copyTo(outFile, overwrite = true)
    } else if (isZipArchive(downloadedFile)) {
        ZipFile(downloadedFile).use { zip ->
            val entries = zip.entries().toList().filter { !it.isDirectory }
            println("[SIMORGH native] Zip entries for MasterDNS from ${downloadedFile.name}:")
            entries.take(60).forEach { println("[SIMORGH native]  - ${it.name} (${it.size} bytes)") }
            val candidate = entries
                .map { it to masterDnsEntryScore(it.name) }
                .filter { (_, score) -> score > 0 }
                .maxByOrNull { (_, score) -> score }
                ?.first
                ?: throw GradleException("Could not find Android arm64 MasterDNS executable inside ${downloadedFile.name}")
            println("[SIMORGH native] Selected MasterDNS entry: ${candidate.name}")
            zip.getInputStream(candidate).use { input ->
                outFile.outputStream().use { output -> input.copyTo(output) }
            }
        }
    } else if (isGzipFile(downloadedFile)) {
        if (!extractMasterDnsCoreFromTarGz(downloadedFile, outFile)) {
            throw GradleException("Could not find Android arm64 MasterDNS executable inside ${downloadedFile.name}")
        }
    } else {
        val preview = runCatching { downloadedFile.readText().take(200) }.getOrDefault("")
        throw GradleException("Downloaded MasterDNS asset is neither ELF, ZIP, nor TAR.GZ. File=${downloadedFile.name} Preview=$preview")
    }
    val len = outFile.length()
    if (len < minBytes) throw GradleException("Extracted MasterDNS core is too small: ${outFile.path} ($len bytes)")
    if (!isElfBinary(outFile)) throw GradleException("Extracted MasterDNS core is not an ELF binary: ${outFile.path}")
    outFile.setExecutable(true, false)
    println("[SIMORGH native] Placed MasterDNS core => ${outFile.path} ($len bytes)")
}

tasks.register("ensureArm64NativeBinaries") {
    group = "build setup"
    description = "Auto-downloads and validates Xray, tun2socks, and MasterDNS arm64 native binaries."
    doLast {
        val outDir = arm64NativeDir.asFile
        outDir.mkdirs()
        val expectedXrayZipSha256 = providers.gradleProperty("simorgh.xrayArm64ZipSha256").orNull
        val expectedTun2socksZipSha256 = providers.gradleProperty("simorgh.tun2socksArm64ZipSha256").orNull
        val refreshXray = providers.gradleProperty("simorgh.refreshXray").orNull?.toBooleanStrictOrNull() ?: true
        val refreshTun2socks = providers.gradleProperty("simorgh.refreshTun2socks").orNull?.toBooleanStrictOrNull() ?: true
        val libXray = File(outDir, "libxray.so")
        val libTun2socks = File(outDir, "libtun2socks.so")
        val libMasterDns = File(outDir, "libmasterdns.so")

        val xrayZip = nativeTmpDir.file("xray-arm64-v8a-v26.6.22.zip").asFile
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
                    "https://github.com/XTLS/Xray-core/releases/download/v26.6.22/Xray-android-arm64-v8a.zip"
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

        if (refreshTun2socks || !libTun2socks.exists() || libTun2socks.length() < 100_000L) {
            val tunZip = nativeTmpDir.file("tun2socks-arm64-v8a-alt.zip").asFile
            downloadWithFallback(
                listOf(
                    "https://github.com/xjasonlyu/tun2socks/releases/download/v2.5.2/tun2socks-linux-arm64.zip",
                    "https://github.com/xjasonlyu/tun2socks/releases/download/v2.5.0/tun2socks-linux-arm64.zip"
                ),
                tunZip,
                "tun2socks/arm64-v8a official 2.5.x alternate"
            )
            verifySha256(tunZip, expectedTun2socksZipSha256, "tun2socks/arm64-v8a zip")
            extractBinaryFromZip(tunZip, "tun2socks", libTun2socks, 100_000L)
        }

        if (!libMasterDns.exists() || libMasterDns.length() < 100_000L || !isElfBinary(libMasterDns)) {
            val masterDnsAsset = nativeTmpDir.file("masterdns-arm64-v8a.download").asFile
            downloadWithFallback(
                masterDnsReleaseAssetUrls(),
                masterDnsAsset,
                "MasterDNS core/arm64-v8a from masterking32/MasterDnsVPN $masterDnsReleaseTag"
            )
            extractMasterDnsCore(masterDnsAsset, libMasterDns, 100_000L)
        }

        validateNativeExecutable("libxray.so", 1_000_000L)
        validateNativeExecutable("libtun2socks.so", 100_000L)
        validateNativeFile("libmasterdns.so", 100_000L)
        validateNativeExecutable("libnipovpn.so", 1_000_000L)
        validateXrayAsset("geosite.dat", 100_000L)
        validateXrayAsset("geoip.dat", 100_000L)
    }
}

tasks.named("preBuild") {
    dependsOn("ensureArm64NativeBinaries")
}
