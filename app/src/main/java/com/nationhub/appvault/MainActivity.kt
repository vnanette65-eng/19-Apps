package com.nationhub.appvault

import android.app.Activity
import android.app.AlertDialog
import android.app.DownloadManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.view.Gravity
import android.view.View
import android.webkit.DownloadListener
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast

class MainActivity : Activity() {
    private lateinit var root: FrameLayout
    private lateinit var webView: WebView
    private lateinit var loadingPanel: LinearLayout
    private lateinit var errorPanel: LinearLayout
    private lateinit var pageProgress: ProgressBar
    private lateinit var downloadManager: DownloadManager
    private var activeDownloadId: Long = -1L
    private var pageHasError = false

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
                        AlertDialog.Builder(this@MainActivity)
                            .setTitle("Installation blocked")
                            .setMessage("Nationestore could not verify this APK's basic package information. It will not be opened.\n\n${report.summary}")
                            .setPositiveButton("OK", null)
                            .show()
                    } else {
                        val details = buildString {
                            append("Package: ${report.packageName ?: "Unknown"}\n")
                            append("Version: ${report.versionName ?: "Unknown"}\n")
                            append("SHA-256: ${report.sha256}\n\n")
                            append(if (report.warnings.isEmpty()) "No basic static-check warnings were found." else "Warnings:\n• " + report.warnings.joinToString("\n• "))
                            append("\n\nThis is a basic static check, not a full antivirus scan. Android will perform its own checks and ask you to confirm installation.")
                        }
                        AlertDialog.Builder(this@MainActivity)
                            .setTitle("Nationestore Security Shield")
                            .setMessage(details)
                            .setNegativeButton("Cancel", null)
                            .setPositiveButton("Continue to Android installer") { _, _ -> openAndroidInstaller(uri) }
                            .show()
                    }
                }
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = Color.rgb(9, 17, 31)
        window.navigationBarColor = Color.rgb(9, 17, 31)
        @Suppress("DEPRECATION")
        window.decorView.systemUiVisibility = 0

        downloadManager = getSystemService(DOWNLOAD_SERVICE) as DownloadManager
        buildInterface()
        configureWebView()

        val filter = IntentFilter(DownloadManager.ACTION_DOWNLOAD_COMPLETE)
        if (Build.VERSION.SDK_INT >= 33) registerReceiver(downloadReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        else @Suppress("DEPRECATION") registerReceiver(downloadReceiver, filter)

        if (savedInstanceState != null) {
            webView.restoreState(savedInstanceState)
        } else {
            loadStore()
        }
    }

    private fun buildInterface() {
        root = FrameLayout(this).apply { setBackgroundColor(Color.rgb(9, 17, 31)) }
        webView = WebView(this).apply { visibility = View.INVISIBLE }
        root.addView(webView, FrameLayout.LayoutParams(-1, -1))

        pageProgress = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 100
            progress = 0
            visibility = View.GONE
        }
        root.addView(pageProgress, FrameLayout.LayoutParams(-1, dp(3), Gravity.TOP))

        loadingPanel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(dp(28), dp(28), dp(28), dp(28))
            setBackgroundColor(Color.rgb(9, 17, 31))
        }
        val brand = TextView(this).apply {
            text = "NATIONESTORE"
            textSize = 27f
            setTextColor(Color.WHITE)
            letterSpacing = 0.16f
            gravity = Gravity.CENTER
        }
        val subtitle = TextView(this).apply {
            text = "Discover. Download. Enjoy."
            textSize = 14f
            setTextColor(Color.rgb(177, 190, 209))
            gravity = Gravity.CENTER
            setPadding(0, dp(10), 0, dp(28))
        }
        val spinner = ProgressBar(this).apply { isIndeterminate = true }
        loadingPanel.addView(brand)
        loadingPanel.addView(subtitle)
        loadingPanel.addView(spinner, LinearLayout.LayoutParams(dp(34), dp(34)))
        root.addView(loadingPanel, FrameLayout.LayoutParams(-1, -1))

        errorPanel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(dp(30), dp(30), dp(30), dp(30))
            setBackgroundColor(Color.rgb(9, 17, 31))
            visibility = View.GONE
        }
        val errorTitle = TextView(this).apply {
            text = "You’re offline or the store can’t be reached"
            textSize = 20f
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
        }
        val errorMessage = TextView(this).apply {
            text = "Check your internet connection, then try again."
            textSize = 14f
            setTextColor(Color.rgb(177, 190, 209))
            gravity = Gravity.CENTER
            setPadding(0, dp(12), 0, dp(22))
        }
        val retry = Button(this).apply {
            text = "Try again"
            setOnClickListener { loadStore() }
        }
        errorPanel.addView(errorTitle)
        errorPanel.addView(errorMessage)
        errorPanel.addView(retry)
        root.addView(errorPanel, FrameLayout.LayoutParams(-1, -1))
        setContentView(root)
    }

    private fun configureWebView() {
        webView.settings.javaScriptEnabled = true
        webView.settings.domStorageEnabled = true
        webView.settings.loadsImagesAutomatically = true
        webView.settings.javaScriptCanOpenWindowsAutomatically = false
        webView.settings.setSupportMultipleWindows(false)
        webView.settings.allowFileAccess = false
        webView.settings.allowContentAccess = true
        webView.webViewClient = object : WebViewClient() {
            override fun onPageStarted(view: WebView?, url: String?, favicon: android.graphics.Bitmap?) {
                pageHasError = false
                showLoading()
            }

            override fun onPageFinished(view: WebView?, url: String?) {
                hideBase44BadgeInNativeApp()
                if (!pageHasError) {
                    loadingPanel.visibility = View.GONE
                    errorPanel.visibility = View.GONE
                    webView.visibility = View.VISIBLE
                    pageProgress.visibility = View.GONE
                }
            }

            override fun onReceivedError(view: WebView?, request: WebResourceRequest?, error: android.webkit.WebResourceError?) {
                if (request?.isForMainFrame == true) showError()
            }
        }
        webView.webChromeClient = object : WebChromeClient() {
            override fun onProgressChanged(view: WebView?, newProgress: Int) {
                if (newProgress in 1..99) {
                    pageProgress.visibility = View.VISIBLE
                    pageProgress.progress = newProgress
                } else {
                    pageProgress.visibility = View.GONE
                }
            }
        }
        webView.setDownloadListener(DownloadListener { url, _, contentDisposition, mimeType, _ ->
            val isApk = url.endsWith(".apk", ignoreCase = true) ||
                mimeType.equals("application/vnd.android.package-archive", ignoreCase = true)
            if (isApk) enqueueApkDownload(url, contentDisposition)
            else Toast.makeText(this, "This file is not an Android APK.", Toast.LENGTH_SHORT).show()
        })
    }

    /**
     * Hides only the Base44 promotional badge inside the Android WebView.
     * This does not modify the website itself or remove other site content.
     */
    private fun hideBase44BadgeInNativeApp() {
        val script = """
            (function() {
              function hideBadge() {
                var pattern = /(?:made|built|powered)\s+with\s+base44/i;
                var nodes = document.querySelectorAll('a,button,div,span,[role="link"]');
                nodes.forEach(function(node) {
                  var text = (node.innerText || node.textContent || '').trim();
                  if (!text || text.length > 100 || !pattern.test(text)) return;
                  var target = node;
                  for (var i = 0; i < 4 && target.parentElement; i++) {
                    var style = window.getComputedStyle(target);
                    var rect = target.getBoundingClientRect();
                    if (style.position === 'fixed' || /badge|branding|watermark/i.test((target.className || '') + ' ' + (target.id || ''))) break;
                    if (rect.width > 360 || rect.height > 100) break;
                    target = target.parentElement;
                  }
                  target.style.setProperty('display', 'none', 'important');
                  target.setAttribute('aria-hidden', 'true');
                });
              }
              hideBadge();
              if (!window.__nationestoreBadgeObserver) {
                window.__nationestoreBadgeObserver = new MutationObserver(hideBadge);
                window.__nationestoreBadgeObserver.observe(document.documentElement, { childList: true, subtree: true });
              }
            })();
        """.trimIndent()
        webView.evaluateJavascript(script, null)
    }

    private fun loadStore() {
        val appUrl = BuildConfig.APPVAULT_BASE_URL
        if (appUrl.contains("REPLACE-WITH-PUBLISHED")) {
            showError()
            Toast.makeText(this, "The published Nationestore website URL still needs to be connected.", Toast.LENGTH_LONG).show()
            return
        }
        pageHasError = false
        showLoading()
        webView.loadUrl(appUrl)
    }

    private fun showLoading() {
        errorPanel.visibility = View.GONE
        webView.visibility = View.INVISIBLE
        loadingPanel.visibility = View.VISIBLE
    }

    private fun showError() {
        pageHasError = true
        loadingPanel.visibility = View.GONE
        webView.visibility = View.GONE
        pageProgress.visibility = View.GONE
        errorPanel.visibility = View.VISIBLE
    }

    private fun enqueueApkDownload(url: String, contentDisposition: String?) {
        val parsed = Uri.parse(url)
        if (parsed.scheme != "https") {
            Toast.makeText(this, "For safety, APK downloads must use a secure HTTPS link.", Toast.LENGTH_LONG).show()
            return
        }
        val request = DownloadManager.Request(parsed)
            .setTitle("Nationestore app download")
            .setDescription("Downloading APK for basic security checks")
            .setMimeType("application/vnd.android.package-archive")
            .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
            .setDestinationInExternalFilesDir(this, Environment.DIRECTORY_DOWNLOADS, "nationestore-${System.currentTimeMillis()}.apk")
        activeDownloadId = downloadManager.enqueue(request)
        Toast.makeText(this, "Download started. You’ll be asked to review the security check before installation.", Toast.LENGTH_LONG).show()
    }

    private fun openAndroidInstaller(uri: Uri) {
        val installIntent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        if (installIntent.resolveActivity(packageManager) != null) startActivity(installIntent)
        else Toast.makeText(this, "Android package installer is unavailable on this device.", Toast.LENGTH_LONG).show()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        webView.saveState(outState)
        super.onSaveInstanceState(outState)
    }

    @Deprecated("Deprecated in Android API 33")
    override fun onBackPressed() {
        if (::webView.isInitialized && webView.canGoBack()) webView.goBack()
        else super.onBackPressed()
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    override fun onDestroy() {
        try { unregisterReceiver(downloadReceiver) } catch (_: IllegalArgumentException) { }
        if (::webView.isInitialized) {
            webView.stopLoading()
            webView.destroy()
        }
        super.onDestroy()
    }
}
