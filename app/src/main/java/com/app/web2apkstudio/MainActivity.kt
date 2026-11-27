package com.app.web2apkstudio

import android.Manifest
import android.annotation.SuppressLint
import android.app.DownloadManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.util.Base64
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.webkit.CookieManager
import android.webkit.JavascriptInterface
import android.webkit.PermissionRequest
import android.webkit.URLUtil
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL

class MainActivity : AppCompatActivity() {

    companion object {
        private const val TARGET_URL = "https://web2apk-studio.ai.studio"
        private const val TARGET_HOST = "web2apk-studio.ai.studio"
        private const val TOP_HEADER_TRIM_DP = 0
    }

    private lateinit var webView: WebView
    private var fileUploadCallback: ValueCallback<Array<Uri>>? = null
    private var pendingWebPermissionRequest: PermissionRequest? = null

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { _ ->
        pendingWebPermissionRequest?.let { req ->
            grantSupportedWebPermissions(req)
            pendingWebPermissionRequest = null
        }
    }

    private val fileChooserLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        val uris = WebChromeClient.FileChooserParams.parseResult(result.resultCode, result.data)
        fileUploadCallback?.onReceiveValue(uris)
        fileUploadCallback = null
    }

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        configureWindowDisplay()
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        val rootContainer = FrameLayout(this).apply {
            setBackgroundColor(Color.parseColor("#090D16"))
            clipChildren = true
            clipToPadding = true
        }

        webView = WebView(this).apply {
            setBackgroundColor(Color.parseColor("#090D16"))
            overScrollMode = View.OVER_SCROLL_NEVER
            isVerticalScrollBarEnabled = false
            isHorizontalScrollBarEnabled = false

        }

        val trimPx = (TOP_HEADER_TRIM_DP * resources.displayMetrics.density).toInt()
        val webParams = FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT
        ).apply {
            if (trimPx > 0) {
                topMargin = -trimPx
            }
        }
        rootContainer.addView(webView, webParams)
        setContentView(rootContainer)

        configureWebSettings(webView.settings)
        configureWebClients()
        configureNativeDownloadSupport()
        requestRequiredRuntimePermissions()
        setupBackNavigation()

        if (savedInstanceState != null) {
            webView.restoreState(savedInstanceState)
        } else {
            webView.loadUrl(TARGET_URL)
        }
    }

    private fun configureWindowDisplay() {
        val controller = WindowInsetsControllerCompat(window, window.decorView)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        controller.systemBarsBehavior =
            WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        controller.hide(WindowInsetsCompat.Type.systemBars())
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun configureWebSettings(settings: WebSettings) {
        settings.javaScriptEnabled = true
        settings.domStorageEnabled = true
        settings.databaseEnabled = true
        settings.mediaPlaybackRequiresUserGesture = false
        settings.allowFileAccess = true
        settings.allowContentAccess = true
        settings.loadsImagesAutomatically = true
        settings.useWideViewPort = true
        settings.loadWithOverviewMode = true
        settings.setSupportZoom(false)
        settings.builtInZoomControls = false
        settings.displayZoomControls = false
        settings.cacheMode = WebSettings.LOAD_DEFAULT
        settings.mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
        // Uses standard Android WebView mobile User-Agent

        CookieManager.getInstance().apply {
            setAcceptCookie(true)
            setAcceptThirdPartyCookies(webView, true)
        }
    }

    private fun configureWebClients() {
        webView.webViewClient = object : WebViewClient() {

            override fun shouldOverrideUrlLoading(
                view: WebView,
                request: WebResourceRequest
            ): Boolean {
                val uri = request.url ?: return false
                val scheme = uri.scheme?.lowercase() ?: ""
                if (scheme == "http" || scheme == "https") {
                    if (shouldOpenInChrome(uri)) {
                        openInChromeBrowser(uri)
                        return true
                    }
                    // Keep app navigation inside standalone WebView
                    return false
                }
                return handleExternalScheme(uri)
            }

            override fun onPageFinished(view: WebView?, url: String?) {
                super.onPageFinished(view, url)
                view?.evaluateJavascript(
                    "(function(){document.documentElement.style.overscrollBehavior='none';document.documentElement.style.overscrollBehaviorY='none';document.body&&(document.body.style.overscrollBehavior='none',document.body.style.overscrollBehaviorY='none');})();",
                    null
                )
            }

            override fun onReceivedError(
                view: WebView,
                request: WebResourceRequest,
                error: WebResourceError
            ) {
                if (request.isForMainFrame) {
                    val offlineHtml = """
                        <html>
                        <body style="margin:0;background:#090D16;color:#E2E8F0;font-family:sans-serif;display:flex;flex-direction:column;align-items:center;justify-content:center;height:100vh;text-align:center;padding:24px;">
                          <h2 style="margin:0 0 8px 0;font-size:20px;">Connection Unavailable</h2>
                          <p style="margin:0 0 20px 0;color:#94A3B8;font-size:14px;">Unable to reach web2apk-studio.ai.studio.</p>
                          <button onclick="window.location.href='https://web2apk-studio.ai.studio'" style="background:#10B981;color:#06281E;border:none;padding:12px 24px;border-radius:8px;font-weight:600;font-size:14px;">Retry</button>
                        </body>
                        </html>
                    """.trimIndent()
                    view.loadDataWithBaseURL(null, offlineHtml, "text/html", "UTF-8", null)
                }
            }
        }

        webView.webChromeClient = object : WebChromeClient() {
            override fun onPermissionRequest(request: PermissionRequest) {
                runOnUiThread {
                    val neededAndroidPerms = mutableListOf<String>()
                    if (request.resources.contains(PermissionRequest.RESOURCE_AUDIO_CAPTURE) &&
                        ContextCompat.checkSelfPermission(this@MainActivity, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED
                    ) {
                        neededAndroidPerms.add(Manifest.permission.RECORD_AUDIO)
                    }
                    if (request.resources.contains(PermissionRequest.RESOURCE_VIDEO_CAPTURE) &&
                        ContextCompat.checkSelfPermission(this@MainActivity, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED
                    ) {
                        neededAndroidPerms.add(Manifest.permission.CAMERA)
                    }
                    if (neededAndroidPerms.isNotEmpty()) {
                        pendingWebPermissionRequest = request
                        permissionLauncher.launch(neededAndroidPerms.toTypedArray())
                    } else {
                        grantSupportedWebPermissions(request)
                    }
                }
            }

            override fun onShowFileChooser(
                webView: WebView?,
                filePathCallback: ValueCallback<Array<Uri>>?,
                fileChooserParams: FileChooserParams?
            ): Boolean {
                fileUploadCallback?.onReceiveValue(null)
                fileUploadCallback = filePathCallback
                return try {
                    val intent = fileChooserParams?.createIntent() ?: return false
                    fileChooserLauncher.launch(intent)
                    true
                } catch (_: Exception) {
                    fileUploadCallback = null
                    false
                }
            }
        }
    }

    @SuppressLint("JavascriptInterface")
    private fun configureNativeDownloadSupport() {
        webView.addJavascriptInterface(AndroidBridge(), "AndroidBridge")
        webView.setDownloadListener { url, userAgent, contentDisposition, mimeType, _ ->
            try {
                if (url.startsWith("http://") || url.startsWith("https://")) {
                    val fileName = URLUtil.guessFileName(url, contentDisposition, mimeType)
                    val isApk = fileName.lowercase().endsWith(".apk") ||
                        url.lowercase().contains(".apk") ||
                        (mimeType?.contains("android.package-archive") == true)
                    if (isApk) {
                        val apkName = if (fileName.lowercase().endsWith(".apk")) fileName else "app-debug.apk"
                        startNativeApkDownloadAndInstall(url, apkName, "")
                        return@setDownloadListener
                    }
                    val request = DownloadManager.Request(Uri.parse(url)).apply {
                        setMimeType(mimeType ?: "application/octet-stream")
                        addRequestHeader("User-Agent", userAgent)
                        val cookies = CookieManager.getInstance().getCookie(url)
                        if (!cookies.isNullOrEmpty()) {
                            addRequestHeader("Cookie", cookies)
                        }
                        setTitle(fileName)
                        setDescription("Downloading file...")
                        setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
                        setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, fileName)
                    }
                    val dm = getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
                    dm.enqueue(request)
                    Toast.makeText(this, "Downloading $fileName...", Toast.LENGTH_SHORT).show()
                }
            } catch (_: Exception) {
                Toast.makeText(this, "Download started", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun startNativeApkDownloadAndInstall(downloadUrl: String, filename: String, authHeader: String) {
        val safeName = filename.replace(Regex("[^a-zA-Z0-9._-]"), "_").let {
            if (it.lowercase().endsWith(".apk")) it else "$it.apk"
        }
        runOnUiThread {
            Toast.makeText(this, "Downloading $safeName...", Toast.LENGTH_SHORT).show()
        }
        Thread {
            try {
                var currentUrl = downloadUrl
                var conn: HttpURLConnection? = null
                for (hop in 0 until 8) {
                    val urlObj = URL(currentUrl)
                    conn = (urlObj.openConnection() as HttpURLConnection).apply {
                        instanceFollowRedirects = false
                        connectTimeout = 20000
                        readTimeout = 60000
                        setRequestProperty("User-Agent", "Web2App-Android")
                        if (hop == 0 && authHeader.isNotBlank()) {
                            setRequestProperty("Authorization", authHeader)
                            setRequestProperty("Accept", "application/octet-stream")
                        }
                    }
                    val code = conn.responseCode
                    if (code == 301 || code == 302 || code == 303 || code == 307 || code == 308) {
                        val nextLoc = conn.getHeaderField("Location")
                        conn.disconnect()
                        if (nextLoc.isNullOrBlank()) break
                        currentUrl = URL(urlObj, nextLoc).toString()
                        continue
                    }
                    break
                }

                if (conn == null || conn.responseCode !in 200..299) {
                    runOnUiThread {
                        Toast.makeText(this, "Download failed, opening in Chrome...", Toast.LENGTH_SHORT).show()
                        openInChromeBrowser(Uri.parse(downloadUrl))
                    }
                    return@Thread
                }

                val downloadsDir = getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS) ?: cacheDir
                val outFile = File(downloadsDir, safeName)
                val input: InputStream = conn.inputStream
                FileOutputStream(outFile).use { fos ->
                    val buffer = ByteArray(16384)
                    var read: Int
                    while (input.read(buffer).also { read = it } != -1) {
                        fos.write(buffer, 0, read)
                    }
                    fos.flush()
                }
                input.close()
                conn.disconnect()

                runOnUiThread {
                    launchApkInstaller(outFile)
                }
            } catch (_: Exception) {
                runOnUiThread {
                    Toast.makeText(this, "Opening download in Chrome...", Toast.LENGTH_SHORT).show()
                    openInChromeBrowser(Uri.parse(downloadUrl))
                }
            }
        }.start()
    }

    private fun launchApkInstaller(apkFile: File) {
        try {
            val apkUri = FileProvider.getUriForFile(
                this,
                "${packageName}.fileprovider",
                apkFile
            )
            val installIntent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(apkUri, "application/vnd.android.package-archive")
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            startActivity(installIntent)
        } catch (_: Exception) {
            Toast.makeText(this, "Saved ${apkFile.name} to app Downloads", Toast.LENGTH_LONG).show()
        }
    }

    private fun shouldOpenInChrome(uri: Uri): Boolean {
        val host = uri.host?.lowercase() ?: ""
        val full = uri.toString().lowercase()
        if (host.endsWith("uptodown.com")
            || host.endsWith("apkpure.com")
            || host.endsWith("indusappstore.com")
            || host.endsWith("phonepeappstore.com")
            || host.endsWith("amazon.com")
            || host.endsWith("samsungapps.com")
            || host.endsWith("itch.io")
            || host.endsWith("huawei.com")
            || host.endsWith("f-droid.org")
        ) {
            return true
        }
        if (host == "github.com" && (full.contains("/settings")
                || full.contains("/actions")
                || full.contains("/login"))
        ) {
            return true
        }
        return false
    }

    private fun openInChromeBrowser(uri: Uri) {
        try {
            val chromeIntent = Intent(Intent.ACTION_VIEW, uri).apply {
                setPackage("com.android.chrome")
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            startActivity(chromeIntent)
        } catch (_: Exception) {
            openExternalUri(uri)
        }
    }

    inner class AndroidBridge {
        @JavascriptInterface
        fun openInChrome(url: String) {
            runOnUiThread {
                openInChromeBrowser(Uri.parse(url))
            }
        }

        @JavascriptInterface
        fun downloadAndInstallApk(downloadUrl: String, filename: String, authHeader: String?) {
            startNativeApkDownloadAndInstall(downloadUrl, filename, authHeader ?: "")
        }

        @JavascriptInterface
        fun saveBase64File(base64Data: String, filename: String, mimeType: String) {
            runOnUiThread {
                try {
                    val cleanB64 = if (base64Data.contains(",")) base64Data.substringAfter(",") else base64Data
                    val bytes = Base64.decode(cleanB64, Base64.DEFAULT)
                    val safeName = filename.replace(Regex("[^a-zA-Z0-9._-]"), "_")
                    val downloadsDir = getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS) ?: cacheDir
                    val outFile = File(downloadsDir, safeName)
                    FileOutputStream(outFile).use { it.write(bytes) }

                    if (safeName.lowercase().endsWith(".apk") || mimeType.contains("android.package-archive")) {
                        launchApkInstaller(outFile)
                    } else {
                        Toast.makeText(this@MainActivity, "Saved $safeName", Toast.LENGTH_SHORT).show()
                    }
                } catch (_: Exception) {
                    Toast.makeText(this@MainActivity, "Unable to save $filename", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    private fun grantSupportedWebPermissions(request: PermissionRequest) {
        val resources = request.resources
        val granted = mutableListOf<String>()

                    if (resources.contains(PermissionRequest.RESOURCE_AUDIO_CAPTURE)) {
                        granted.add(PermissionRequest.RESOURCE_AUDIO_CAPTURE)
                    }

                    if (resources.contains(PermissionRequest.RESOURCE_VIDEO_CAPTURE)) {
                        granted.add(PermissionRequest.RESOURCE_VIDEO_CAPTURE)
                    }
        if (granted.isNotEmpty()) {
            request.grant(granted.toTypedArray())
        } else {
            request.deny()
        }
    }

    private fun handleExternalScheme(uri: Uri): Boolean {
        return try {
            startActivity(Intent(Intent.ACTION_VIEW, uri))
            true
        } catch (_: Exception) {
            true
        }
    }

    private fun openExternalUri(uri: Uri) {
        try {
            startActivity(Intent(Intent.ACTION_VIEW, uri))
        } catch (_: Exception) {
            // No handler installed on device
        }
    }

    private fun requestRequiredRuntimePermissions() {
        val toRequest = mutableListOf<String>()
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            toRequest.add(Manifest.permission.RECORD_AUDIO)
        }
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            toRequest.add(Manifest.permission.CAMERA)
        }
        if (toRequest.isNotEmpty()) {
            permissionLauncher.launch(toRequest.toTypedArray())
        }
    }

    private fun setupBackNavigation() {
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (webView.canGoBack()) {
                    webView.goBack()
                } else {
                    isEnabled = false
                    onBackPressedDispatcher.onBackPressed()
                }
            }
        })
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        webView.saveState(outState)
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) {
            configureWindowDisplay()
        }
    }

    override fun onResume() {
        super.onResume()
        webView.onResume()
    }

    override fun onPause() {
        webView.onPause()
        super.onPause()
    }

    override fun onDestroy() {
        webView.destroy()
        super.onDestroy()
    }
}
