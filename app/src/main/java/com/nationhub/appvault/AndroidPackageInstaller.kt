package com.nationhub.appvault

import android.content.Context
import android.content.Intent
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.content.pm.PackageInstaller
import android.app.PendingIntent
import android.net.Uri
import java.io.File
import java.security.MessageDigest
import java.util.zip.ZipFile

/** Basic archive validation and Android split-package installation. This is not antivirus. */
object AndroidPackageInstaller {
    fun scanAndInstall(context: Context, archiveUri: Uri, callback: (String) -> Unit) {
        Thread {
            val dir = File(context.cacheDir, "nationestore-archive-scan").apply {
                deleteRecursively()
                mkdirs()
            }
            try {
                val archive = File(dir, "package.zip")
                context.contentResolver.openInputStream(archiveUri).use { input ->
                    requireNotNull(input) { "Cannot read package archive" }
                    archive.outputStream().use { output -> input.copyTo(output) }
                }

                val apkFiles = extractApkParts(archive, dir)
                require(apkFiles.isNotEmpty()) { "No APK packages were found inside this archive." }
                val packages = apkFiles.map { packageInfo(context, it) }
                val packageNames = packages.map { it.packageName }.distinct()
                require(packageNames.size == 1) { "The archive contains APKs from different applications." }
                require(packages.count { it.splitName.isNullOrBlank() } == 1) {
                    "The archive must contain exactly one base APK and its optional split APKs."
                }

                val digest = MessageDigest.getInstance("SHA-256")
                apkFiles.forEach { file ->
                    file.inputStream().use { input ->
                        val buffer = ByteArray(8192)
                        while (true) {
                            val count = input.read(buffer)
                            if (count <= 0) break
                            digest.update(buffer, 0, count)
                        }
                    }
                }
                val hash = digest.digest().joinToString("") { "%02x".format(it) }
                callback(
                    "Archive contains ${apkFiles.size} APK component(s).\n" +
                    "Package: ${packageNames.first()}\nSHA-256: $hash\n\n" +
                    "The APK components passed basic package-metadata checks. This is not malware detection " +
                    "or a full antivirus scan. Android will verify signatures and request installation confirmation."
                )
            } catch (error: Exception) {
                callback("Archive inspection failed: ${error.message ?: "Unknown error"}. The package will not be installed.")
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

                val apkFiles = extractApkParts(archive, dir)
                require(apkFiles.isNotEmpty()) { "No APK files found." }
                val parts = apkFiles.map { file ->
                    val info = packageInfo(context, file)
                    (info.splitName ?: "base") to file
                }
                val packageNames = apkFiles.map { packageInfo(context, it).packageName }.distinct()
                require(packageNames.size == 1 && parts.count { it.first == "base" } == 1) {
                    "Invalid split APK set."
                }

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
                    val statusIntent = Intent(context, PackageInstallStatusReceiver::class.java)
                    val pending = PendingIntent.getBroadcast(
                        context,
                        sessionId,
                        statusIntent,
                        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE
                    )
                    session.commit(pending.intentSender)
                }
            } catch (error: Exception) {
                android.os.Handler(context.mainLooper).post {
                    android.widget.Toast.makeText(
                        context,
                        "Installation could not start: ${error.message ?: "Unknown error"}",
                        android.widget.Toast.LENGTH_LONG
                    ).show()
                }
            } finally {
                dir.deleteRecursively()
            }
        }.start()
    }

    private fun extractApkParts(archive: File, destination: File): List<File> {
        val extracted = mutableListOf<File>()
        ZipFile(archive).use { zip ->
            val entries = zip.entries()
            var totalBytes = 0L
            while (entries.hasMoreElements()) {
                val entry = entries.nextElement()
                if (entry.isDirectory || !entry.name.lowercase().endsWith(".apk")) continue
                val segments = entry.name.replace(92.toChar(), '/').split('/')
                require(!entry.name.startsWith('/') && segments.none { it == ".." }) {
                    "Unsafe archive path detected."
                }
                require(extracted.size < 100) { "Archive contains too many APK components." }
                val outputFile = File(destination, "part-${extracted.size}.apk")
                zip.getInputStream(entry).use { input ->
                    outputFile.outputStream().use { output ->
                        val buffer = ByteArray(8192)
                        while (true) {
                            val count = input.read(buffer)
                            if (count <= 0) break
                            totalBytes += count
                            require(totalBytes <= 1024L * 1024L * 1024L) { "Extracted APK data exceeds the 1 GB safety limit." }
                            output.write(buffer, 0, count)
                        }
                    }
                }
                extracted += outputFile
            }
        }
        return extracted
    }

    private fun packageInfo(context: Context, file: File): PackageInfo {
        val info = if (android.os.Build.VERSION.SDK_INT >= 33) {
            context.packageManager.getPackageArchiveInfo(
                file.absolutePath,
                PackageManager.PackageInfoFlags.of(PackageManager.GET_PERMISSIONS.toLong())
            )
        } else {
            @Suppress("DEPRECATION")
            context.packageManager.getPackageArchiveInfo(
                file.absolutePath,
                PackageManager.GET_PERMISSIONS
            )
        }
        return requireNotNull(info) { "An APK component has unreadable package metadata." }
    }
}
