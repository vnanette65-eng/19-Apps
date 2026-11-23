package com.nationhub.appvault

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.content.pm.PackageInstaller
import java.io.File
import java.security.MessageDigest
import java.util.zip.ZipFile

/** Static inspection and split-package installation for XAPK/APKS/APKM archives. Not an antivirus. */
object AndroidPackageInstaller {
    fun scanAndInstall(context: Context, archiveUri: Uri, callback: (String) -> Unit) {
        Thread {
            val dir = File(context.cacheDir, "nationestore-archive-scan").apply { deleteRecursively(); mkdirs() }
            try {
                val archive = File(dir, "package.zip")
                context.contentResolver.openInputStream(archiveUri).use { input ->
                    requireNotNull(input) { "Cannot read package archive" }
                    archive.outputStream().use { output -> input.copyTo(output) }
                }
                val apkFiles = mutableListOf<File>()
                ZipFile(archive).use { zip ->
                    val entries = zip.entries()
                    while (entries.hasMoreElements()) {
                        val entry = entries.nextElement()
                        if (entry.isDirectory || !entry.name.lowercase().endsWith(".apk")) continue
                        require(!entry.name.startsWith("/") && !entry.name.split("/").contains("..")) { "Unsafe archive path" }
                        val out = File(dir, "part-${apkFiles.size}.apk")
                        zip.getInputStream(entry).use { input -> out.outputStream().use { output -> input.copyTo(output) } }
                        apkFiles += out
                    }
                }
                require(apkFiles.isNotEmpty()) { "No APK packages were found inside this archive." }
                val packages = apkFiles.map { packageInfo(context, it) }
                val packageNames = packages.map { it.packageName }.distinct()
                require(packageNames.size == 1) { "The archive contains APKs from different applications." }
                val baseCount = packages.count { it.splitName.isNullOrBlank() }
                require(baseCount == 1) { "The archive must contain exactly one base APK and its optional split APKs." }
                val hash = MessageDigest.getInstance("SHA-256")
                apkFiles.forEach { file ->
                    file.inputStream().use { input ->
                        val buffer = ByteArray(8192)
                        while (true) { val n = input.read(buffer); if (n <= 0) break; hash.update(buffer, 0, n) }
                    }
                }
                val fingerprint = hash.digest().joinToString("") { "%02x".format(it) }
                val report = "Archive contains ${apkFiles.size} APK component(s).\nPackage: ${packageNames.first()}\nCombined SHA-256: $fingerprint\n\nAll components passed basic package metadata checks. This is not malware detection or a full antivirus scan. Android will verify signatures and request installation confirmation."
                callback(report)
            } catch (e: Exception) {
                callback("Archive inspection failed: ${e.message ?: "Unknown error"}. The package will not be installed.")
            } finally {
                dir.deleteRecursively()
            }
        }.start()
    }

    fun installArchive(context: Context, archiveUri: Uri) {
        Thread {
            val dir = File(context.cacheDir, "nationestore-install-${System.currentTimeMillis()}").apply { mkdirs() }
            try {
                val archive = File(dir, "package.zip")
                context.contentResolver.openInputStream(archiveUri).use { input ->
                    requireNotNull(input) { "Cannot read package archive" }
                    archive.outputStream().use { output -> input.copyTo(output) }
                }
                val parts = mutableListOf<Pair<String, File>>()
                ZipFile(archive).use { zip ->
                    val entries = zip.entries()
                    while (entries.hasMoreElements()) {
                        val entry = entries.nextElement()
                        if (entry.isDirectory || !entry.name.lowercase().endsWith(".apk")) continue
                        require(!entry.name.startsWith("/") && !entry.name.split("/").contains("..")) { "Unsafe archive path" }
                        val file = File(dir, "split-${parts.size}.apk")
                        zip.getInputStream(entry).use { input -> file.outputStream().use { output -> input.copyTo(output) } }
                        val info = packageInfo(context, file)
                        parts += (info.splitName ?: "base") to file
                    }
                }
                require(parts.isNotEmpty()) { "No APK files found." }
                val names = parts.map { packageInfo(context, it.second).packageName }.distinct()
                require(names.size == 1 && parts.count { it.first == "base" } == 1) { "Invalid split APK set." }

                val installer = context.packageManager.packageInstaller
                val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL)
                val sessionId = installer.createSession(params)
                installer.openSession(sessionId).use { session ->
                    parts.forEach { (splitName, file) ->
                        session.openWrite(splitName, 0, file.length()).use { output ->
                            file.inputStream().use { input -> input.copyTo(output) }
                            session.fsync(output)
                        }
                    }
                    val intent = Intent(context, PackageInstallStatusReceiver::class.java)
                    val pending = PendingIntent.getBroadcast(
                        context, sessionId, intent,
                        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE
                    )
                    session.commit(pending.intentSender)
                }
            } catch (e: Exception) {
                android.os.Handler(context.mainLooper).post {
                    android.widget.Toast.makeText(context, "Installation could not start: ${e.message}", android.widget.Toast.LENGTH_LONG).show()
                }
            } finally {
                dir.deleteRecursively()
            }
        }.start()
    }

    private fun packageInfo(context: Context, file: File): PackageInfo {
        val flags = if (Build.VERSION.SDK_INT >= 33)
            PackageManager.PackageInfoFlags.of(PackageManager.GET_PERMISSIONS.toLong())
        else @Suppress("DEPRECATION") PackageManager.GET_PERMISSIONS
        val info = if (Build.VERSION.SDK_INT >= 33)
            context.packageManager.getPackageArchiveInfo(file.absolutePath, flags as PackageManager.PackageInfoFlags)
        else @Suppress("DEPRECATION") context.packageManager.getPackageArchiveInfo(file.absolutePath, flags as Int)
        return requireNotNull(info) { "An APK component has unreadable package metadata." }
    }
}
