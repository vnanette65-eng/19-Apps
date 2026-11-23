package com.nationhub.appvault

import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import java.io.File
import java.security.MessageDigest

data class ApkScanReport(
    val sha256: String,
    val packageName: String?,
    val versionName: String?,
    val permissions: List<String>,
    val warnings: List<String>,
    val isInstallAllowed: Boolean,
    val summary: String
)

/** First-pass static checks only; this is not a full antivirus engine. */
object ApkSecurityScanner {
    fun scan(context: Context, apkUri: Uri, callback: (ApkScanReport) -> Unit) {
        Thread {
            val temp = File(context.cacheDir, "appvault-scan.apk")
            try {
                context.contentResolver.openInputStream(apkUri).use { input ->
                    requireNotNull(input) { "Cannot read downloaded APK" }
                    temp.outputStream().use { output -> input.copyTo(output) }
                }
                val digest = MessageDigest.getInstance("SHA-256")
                val hash = temp.inputStream().use { stream ->
                    val buffer = ByteArray(8192)
                    while (true) {
                        val count = stream.read(buffer)
                        if (count <= 0) break
                        digest.update(buffer, 0, count)
                    }
                    digest.digest().joinToString("") { "%02x".format(it) }
                }
                val info = if (Build.VERSION.SDK_INT >= 33) {
                    context.packageManager.getPackageArchiveInfo(temp.absolutePath,
                        PackageManager.PackageInfoFlags.of(PackageManager.GET_PERMISSIONS.toLong()))
                } else {
                    @Suppress("DEPRECATION")
                    context.packageManager.getPackageArchiveInfo(temp.absolutePath, PackageManager.GET_PERMISSIONS)
                }
                val warnings = mutableListOf<String>()
                if (info == null) warnings += "APK metadata could not be read"
                val permissions = info?.requestedPermissions?.toList().orEmpty()
                val sensitive = permissions.filter { p ->
                    listOf("READ_SMS", "SEND_SMS", "RECORD_AUDIO", "READ_CONTACTS", "ACCESS_FINE_LOCATION", "SYSTEM_ALERT_WINDOW", "REQUEST_INSTALL_PACKAGES").any { p.contains(it) }
                }
                if (sensitive.isNotEmpty()) warnings += "Sensitive permissions requested: " + sensitive.joinToString()
                if (info?.packageName.isNullOrBlank()) warnings += "Package identity is missing"
                val allowed = info != null && !info.packageName.isNullOrBlank()
                val summary = if (warnings.isEmpty()) "No basic static-check warnings" else warnings.joinToString("; ")
                callback(ApkScanReport(hash, info?.packageName, info?.versionName, permissions, warnings, allowed, summary))
            } catch (error: Exception) {
                callback(ApkScanReport("", null, null, emptyList(), listOf(error.message ?: "Scan failed"), false, "Unable to complete basic APK checks"))
            } finally {
                temp.delete()
            }
        }.start()
    }
}
