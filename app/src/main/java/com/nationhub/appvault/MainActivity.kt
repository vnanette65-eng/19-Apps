package com.nationhub.appvault

import android.app.DownloadManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.webkit.DownloadListener
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity

class MainActivity : AppCompatActivity() {
    private lateinit var webView: WebView
    private lateinit var downloadManager: DownloadManager
    private var activeDownloadId: Long = -1L

    private val downloadReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.getLongExtra(DownloadManager.EXTRA_DOWNLOAD_ID, -1L) != activeDownloadId) return
            val uri = downloadManager.getUriForDownloadedFile(activeDownloadId) ?: run {
                Toast.makeText(this@MainActivity, "Download failed. Please try again.", Toast.LENGTH_LONG).show()
                return
            }
            ApkSecurityScanner.scan(this@MainActivity, uri) { report ->
                runOnUiThread {
                    if (!report.isInstallAllowed) {
                        Toast.makeText(this@MainActivity, "AppVault Security Shield blocked this APK: " + report.summary, Toast.LENGTH_LONG).show()
                    } else {
                        Toast.makeText(this@MainActivity, "Basic checks passed. Android will ask you to confirm installation.", Toast.LENGTH_LONG).show()
                        openAndroidInstaller(uri)
                    }
                }
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        downloadManager = getSystemService(DOWNLOAD_SERVICE) as DownloadManager
        webView = WebView(this)
        setContentView(webView)
        webView.settings.javaScriptEnabled = true
        webView.settings.domStorageEnabled = true
        webView.webViewClient = WebViewClient()
        webView.webChromeClient = WebChromeClient()
        webView.setDownloadListener(DownloadListener { url, _, _, mimeType, _ ->
            if (url.endsWith(".apk", ignoreCase = true) || mimeType == "application/vnd.android.package-archive") {
                enqueueApkDownload(url)
            } else {
                Toast.makeText(this, "This download is not an Android APK.", Toast.LENGTH_SHORT).show()
            }
        })

        val appUrl = BuildConfig.APPVAULT_BASE_URL
        if (appUrl.contains("REPLACE-WITH-PUBLISHED")) {
            webView.loadData("<html><body style='font-family:sans-serif;padding:32px'><h2>AppVault is being connected</h2><p>Set APPVAULT_BASE_URL to the published AppVault website URL to finish connecting this Android app.</p></body></html>", "text/html", "UTF-8")
        } else webView.loadUrl(appUrl)

        val filter = IntentFilter(DownloadManager.ACTION_DOWNLOAD_COMPLETE)
        if (Build.VERSION.SDK_INT >= 33) registerReceiver(downloadReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        else @Suppress("DEPRECATION") registerReceiver(downloadReceiver, filter)
    }

    private fun enqueueApkDownload(url: String) {
        val request = DownloadManager.Request(Uri.parse(url))
            .setTitle("AppVault application")
            .setDescription("Downloading APK for security checks")
            .setMimeType("application/vnd.android.package-archive")
            .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
            .setDestinationInExternalFilesDir(this, Environment.DIRECTORY_DOWNLOADS, "appvault-download.apk")
        activeDownloadId = downloadManager.enqueue(request)
        Toast.makeText(this, "Download started. AppVault will scan the APK before installation.", Toast.LENGTH_LONG).show()
    }

    private fun openAndroidInstaller(uri: Uri) {
        val installIntent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        if (installIntent.resolveActivity(packageManager) != null) startActivity(installIntent)
        else Toast.makeText(this, "Android package installer is unavailable on this device.", Toast.LENGTH_LONG).show()
    }

    override fun onDestroy() {
        try { unregisterReceiver(downloadReceiver) } catch (_: IllegalArgumentException) { }
        webView.destroy()
        super.onDestroy()
    }
}
