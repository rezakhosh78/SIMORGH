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
// Optional SHA-256 verification can be enabled with Gradle properties:
//   -Psimorgh.xrayArm64ZipSha256=<expected xray zip sha256>
//   -Psimorgh.tun2socksArm64ZipSha256=<expected tun2socks zip sha256>
//   -Psimorgh.refreshTun2socks=true|false  (default: true)
// Build automatically downloads native binaries and packages them as jniLibs:
//   app/src/main/jniLibs/arm64-v8a/libxray.so
//   app/src/main/jniLibs/arm64-v8a/libtun2socks.so

android {
    namespace = "com.rkh.vpn"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.rkh.simorgh"
        minSdk = 29
        targetSdk = 36
        versionCode = 12345
        versionName = "1.1.23.45"
    }

    packaging {
        resources.excludes += setOf("META-INF/DEPENDENCIES", "META-INF/LICENSE*", "META-INF/NOTICE*")
        jniLibs.useLegacyPackaging = true
        jniLibs.keepDebugSymbols += setOf("**/libxray.so", "**/libtun2socks.so", "**/libnipovpn.so")
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

tasks.register("ensureArm64NativeBinaries") {
    group = "build setup"
    description = "Auto-downloads and validates Xray and tun2socks arm64 native binaries."
    doLast {
        val outDir = arm64NativeDir.asFile
        outDir.mkdirs()
        val expectedXrayZipSha256 = providers.gradleProperty("simorgh.xrayArm64ZipSha256").orNull
        val expectedTun2socksZipSha256 = providers.gradleProperty("simorgh.tun2socksArm64ZipSha256").orNull
        val refreshTun2socks = providers.gradleProperty("simorgh.refreshTun2socks").orNull?.toBooleanStrictOrNull() ?: true
        val libXray = File(outDir, "libxray.so")
        val libTun2socks = File(outDir, "libtun2socks.so")

        val xrayZip = nativeTmpDir.file("xray-arm64-v8a.zip").asFile
        val geositeDat = xrayAssetDir.file("geosite.dat").asFile
        val geoipDat = xrayAssetDir.file("geoip.dat").asFile
        val needsXrayZip =
            !libXray.exists() || libXray.length() < 1_000_000L ||
            !geositeDat.exists() || geositeDat.length() < 100_000L ||
            !geoipDat.exists() || geoipDat.length() < 100_000L

        if (needsXrayZip) {
            downloadWithFallback(
                listOf(
                    "https://github.com/XTLS/Xray-core/releases/download/v26.6.1/Xray-android-arm64-v8a.zip"
                ),
                xrayZip,
                "xray/arm64-v8a + geo assets"
            )
            verifySha256(xrayZip, expectedXrayZipSha256, "xray/arm64-v8a zip")
        }

        if (!libXray.exists() || libXray.length() < 1_000_000L) {
            extractBinaryFromZip(xrayZip, "xray", libXray, 1_000_000L)
        }

        if (!geositeDat.exists() || geositeDat.length() < 100_000L) {
            extractAssetFromZip(xrayZip, "geosite.dat", geositeDat, 100_000L)
        }
        if (!geoipDat.exists() || geoipDat.length() < 100_000L) {
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

        validateNativeExecutable("libxray.so", 1_000_000L)
        validateNativeExecutable("libtun2socks.so", 100_000L)
        validateNativeExecutable("libnipovpn.so", 1_000_000L)
        validateXrayAsset("geosite.dat", 100_000L)
        validateXrayAsset("geoip.dat", 100_000L)
    }
}

tasks.named("preBuild") {
    dependsOn("ensureArm64NativeBinaries")
}
