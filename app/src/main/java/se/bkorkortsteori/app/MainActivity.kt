package se.bkorkortsteori.app

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.app.DownloadManager
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Color
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.Message
import android.provider.MediaStore
import android.util.Log
import android.view.KeyEvent
import android.view.View
import android.view.WindowManager
import android.webkit.CookieManager
import android.webkit.DownloadListener
import android.webkit.URLUtil
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Button
import android.widget.FrameLayout
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.browser.customtabs.CustomTabColorSchemeParams
import androidx.browser.customtabs.CustomTabsIntent
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout
import com.google.firebase.messaging.FirebaseMessaging
import java.io.File
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainActivity : AppCompatActivity() {

    companion object {
        private const val TAG = "BKorkortsteori"
        private const val BASE_URL = "https://bkorkortsteori.se"
        private const val EXTRA_TARGET_URL = "target_url"
    }

    private lateinit var webView: WebView
    private lateinit var swipeRefreshLayout: SwipeRefreshLayout
    private lateinit var offlineLayout: View
    private lateinit var webViewContainer: FrameLayout

    // File upload
    private var fileUploadCallback: ValueCallback<Array<Uri>>? = null
    private var cameraPhotoPath: String? = null

    // Activity result launchers
    private lateinit var fileChooserLauncher: ActivityResultLauncher<Intent>
    private lateinit var cameraPermissionLauncher: ActivityResultLauncher<String>
    private lateinit var notificationPermissionLauncher: ActivityResultLauncher<String>

    // Track if we have loaded the page at least once
    private var hasLoadedPage = false

    // Track if we opened OAuth so we know to refresh on return
    private var openedOAuth = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        setupStatusBar()
        setupViews()
        setupActivityResultLaunchers()
        setupWebView()
        setupSwipeRefresh()
        setupBackNavigation()
        requestNotificationPermission()
        subscribeToFCMTopics()

        // Load target URL from notification or default
        val targetUrl = intent?.getStringExtra(EXTRA_TARGET_URL) ?: BASE_URL
        loadUrl(targetUrl)
    }

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        setIntent(intent)
        // Handle notification tap while app is already open
        val targetUrl = intent?.getStringExtra(EXTRA_TARGET_URL)
        if (!targetUrl.isNullOrEmpty()) {
            loadUrl(targetUrl)
        }
    }

    private fun setupStatusBar() {
        window.addFlags(WindowManager.LayoutParams.FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS)
        window.statusBarColor = Color.parseColor("#0a1628")
        window.navigationBarColor = Color.parseColor("#0a1628")

        WindowCompat.setDecorFitsSystemWindows(window, true)
        val controller = WindowInsetsControllerCompat(window, window.decorView)
        // Light status bar icons = false means white icons on dark background
        controller.isAppearanceLightStatusBars = false
        controller.isAppearanceLightNavigationBars = false
    }

    private fun setupViews() {
        webView = findViewById(R.id.webView)
        swipeRefreshLayout = findViewById(R.id.swipeRefreshLayout)
        offlineLayout = findViewById(R.id.offlineLayout)
        webViewContainer = findViewById(R.id.webViewContainer)

        val retryButton: Button = findViewById(R.id.btnRetry)
        retryButton.setOnClickListener {
            if (isNetworkAvailable()) {
                showWebView()
                webView.reload()
            } else {
                Toast.makeText(this, "Ingen internetanslutning", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun setupActivityResultLaunchers() {
        // File chooser result
        fileChooserLauncher = registerForActivityResult(
            ActivityResultContracts.StartActivityForResult()
        ) { result ->
            if (result.resultCode == Activity.RESULT_OK) {
                val data = result.data
                val results: Array<Uri>?

                if (data == null || data.data == null) {
                    // Camera photo was taken
                    results = if (!cameraPhotoPath.isNullOrEmpty()) {
                        arrayOf(Uri.parse(cameraPhotoPath))
                    } else {
                        null
                    }
                } else {
                    // File was selected from gallery/file picker
                    val dataString = data.dataString
                    results = if (dataString != null) {
                        arrayOf(Uri.parse(dataString))
                    } else {
                        // Handle multiple file selection
                        data.clipData?.let { clipData ->
                            Array(clipData.itemCount) { i ->
                                clipData.getItemAt(i).uri
                            }
                        }
                    }
                }

                fileUploadCallback?.onReceiveValue(results ?: arrayOf())
            } else {
                fileUploadCallback?.onReceiveValue(arrayOf())
            }
            fileUploadCallback = null
        }

        // Camera permission result
        cameraPermissionLauncher = registerForActivityResult(
            ActivityResultContracts.RequestPermission()
        ) { isGranted ->
            if (isGranted) {
                // Permission granted, the file chooser will be shown again
                Log.d(TAG, "Camera permission granted")
            }
        }

        // Notification permission result (Android 13+)
        notificationPermissionLauncher = registerForActivityResult(
            ActivityResultContracts.RequestPermission()
        ) { isGranted ->
            Log.d(TAG, "Notification permission ${if (isGranted) "granted" else "denied"}")
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun setupWebView() {
        val webSettings = webView.settings

        // JavaScript
        webSettings.javaScriptEnabled = true
        webSettings.javaScriptCanOpenWindowsAutomatically = true

        // DOM Storage / localStorage
        webSettings.domStorageEnabled = true

        // Database
        webSettings.databaseEnabled = true

        // Cache
        webSettings.cacheMode = WebSettings.LOAD_DEFAULT

        // File access
        webSettings.allowFileAccess = true
        webSettings.allowContentAccess = true

        // Zoom
        webSettings.setSupportZoom(true)
        webSettings.builtInZoomControls = true
        webSettings.displayZoomControls = false

        // Viewport
        webSettings.useWideViewPort = true
        webSettings.loadWithOverviewMode = true

        // Mixed content (allow HTTPS pages to load HTTP resources if needed)
        webSettings.mixedContentMode = WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE

        // Media
        webSettings.mediaPlaybackRequiresUserGesture = false

        // User Agent - remove WebView markers so Google OAuth works
        val defaultUserAgent = webSettings.userAgentString
        val chromeAgent = defaultUserAgent
            .replace("; wv)", ")")
            .replace(Regex("Version/\\d+\\.\\d+\\s"), "")
        webSettings.userAgentString = "$chromeAgent BKorkortsteoriApp/1.0"

        // Multiple windows support (for target="_blank")
        webSettings.setSupportMultipleWindows(true)

        // Text size
        webSettings.textZoom = 100

        // Cookies
        val cookieManager = CookieManager.getInstance()
        cookieManager.setAcceptCookie(true)
        cookieManager.setAcceptThirdPartyCookies(webView, true)

        // WebView Client
        webView.webViewClient = BKWebViewClient()

        // WebChrome Client (file uploads, progress, new windows)
        webView.webChromeClient = BKWebChromeClient()

        // Download listener
        webView.setDownloadListener(BKDownloadListener())

        // Scroll listener for swipe refresh compatibility
        webView.setOnScrollChangeListener { _, _, scrollY, _, _ ->
            swipeRefreshLayout.isEnabled = scrollY == 0
        }
    }

    private fun setupSwipeRefresh() {
        swipeRefreshLayout.setColorSchemeColors(
            Color.parseColor("#1db954"),
            Color.parseColor("#0a1628")
        )
        swipeRefreshLayout.setOnRefreshListener {
            if (isNetworkAvailable()) {
                webView.reload()
            } else {
                swipeRefreshLayout.isRefreshing = false
                showOfflinePage()
            }
        }
    }

    private fun setupBackNavigation() {
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                when {
                    webView.canGoBack() -> webView.goBack()
                    else -> {
                        isEnabled = false
                        onBackPressedDispatcher.onBackPressed()
                    }
                }
            }
        })
    }

    private fun requestNotificationPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(
                    this, Manifest.permission.POST_NOTIFICATIONS
                ) != PackageManager.PERMISSION_GRANTED
            ) {
                notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        }
    }

    private fun subscribeToFCMTopics() {
        try {
            FirebaseMessaging.getInstance().subscribeToTopic("all_users")
                .addOnCompleteListener { task ->
                    if (task.isSuccessful) {
                        Log.d(TAG, "Subscribed to all_users topic")
                    } else {
                        Log.w(TAG, "Failed to subscribe to topic", task.exception)
                    }
                }

            FirebaseMessaging.getInstance().subscribeToTopic("theory_updates")
                .addOnCompleteListener { task ->
                    if (task.isSuccessful) {
                        Log.d(TAG, "Subscribed to theory_updates topic")
                    }
                }

            // Log FCM token for debugging
            FirebaseMessaging.getInstance().token.addOnCompleteListener { task ->
                if (task.isSuccessful) {
                    Log.d(TAG, "FCM Token: ${task.result}")
                }
            }
        } catch (e: Exception) {
            // Firebase may not be initialized if google-services.json is missing
            Log.w(TAG, "Firebase not initialized - add google-services.json", e)
        }
    }

    private fun loadUrl(url: String) {
        if (isNetworkAvailable()) {
            showWebView()
            webView.loadUrl(url)
        } else {
            showOfflinePage()
        }
    }

    private fun showWebView() {
        webViewContainer.visibility = View.VISIBLE
        offlineLayout.visibility = View.GONE
    }

    private fun showOfflinePage() {
        webViewContainer.visibility = View.GONE
        offlineLayout.visibility = View.VISIBLE
    }

    private fun isNetworkAvailable(): Boolean {
        val connectivityManager =
            getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val network = connectivityManager.activeNetwork ?: return false
        val capabilities = connectivityManager.getNetworkCapabilities(network) ?: return false
        return capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
                capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
    }

    private fun isInternalUrl(url: String): Boolean {
        return url.startsWith(BASE_URL) ||
                url.startsWith("https://www.bkorkortsteori.se") ||
                url.startsWith("https://bkorkortsteori.se")
    }

    private fun isOAuthUrl(url: String): Boolean {
        return url.startsWith("https://accounts.google.com") ||
                url.startsWith("https://www.facebook.com/login") ||
                url.startsWith("https://www.facebook.com/v") ||
                url.startsWith("https://m.facebook.com") ||
                url.contains("oauth") ||
                url.contains("accounts.google.com")
    }

    private fun openInExternalBrowser(url: String) {
        try {
            val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url))
            startActivity(intent)
        } catch (e: ActivityNotFoundException) {
            Toast.makeText(this, "Kan inte öppna länken", Toast.LENGTH_SHORT).show()
        }
    }

    private fun openInCustomTab(url: String) {
        try {
            val colorScheme = CustomTabColorSchemeParams.Builder()
                .setToolbarColor(Color.parseColor("#0a1628"))
                .setNavigationBarColor(Color.parseColor("#0a1628"))
                .build()

            val customTabsIntent = CustomTabsIntent.Builder()
                .setDefaultColorSchemeParams(colorScheme)
                .setShowTitle(true)
                .build()

            customTabsIntent.launchUrl(this, Uri.parse(url))
        } catch (e: Exception) {
            // Fallback to external browser if Custom Tabs not available
            openInExternalBrowser(url)
        }
    }

    @Throws(IOException::class)
    private fun createImageFile(): File {
        val timeStamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())
        val imageFileName = "BK_${timeStamp}_"
        val storageDir = getExternalFilesDir(Environment.DIRECTORY_PICTURES)
            ?: cacheDir
        return File.createTempFile(imageFileName, ".jpg", storageDir)
    }

    override fun onResume() {
        super.onResume()
        webView.onResume()
        CookieManager.getInstance().flush()

        // After returning from OAuth Custom Tab, reload the page to check login state
        if (openedOAuth) {
            openedOAuth = false
            webView.reload()
        }
    }

    override fun onPause() {
        super.onPause()
        webView.onPause()
        CookieManager.getInstance().flush()
    }

    override fun onDestroy() {
        webView.destroy()
        super.onDestroy()
    }

    // =========================================================================
    // Inner Classes
    // =========================================================================

    /**
     * Custom WebViewClient - handles page loading, errors, and URL interception
     */
    private inner class BKWebViewClient : WebViewClient() {

        override fun shouldOverrideUrlLoading(
            view: WebView?,
            request: WebResourceRequest?
        ): Boolean {
            val url = request?.url?.toString() ?: return false

            return when {
                // Internal URLs - load in WebView
                isInternalUrl(url) -> false

                // OAuth URLs - open in Chrome Custom Tab (Google blocks WebView OAuth)
                isOAuthUrl(url) -> {
                    openedOAuth = true
                    openInCustomTab(url)
                    true
                }

                // tel: links
                url.startsWith("tel:") -> {
                    startActivity(Intent(Intent.ACTION_DIAL, Uri.parse(url)))
                    true
                }

                // mailto: links
                url.startsWith("mailto:") -> {
                    startActivity(Intent(Intent.ACTION_SENDTO, Uri.parse(url)))
                    true
                }

                // intent: links (for app deep links)
                url.startsWith("intent:") -> {
                    try {
                        val intent = Intent.parseUri(url, Intent.URI_INTENT_SCHEME)
                        startActivity(intent)
                    } catch (e: Exception) {
                        Log.w(TAG, "Could not handle intent URL: $url", e)
                    }
                    true
                }

                // External URLs - open in browser
                else -> {
                    openInExternalBrowser(url)
                    true
                }
            }
        }

        override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
            super.onPageStarted(view, url, favicon)
            swipeRefreshLayout.isRefreshing = true
        }

        override fun onPageFinished(view: WebView?, url: String?) {
            super.onPageFinished(view, url)
            swipeRefreshLayout.isRefreshing = false
            hasLoadedPage = true

            // Flush cookies after page load
            CookieManager.getInstance().flush()
        }

        override fun onReceivedError(
            view: WebView?,
            request: WebResourceRequest?,
            error: WebResourceError?
        ) {
            super.onReceivedError(view, request, error)

            // Only handle main frame errors
            if (request?.isForMainFrame == true) {
                Log.e(TAG, "WebView error: ${error?.description} (code: ${error?.errorCode})")

                if (!isNetworkAvailable()) {
                    showOfflinePage()
                }
            }
        }
    }

    /**
     * Custom WebChromeClient - handles file uploads, new windows, progress
     */
    private inner class BKWebChromeClient : WebChromeClient() {

        // File upload for Android 5.0+
        override fun onShowFileChooser(
            webView: WebView?,
            filePathCallback: ValueCallback<Array<Uri>>?,
            fileChooserParams: FileChooserParams?
        ): Boolean {
            // Cancel any pending callback
            fileUploadCallback?.onReceiveValue(arrayOf())
            fileUploadCallback = filePathCallback

            // Build intents
            val intentList = mutableListOf<Intent>()

            // Camera intent
            val hasCameraPermission = ContextCompat.checkSelfPermission(
                this@MainActivity, Manifest.permission.CAMERA
            ) == PackageManager.PERMISSION_GRANTED

            if (hasCameraPermission) {
                val captureIntent = Intent(MediaStore.ACTION_IMAGE_CAPTURE)
                try {
                    val photoFile = createImageFile()
                    cameraPhotoPath = "file:${photoFile.absolutePath}"
                    val photoUri = FileProvider.getUriForFile(
                        this@MainActivity,
                        "${packageName}.fileprovider",
                        photoFile
                    )
                    captureIntent.putExtra(MediaStore.EXTRA_OUTPUT, photoUri)
                    intentList.add(captureIntent)
                } catch (e: IOException) {
                    Log.e(TAG, "Error creating image file", e)
                }
            } else {
                cameraPermissionLauncher.launch(Manifest.permission.CAMERA)
            }

            // File picker intent
            val filePickerIntent = Intent(Intent.ACTION_GET_CONTENT).apply {
                addCategory(Intent.CATEGORY_OPENABLE)
                type = fileChooserParams?.acceptTypes?.firstOrNull()
                    ?.takeIf { it.isNotEmpty() } ?: "image/*"
                putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)
            }

            // Chooser
            val chooserIntent = Intent.createChooser(filePickerIntent, "Välj fil")
            chooserIntent.putExtra(
                Intent.EXTRA_INITIAL_INTENTS,
                intentList.toTypedArray()
            )

            try {
                fileChooserLauncher.launch(chooserIntent)
            } catch (e: ActivityNotFoundException) {
                fileUploadCallback?.onReceiveValue(arrayOf())
                fileUploadCallback = null
                Toast.makeText(
                    this@MainActivity,
                    "Kan inte öppna filväljare",
                    Toast.LENGTH_SHORT
                ).show()
                return false
            }

            return true
        }

        // Handle target="_blank" links - open new window URLs in WebView or external browser
        override fun onCreateWindow(
            view: WebView?,
            isDialog: Boolean,
            isUserGesture: Boolean,
            resultMsg: Message?
        ): Boolean {
            val transport = resultMsg?.obj as? WebView.WebViewTransport ?: return false

            // Create a temporary WebView to capture the URL
            val tempWebView = WebView(this@MainActivity)
            tempWebView.webViewClient = object : WebViewClient() {
                override fun shouldOverrideUrlLoading(
                    view: WebView?,
                    request: WebResourceRequest?
                ): Boolean {
                    val url = request?.url?.toString() ?: return false
                    if (isInternalUrl(url)) {
                        webView.loadUrl(url)
                    } else {
                        openInExternalBrowser(url)
                    }
                    // Clean up temp WebView
                    tempWebView.destroy()
                    return true
                }
            }

            transport.webView = tempWebView
            resultMsg.sendToTarget()
            return true
        }

        override fun onCloseWindow(window: WebView?) {
            super.onCloseWindow(window)
        }

        // Update page title
        override fun onReceivedTitle(view: WebView?, title: String?) {
            super.onReceivedTitle(view, title)
            // Optionally update the action bar title
        }

        // Progress bar (used by swipe refresh)
        override fun onProgressChanged(view: WebView?, newProgress: Int) {
            super.onProgressChanged(view, newProgress)
            if (newProgress >= 100) {
                swipeRefreshLayout.isRefreshing = false
            }
        }
    }

    /**
     * Download listener - handles file downloads
     */
    private inner class BKDownloadListener : DownloadListener {
        override fun onDownloadStart(
            url: String?,
            userAgent: String?,
            contentDisposition: String?,
            mimetype: String?,
            contentLength: Long
        ) {
            if (url == null) return

            try {
                val request = DownloadManager.Request(Uri.parse(url))
                request.setMimeType(mimetype)
                request.addRequestHeader("User-Agent", userAgent)
                request.setDescription("Laddar ner fil...")
                request.setTitle(
                    URLUtil.guessFileName(url, contentDisposition, mimetype)
                )
                request.setNotificationVisibility(
                    DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED
                )
                request.setDestinationInExternalPublicDir(
                    Environment.DIRECTORY_DOWNLOADS,
                    URLUtil.guessFileName(url, contentDisposition, mimetype)
                )

                val dm = getSystemService(DOWNLOAD_SERVICE) as DownloadManager
                dm.enqueue(request)

                Toast.makeText(
                    this@MainActivity,
                    "Nedladdning startad",
                    Toast.LENGTH_SHORT
                ).show()
            } catch (e: Exception) {
                Log.e(TAG, "Download failed", e)
                // Fallback: open in browser
                openInExternalBrowser(url)
            }
        }
    }
}
