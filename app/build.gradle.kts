import java.net.URI
import java.util.zip.ZipFile

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

// SIMORGH VPN: Public/Private mode, stable native binaries, liquid glass cards.
// Build automatically downloads native binaries and packages them as jniLibs:
//   app/src/main/jniLibs/arm64-v8a/libxray.so
//   app/src/main/jniLibs/arm64-v8a/libtun2socks.so

android {
    namespace = "com.rkh.vpn"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.rkh.simorgh"
        minSdk = 29
        targetSdk = 35
        versionCode = 12344
        versionName = "1.1.23.44"
    }

    packaging {
        resources.excludes += setOf("META-INF/DEPENDENCIES", "META-INF/LICENSE*", "META-INF/NOTICE*")
        jniLibs.keepDebugSymbols += setOf("**/libxray.so", "**/libtun2socks.so", "**/libnipovpn.so")
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
        debug { isMinifyEnabled = false }
    }

    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
    kotlinOptions { jvmTarget = "17" }
    buildFeatures { compose = true }
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
    implementation("com.google.zxing:core:3.5.3")
    implementation("com.journeyapps:zxing-android-embedded:4.3.0")


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
            println("[SIMORGH native] Downloading $label from $url")
            if (outFile.exists()) outFile.delete()
            URI(url).toURL().openStream().use { input ->
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
    throw GradleException("All download URLs failed for $label. Last error: ${lastError?.message}")
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

fun validateNativeExecutable(fileName: String, minBytes: Long) {
    val file = arm64NativeDir.file(fileName).asFile
    if (!file.exists()) throw GradleException("Missing native binary after setup: ${file.path}")
    if (file.length() < minBytes) throw GradleException("Native binary is too small/invalid: ${file.path} (${file.length()} bytes)")
    println("[SIMORGH native] OK: ${file.path} (${file.length()} bytes)")
}

tasks.register("ensureArm64NativeBinaries") {
    group = "build setup"
    description = "Auto-downloads and validates Xray and tun2socks arm64 native binaries."
    doLast {
        val outDir = arm64NativeDir.asFile
        outDir.mkdirs()
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

        if (!libTun2socks.exists() || libTun2socks.length() < 100_000L) {
            val tunZip = nativeTmpDir.file("tun2socks-arm64-v8a.zip").asFile
            downloadWithFallback(
                listOf(
                    "https://github.com/xjasonlyu/tun2socks/releases/download/v2.6.0/tun2socks-linux-arm64.zip",
                    "https://sourceforge.net/projects/tun2socks.mirror/files/v2.6.0/tun2socks-linux-arm64.zip/download",
                    "https://github.com/xjasonlyu/tun2socks/releases/download/v2.5.2/tun2socks-linux-arm64.zip",
                    "https://sourceforge.net/projects/tun2socks.mirror/files/v2.5.2/tun2socks-linux-arm64.zip/download"
                ),
                tunZip,
                "tun2socks/arm64-v8a"
            )
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
