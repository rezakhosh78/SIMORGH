package com.rkh.vpn

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.net.URL
import java.security.MessageDigest
import javax.net.ssl.HttpsURLConnection

data class SimorghUpdate(
    val version: String,
    val tag: String,
    val releaseUrl: String,
    val downloadUrl: String?,
    val sha256: String?
)

data class SimorghUpdateCheck(
    val installedVersion: String,
    val latestVersion: String,
    val update: SimorghUpdate?
)

enum class SimorghInstallResult { STARTED, PERMISSION_REQUIRED }

object SimorghUpdateManager {
    private const val RELEASE_API = "https://api.github.com/repos/rezakhosh78/SIMORGH/releases/latest"
    private const val MAX_APK_BYTES = 350L * 1024L * 1024L

    @Suppress("DEPRECATION")
    suspend fun checkForUpdate(context: Context): SimorghUpdateCheck = withContext(Dispatchers.IO) {
        val installedVersion = context.packageManager.getPackageInfo(context.packageName, 0).versionName.orEmpty()
        val connection = (URL(RELEASE_API).openConnection() as HttpsURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = 15_000
            readTimeout = 20_000
            setRequestProperty("Accept", "application/vnd.github+json")
            setRequestProperty("User-Agent", "SIMORGH-Android/$installedVersion")
            setRequestProperty("X-GitHub-Api-Version", "2022-11-28")
        }
        try {
            val responseCode = connection.responseCode
            if (responseCode !in 200..299) throw IOException("GitHub returned HTTP $responseCode")
            val release = JSONObject(connection.inputStream.bufferedReader().use { it.readText() })
            if (release.optBoolean("draft", false)) throw IOException("Latest release is a draft")

            val tag = release.optString("tag_name").trim()
            val latestVersion = tag.removePrefix("v").removePrefix("V")
            if (latestVersion.isBlank()) throw IOException("Release has no version tag")

            val assets = release.optJSONArray("assets")
            val apkAssets = (0 until (assets?.length() ?: 0))
                .mapNotNull { index -> assets?.optJSONObject(index) }
                .filter { it.optString("name").endsWith(".apk", ignoreCase = true) }
            val apkAsset = apkAssets.firstOrNull { asset ->
                asset.optString("name").lowercase().let { it.contains("arm64") || it.contains("aarch64") }
            } ?: apkAssets.firstOrNull { it.optString("name").contains("universal", ignoreCase = true) }
                ?: apkAssets.singleOrNull()

            val htmlUrl = release.optString("html_url", "https://github.com/rezakhosh78/SIMORGH/releases")
            val update = if (compareVersions(latestVersion, installedVersion) > 0) {
                SimorghUpdate(
                    version = latestVersion,
                    tag = tag,
                    releaseUrl = htmlUrl,
                    downloadUrl = apkAsset?.optString("browser_download_url")?.takeIf { it.startsWith("https://") },
                    sha256 = apkAsset?.optString("digest")?.removePrefix("sha256:")?.takeIf { it.matches(Regex("[a-fA-F0-9]{64}")) }
                )
            } else null

            SimorghUpdateCheck(installedVersion, latestVersion, update)
        } finally {
            connection.disconnect()
        }
    }

    suspend fun downloadApk(context: Context, update: SimorghUpdate): File = withContext(Dispatchers.IO) {
        val downloadUrl = update.downloadUrl ?: throw IOException("This release does not include an APK download")
        val parsed = Uri.parse(downloadUrl)
        if (parsed.scheme != "https" || parsed.host != "github.com") throw IOException("The APK link is not a trusted GitHub release URL")

        val connection = (URL(downloadUrl).openConnection() as HttpsURLConnection).apply {
            instanceFollowRedirects = true
            connectTimeout = 20_000
            readTimeout = 45_000
            setRequestProperty("User-Agent", "SIMORGH-Android")
            setRequestProperty("Accept", "application/octet-stream")
        }
        val outputDir = File(context.cacheDir, "updates").apply { if (!exists() && !mkdirs()) throw IOException("Could not create update cache") }
        val safeVersion = update.version.replace(Regex("[^A-Za-z0-9._-]"), "_")
        val outputFile = File(outputDir, "simorgh-$safeVersion.apk")
        val temporaryFile = File(outputDir, "simorgh-$safeVersion.apk.part")
        try {
            val status = connection.responseCode
            val finalHost = Uri.parse(connection.url.toString()).host.orEmpty()
            val allowedHosts = setOf("github.com", "release-assets.githubusercontent.com", "objects.githubusercontent.com")
            if (status !in 200..299) throw IOException("APK download failed with HTTP $status")
            if (finalHost !in allowedHosts) throw IOException("APK download redirected outside GitHub")
            val declaredLength = connection.contentLengthLong
            if (declaredLength > MAX_APK_BYTES) throw IOException("APK is larger than the allowed download size")

            val digest = MessageDigest.getInstance("SHA-256")
            var totalBytes = 0L
            connection.inputStream.buffered().use { input ->
                temporaryFile.outputStream().buffered().use { output ->
                    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                    while (true) {
                        val count = input.read(buffer)
                        if (count < 0) break
                        totalBytes += count
                        if (totalBytes > MAX_APK_BYTES) throw IOException("APK is larger than the allowed download size")
                        digest.update(buffer, 0, count)
                        output.write(buffer, 0, count)
                    }
                }
            }
            if (totalBytes == 0L) throw IOException("The downloaded APK is empty")
            val actualSha256 = digest.digest().joinToString("") { byte -> "%02x".format(byte) }
            if (update.sha256 != null && !actualSha256.equals(update.sha256, ignoreCase = true)) {
                throw IOException("The APK checksum does not match GitHub's release metadata")
            }
            if (outputFile.exists() && !outputFile.delete()) throw IOException("Could not replace the cached APK")
            if (!temporaryFile.renameTo(outputFile)) throw IOException("Could not finish saving the APK")
            outputFile
        } catch (error: Throwable) {
            temporaryFile.delete()
            throw error
        } finally {
            connection.disconnect()
        }
    }

    fun canInstallPackages(context: Context): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.O || context.packageManager.canRequestPackageInstalls()

    fun installOrRequestPermission(context: Context, apkFile: File): SimorghInstallResult {
        if (!canInstallPackages(context)) {
            val settingsIntent = Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${context.packageName}"))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(settingsIntent)
            return SimorghInstallResult.PERMISSION_REQUIRED
        }

        val apkUri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", apkFile)
        val installIntent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(apkUri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(installIntent)
        return SimorghInstallResult.STARTED
    }

    private fun compareVersions(left: String, right: String): Int {
        val leftParts = Regex("\\d+").findAll(left).mapNotNull { it.value.toLongOrNull() }.toList()
        val rightParts = Regex("\\d+").findAll(right).mapNotNull { it.value.toLongOrNull() }.toList()
        if (leftParts.isEmpty() || rightParts.isEmpty()) return left.compareTo(right, ignoreCase = true)
        for (index in 0 until maxOf(leftParts.size, rightParts.size)) {
            val leftPart = leftParts.getOrElse(index) { 0L }
            val rightPart = rightParts.getOrElse(index) { 0L }
            if (leftPart != rightPart) return leftPart.compareTo(rightPart)
        }
        return 0
    }
}
