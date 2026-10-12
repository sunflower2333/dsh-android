package io.github.sunflower2333.dsh

import android.annotation.SuppressLint
import android.app.Activity
import android.app.AlertDialog
import android.content.ActivityNotFoundException
import android.content.BroadcastReceiver
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ApplicationInfo
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.LayerDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets
import android.view.WindowManager
import android.webkit.ConsoleMessage
import android.webkit.CookieManager
import android.webkit.URLUtil
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import android.webkit.WebMessage
import android.webkit.WebMessagePort
import android.webkit.WebSettings
import android.widget.FrameLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import android.window.OnBackInvokedCallback
import android.window.OnBackInvokedDispatcher
import org.json.JSONObject
import java.io.ByteArrayInputStream
import java.io.File
import java.io.StringReader
import android.util.JsonReader
import android.util.JsonToken

class MainActivity : Activity() {
    private lateinit var webView: WebView
    private lateinit var errorBar: TextView
    private lateinit var contentRoot: FrameLayout
    private var dshUiVisible = false
    @Volatile private var themeReadAllowed = false
    private var themeGeneration = 0
    private var themePorts: Array<WebMessagePort>? = null
    private val handler = Handler(Looper.getMainLooper())
    private var terminalError = false
    private var lastErrorMessage: String? = null
    @Volatile private var lastReadyLaunchUrl: String? = null
    private var pendingFileSelection: ValueCallback<Array<Uri>>? = null
    private var pendingDownload: AndroidDownloadRequest? = null
    private var pendingDownloadDestination: Uri? = null
    private var readyHostConfirmed = false
    private var downloadPageReady = false
    private val downloads by lazy { AndroidDownloads(this) }
    private var uiCheckGeneration = 0
    private var backNavigationPending = false
    private var pendingConfigurationExport = false
    private var pendingNotificationSession: String? = null
    private var notificationNavigationPending = false
    private var workspaceRequest: WorkspaceRequest? = null
    private var workspaceDeliveryPending = false
    private var androidSettingsPortInstalled = false
    private val androidSettingsBridge by lazy { AndroidSettingsBridge(this, webView, {
        !terminalError && readyHostConfirmed && themeReadAllowed && WebNavigation.isCurrentOrigin(webView.url, lastReadyLaunchUrl)
    }, { requestAdbFilePicker() }) }
    private val webDiagnostics by lazy { StartupDiagnostics(File(cacheDir, "dsh-webview.log")) }
    // Keep the callback behind an Any-typed slot: OnBackInvokedCallback was
    // introduced in API 33 while this client still supports API 30.
    private var backInvokedCallback: Any? = null
    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            val value = intent?.getStringExtra(DshService.EXTRA_VALUE).orEmpty()
            when (intent?.action) {
                DshService.ACTION_READY -> {
                    if (!LocalUrl.isAllowed(value)) return
                    val reload = terminalError || lastReadyLaunchUrl != value ||
                        !WebNavigation.isCurrentOrigin(webView.url, value)
                    if (reload) {
                        // Revoke the old document before adopting READY: loadUrl
                        // schedules onPageStarted later, leaving a queued-port gap.
                        androidSettingsBridge.invalidate()
                        androidSettingsPortInstalled = false
                        closeThemePorts()
                        themeReadAllowed = false
                    }
                    terminalError = false
                    lastErrorMessage = null
                    errorBar.visibility = View.GONE
                    // Request interception runs on WebView's IO thread.
                    lastReadyLaunchUrl = value
                    if (workspaceRequest?.readyUrl?.let { it != value } == true) clearWorkspaceRequest()
                    readyHostConfirmed = true
                    // Reuse the restored page/history when the same server
                    // and authentication token are still alive.
                    if (reload) {
                        downloadPageReady = false
                        webView.visibility = View.INVISIBLE
                        dshUiVisible = false
                        contentRoot.setBackgroundColor(Color.TRANSPARENT)
                        webView.loadUrl(value)
                    }
                    else awaitDshUi()
                    savePendingDownload()
                }
                DshService.ACTION_ERROR -> showError(value)
                DshService.ACTION_EXITED -> {
                    readyHostConfirmed = false
                    if (!terminalError && value != "stopped") showError(DshUiLanguage.text(this@MainActivity, R.string.exited_status))
                }
            }
        }
    }

    @SuppressLint("UnspecifiedRegisterReceiverFlag")
    override fun onCreate(savedInstanceState: Bundle?) {
        setTheme(AndroidAppearance.theme(this))
        super.onCreate(savedInstanceState)
        // Keep the WebView in resize mode on API 30 and on edge-to-edge API 35.
        // Some OEMs retain a one-frame pan after IME dismissal when this is
        // left solely to the manifest attribute.
        window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
        // Userdebug Android images can enable WebView debugging by default.
        // Set both build modes explicitly before creating the WebView.
        WebView.setWebContentsDebuggingEnabled(
            applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0,
        )
        setContentView(buildView())
        // API 30's PhoneWindow.insetsController dereferences the DecorView.
        // setContentView installs it before we configure the controller; the
        // root's posted inset request runs after this edge-to-edge setup.
        configureSystemBars()
        restoreWebState(savedInstanceState)
        restoreFileOperations(savedInstanceState)
        restoreWorkspaceRequest(savedInstanceState)
        pendingNotificationSession = savedInstanceState?.getString(STATE_NOTIFICATION_SESSION)
        consumeNotificationIntent(intent)
        savedInstanceState?.getString(STATE_ERROR)?.let { showError(it) }
        registerBackCallback()
        val filter = IntentFilter().apply {
            addAction(DshService.ACTION_READY)
            addAction(DshService.ACTION_ERROR)
            addAction(DshService.ACTION_EXITED)
        }
        if (Build.VERSION.SDK_INT >= 33) registerReceiver(receiver, filter,
            DshService.INTERNAL_PERMISSION, null, Context.RECEIVER_NOT_EXPORTED)
        else registerReceiver(receiver, filter, DshService.INTERNAL_PERMISSION, null)
        // Opening the app always starts or reconnects to the real DSH host.
        // The standard branded launch background remains until its UI mounts.
    }

    override fun onStart() {
        super.onStart()
        // Returning from another app also reconnects or starts the local host.
        // READY from the same host preserves the existing page and picker DOM.
        readyHostConfirmed = false
        startDsh()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        consumeNotificationIntent(intent)
        // The launcher or notification can deliver an intent to the current
        // foreground instance without calling onStart, including just after
        // the notification's Stop action terminated the local host.
        readyHostConfirmed = false
        startDsh()
    }

    override fun onResume() {
        super.onResume()
        configureSystemBars()
        publishSystemUiMode()
        DshService.setUiForeground(true)
        androidSettingsBridge.onResume()
    }

    override fun onPause() {
        androidSettingsBridge.onPause()
        DshService.setUiForeground(false)
        super.onPause()
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        // Android UiMode is authoritative for "system" even on WebViews
        // whose CSS prefers-color-scheme is fixed by their light parent theme.
        configureSystemBars()
        publishSystemUiMode()
    }

    private fun consumeNotificationIntent(intent: Intent?) {
        if (intent?.action != DshService.ACTION_OPEN_SESSION) return
        val target = NotificationSessionTargets.tickets.take(intent.getStringExtra(NotificationSessionTargets.EXTRA_TICKET))
        // An old OS PendingIntent may outlive the process that issued its
        // ticket. Never silently retain a different queued session target.
        pendingNotificationSession = target
        if (target == null) Toast.makeText(this, DshUiLanguage.text(this@MainActivity, R.string.notification_session_unavailable), Toast.LENGTH_LONG).show()
        intent.removeExtra(NotificationSessionTargets.EXTRA_TICKET)
        // Saved-instance state retains a verified pending target across
        // recreation; the consumed Intent must not be handled a second time.
        intent.action = null
        intent.data = null
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun buildView(): View {
        var keyboardWasVisible = false
        val root = FrameLayout(this).apply {
            // Keep the official window launch logo visible until DSH mounts.
            setBackgroundColor(Color.TRANSPARENT)
            // Android 15 enforces edge-to-edge for target 35. Own the insets on
            // every supported API so neither old decor fitting nor a cutout
            // can put a toolbar underneath the system status/navigation bars.
            setOnApplyWindowInsetsListener { view, insets ->
                val bars = insets.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout())
                val keyboard = insets.getInsets(WindowInsets.Type.ime())
                view.setPadding(bars.left, bars.top, bars.right, maxOf(bars.bottom, keyboard.bottom))
                val keyboardVisible = insets.isVisible(WindowInsets.Type.ime())
                if (keyboardWasVisible && !keyboardVisible) {
                    // WebView can keep the native pan it applied to a focused
                    // input after IME dismissal. Recenter only the onboarding
                    // dialog; resetting a chat scroll position would be a
                    // surprising side effect.
                    resetOnboardingImePan()
                }
                keyboardWasVisible = keyboardVisible
                insets
            }
        }
        contentRoot = root
        webView = WebView(this).apply {
            visibility = View.INVISIBLE
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.allowFileAccess = false
            settings.allowContentAccess = false
            settings.builtInZoomControls = false
            settings.displayZoomControls = false
            // DSH's real theme runtime paints its own palette. Do not let
            // WebView additionally invert a correctly selected light UI.
            @Suppress("DEPRECATION")
            run { settings.forceDark = WebSettings.FORCE_DARK_OFF }
            if (Build.VERSION.SDK_INT >= 33) settings.isAlgorithmicDarkeningAllowed = false
            addJavascriptInterface(AndroidUiMode(applicationContext) { themeReadAllowed }, "AndroidUiMode")
            android.webkit.CookieManager.getInstance().setAcceptCookie(true)
            setDownloadListener { url, userAgent, disposition, mimeType, _ ->
                chooseDownloadDestination(url, userAgent, disposition, mimeType)
            }
            webChromeClient = object : WebChromeClient() {
                override fun onShowFileChooser(
                    view: WebView,
                    callback: ValueCallback<Array<Uri>>,
                    parameters: FileChooserParams,
                ): Boolean {
                    pendingFileSelection?.onReceiveValue(null)
                    pendingFileSelection = callback
                    return try {
                        startActivityForResult(parameters.createIntent(), FILE_SELECTION_REQUEST)
                        true
                    } catch (_: ActivityNotFoundException) {
                        pendingFileSelection = null
                        callback.onReceiveValue(null)
                        Toast.makeText(this@MainActivity, DshUiLanguage.text(this@MainActivity, R.string.file_picker_unavailable), Toast.LENGTH_SHORT).show()
                        true
                    }
                }

                override fun onConsoleMessage(message: ConsoleMessage): Boolean {
                    // A plugin can recover from an individual request error.
                    // Keep diagnostics; document failures and actual boot state
                    // determine whether the application failed to start.
                    webDiagnostics.append("${message.messageLevel()}: ${message.message()}")
                    return true
                }
            }
            webViewClient = object : WebViewClient() {
                override fun onPageStarted(view: WebView, url: String, favicon: android.graphics.Bitmap?) {
                    androidSettingsBridge.invalidate()
                    androidSettingsPortInstalled = false
                    closeThemePorts()
                    themeReadAllowed = WebNavigation.isCurrentOrigin(url, lastReadyLaunchUrl)
                    super.onPageStarted(view, url, favicon)
                }
                override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? {
                    if (!WebNavigation.blocksForeignLoopbackRequest(request.url.toString(), lastReadyLaunchUrl)) return null
                    // Cookies do not distinguish localhost ports. Block direct
                    // cross-port resources before WebView sends any credentials.
                    return WebResourceResponse("text/plain", "UTF-8", 403, "Forbidden", emptyMap(),
                        ByteArrayInputStream(ByteArray(0)))
                }
                override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                    if (request.isForMainFrame) {
                        WebNavigation.workspaceRequestId(request.url.toString(), view.url, lastReadyLaunchUrl)?.let { id ->
                            val touched = androidSettingsBridge.consumeRecentUserGesture()
                            chooseWorkspace(id, touched)
                            return true
                        }
                    }
                    if (request.isForMainFrame && WebNavigation.isRuntimeSettingsRequest(
                            request.url.toString(), view.url, lastReadyLaunchUrl)) {
                        openAndroidWebSettings("runtime")
                        return true
                    }
                    if (request.isForMainFrame && WebNavigation.isMobileControlRequest(
                            request.url.toString(), view.url, lastReadyLaunchUrl)) {
                        openAndroidWebSettings("mobile")
                        return true
                    }
                    if (request.isForMainFrame && WebNavigation.isConfigurationRequest(
                            request.url.toString(), view.url, lastReadyLaunchUrl)) {
                        openConfigurationDocument()
                        return true
                    }
                    return when (WebNavigation.classify(request.url.toString(), lastReadyLaunchUrl)) {
                        WebNavigationDecision.INTERNAL -> false
                        WebNavigationDecision.EXTERNAL_HTTP -> {
                            openExternalUrl(request.url)
                            true
                        }
                        WebNavigationDecision.BLOCKED -> true
                    }
                }
                override fun onPageFinished(view: WebView, url: String) {
                    super.onPageFinished(view, url)
                    if (!terminalError && WebNavigation.isCurrentOrigin(url, lastReadyLaunchUrl)) {
                        installAndroidSettingsPort(url)
                        installThemePort(url)
                        publishSystemUiMode()
                        downloadPageReady = true
                        awaitDshUi()
                        savePendingDownload()
                    }
                }
                override fun onReceivedError(view: WebView, request: WebResourceRequest, error: android.webkit.WebResourceError) {
                    super.onReceivedError(view, request, error)
                    if (request.isForMainFrame) showError(error.description?.toString() ?: DshUiLanguage.text(this@MainActivity, R.string.web_load_error))
                }
                override fun onReceivedHttpError(view: WebView, request: WebResourceRequest, response: WebResourceResponse) {
                    super.onReceivedHttpError(view, request, response)
                    if (request.isForMainFrame) showError(DshUiLanguage.text(this@MainActivity, R.string.web_http_error, response.statusCode))
                }
            }
            setOnTouchListener { _, event -> androidSettingsBridge.onWebViewTouch(event); false }
        }
        root.addView(webView, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        errorBar = TextView(this).apply {
            visibility = View.GONE
            textSize = 14f
            maxLines = 3
            setTextColor(Color.rgb(122, 26, 26))
            setBackgroundColor(Color.rgb(255, 236, 236))
            setPadding(dp(16), dp(12), dp(16), dp(12))
            minHeight = dp(48)
            contentDescription = DshUiLanguage.text(this@MainActivity, R.string.error_details)
            setOnClickListener { showErrorDetails() }
        }
        root.addView(errorBar, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM))
        // ColorOS and a few Android 11 WebView builds occasionally omit the
        // final IME insets callback. The visible display frame gives us a
        // second, independent edge for the same transition.
        root.viewTreeObserver.addOnGlobalLayoutListener {
            val frame = android.graphics.Rect()
            root.getWindowVisibleDisplayFrame(frame)
            val keyboardVisible = root.height > 0 && root.height - frame.bottom > root.height / 5
            if (keyboardWasVisible && !keyboardVisible) resetOnboardingImePan()
            keyboardWasVisible = keyboardVisible
        }
        root.post { root.requestApplyInsets() }
        return root
    }

    private fun resetOnboardingImePan() {
        if (!::webView.isInitialized || terminalError || isFinishing || isDestroyed) return
        val script = """
            (function () {
              const dialog = document.querySelector('.jLrgrW_dialog');
              if (!dialog) return false;
              const active = document.activeElement;
              if (active && active.matches('input,textarea,[contenteditable="true"]')) active.blur();
              document.documentElement.dataset.dshAndroidImeRestored = 'true';
              window.scrollTo(0, 0);
              document.documentElement.scrollTop = 0;
              document.body.scrollTop = 0;
              if (window.visualViewport && window.visualViewport.scrollTo) window.visualViewport.scrollTo(0, 0);
              return true;
            })();
        """.trimIndent()
        // The platform may apply its final pan after dispatching insets. Repeat
        // at the end of the current frame and after the OEM animation settles.
        listOf(0L, 80L, 240L, 600L).forEach { delay ->
            handler.postDelayed({
                if (!::webView.isInitialized || isFinishing || isDestroyed) return@postDelayed
                webView.evaluateJavascript(script, null)
                webView.translationX = 0f
                webView.translationY = 0f
                webView.scrollTo(0, 0)
                webView.requestLayout()
            }, delay)
        }
    }

    @Suppress("DEPRECATION")
    private fun configureSystemBars() {
        setTheme(AndroidAppearance.theme(this))
        window.setDecorFitsSystemWindows(false)
        window.statusBarColor = Color.TRANSPARENT
        window.navigationBarColor = Color.TRANSPARENT
        window.isStatusBarContrastEnforced = false
        window.isNavigationBarContrastEnforced = false
        val colors = NativeUiColors(this)
        val background = (getDrawable(R.drawable.dsh_launch_background)?.mutate() as? LayerDrawable)
        if (background != null) {
            background.setDrawable(0, ColorDrawable(colors.background))
            window.setBackgroundDrawable(background)
        }
        if (::webView.isInitialized) webView.setBackgroundColor(colors.background)
        if (::contentRoot.isInitialized && dshUiVisible) contentRoot.setBackgroundColor(colors.background)
        SystemBarAppearance.apply(window, colors.dark)
    }

    private fun closeThemePorts() {
        ++themeGeneration
        themePorts?.forEach { runCatching { it.close() } }
        themePorts = null
    }

    private fun installThemePort(url: String) {
        if (!themeReadAllowed || !WebNavigation.isCurrentOrigin(url, lastReadyLaunchUrl)) return
        closeThemePorts()
        val generation = themeGeneration
        val ports = webView.createWebMessageChannel()
        themePorts = ports
        ports[0].setWebMessageCallback(object : WebMessagePort.WebMessageCallback() {
            override fun onMessage(port: WebMessagePort?, message: WebMessage?) {
                if (generation != themeGeneration || port !== ports[0] ||
                    !themeReadAllowed || !WebNavigation.isCurrentOrigin(webView.url, lastReadyLaunchUrl)) return
                val data = message?.data ?: return
                if (data.length > 256 || !message.ports.isNullOrEmpty()) return
                val parsed = runCatching {
                    val fields = LinkedHashMap<String, Any?>()
                    JsonReader(StringReader(data)).use { reader ->
                        reader.isLenient = false
                        require(reader.peek() == JsonToken.BEGIN_OBJECT)
                        reader.beginObject()
                        while (reader.hasNext()) {
                            val name = reader.nextName()
                            require(name.length <= 32 && name !in fields && fields.size < 3)
                            fields[name] = when (reader.peek()) {
                                JsonToken.STRING -> reader.nextString()
                                JsonToken.NUMBER -> reader.nextString().toLong()
                                else -> throw IllegalArgumentException("Invalid appearance message")
                            }
                        }
                        reader.endObject()
                        require(reader.peek() == JsonToken.END_DOCUMENT)
                    }
                    AppearancePolicy.parse(fields, AndroidAppearance.systemDark(applicationContext))
                }.getOrNull() ?: return
                AndroidAppearance.adoptFromDsh(applicationContext, parsed.preference)
                configureSystemBars()
            }
        })
        val origin = Uri.parse(url).let { Uri.parse("${it.scheme}://${it.host}:${it.port}") }
        webView.postWebMessage(WebMessage("dsh.android.theme.port.v1", arrayOf(ports[1])), origin)
    }

    private fun publishSystemUiMode() {
        if (!::webView.isInitialized || !themeReadAllowed ||
            !WebNavigation.isCurrentOrigin(webView.url, lastReadyLaunchUrl)) return
        val dark = AndroidAppearance.systemDark(applicationContext)
        webView.evaluateJavascript("(function(){if(typeof window.__DSH_ANDROID_SYSTEM_UI_MODE__==='function')" +
            "window.__DSH_ANDROID_SYSTEM_UI_MODE__($dark);})();", null)
    }

    private fun installAndroidSettingsPort(url: String) {
        if (androidSettingsPortInstalled || terminalError || !readyHostConfirmed || !themeReadAllowed ||
            !WebNavigation.isCurrentOrigin(url, lastReadyLaunchUrl)) return
        androidSettingsBridge.install(url)
        androidSettingsPortInstalled = true
    }

    private fun openAndroidWebSettings(section: String) {
        if (!readyHostConfirmed || !WebNavigation.isCurrentOrigin(webView.url, lastReadyLaunchUrl)) return
        webView.evaluateJavascript("(function(){if(typeof window.__DSH_ANDROID_OPEN_SETTINGS__==='function')" +
            "window.__DSH_ANDROID_OPEN_SETTINGS__(${JSONObject.quote(section)});})();", null)
    }

    /** Reveal the real application, after its own plugin boot DOM has gone away. */
    private fun awaitDshUi() {
        val generation = ++uiCheckGeneration
        val deadline = android.os.SystemClock.uptimeMillis() + 60_000
        fun check() {
            if (generation != uiCheckGeneration || terminalError || isFinishing || isDestroyed) return
            if (android.os.SystemClock.uptimeMillis() >= deadline) {
                showError(DshUiLanguage.text(this@MainActivity, R.string.web_boot_timeout, webDiagnostics.tail()))
                return
            }
            webView.evaluateJavascript(UI_STATE_SCRIPT) { value ->
                if (generation != uiCheckGeneration || terminalError || isFinishing || isDestroyed) return@evaluateJavascript
                val state = runCatching { JSONObject(value) }.getOrNull()
                val failure = state?.optString("error").orEmpty()
                if (failure.isNotBlank()) {
                    showError(failure)
                } else if (state?.optBoolean("ready") == true) {
                    dshUiVisible = true
                    installAndroidSettingsPort(webView.url ?: return@evaluateJavascript)
                    androidSettingsBridge.onReady()
                    configureSystemBars()
                    webView.visibility = View.VISIBLE
                    openNotificationSession()
                    deliverWorkspaceResult()
                } else {
                    handler.postDelayed({ check() }, 200)
                }
            }
        }
        check()
    }

    /** Select through DSH's own workspace controller, retaining each session's draft. */
    private fun openNotificationSession() {
        val sessionId = pendingNotificationSession ?: return
        if (notificationNavigationPending || !readyHostConfirmed ||
            !WebNavigation.isCurrentOrigin(webView.url, lastReadyLaunchUrl)) return
        notificationNavigationPending = true
        val deadline = android.os.SystemClock.uptimeMillis() + 15_000
        fun select() {
            if (isFinishing || isDestroyed || pendingNotificationSession != sessionId) {
                notificationNavigationPending = false
                return
            }
            if (!readyHostConfirmed || !WebNavigation.isCurrentOrigin(webView.url, lastReadyLaunchUrl)) {
                notificationNavigationPending = false
                return
            }
            val script = "(function(){try{return typeof window.__DSH_ANDROID_OPEN_SESSION__==='function' && " +
                "window.__DSH_ANDROID_OPEN_SESSION__(${JSONObject.quote(sessionId)})===true;}catch(error){return false;}})();"
            webView.evaluateJavascript(script) { result ->
                if (isFinishing || isDestroyed) return@evaluateJavascript
                if (pendingNotificationSession != sessionId) {
                    notificationNavigationPending = false
                    openNotificationSession()
                } else if (result == "true") {
                    pendingNotificationSession = null
                    notificationNavigationPending = false
                } else if (android.os.SystemClock.uptimeMillis() < deadline) handler.postDelayed({ select() }, 250)
                else {
                    pendingNotificationSession = null
                    notificationNavigationPending = false
                    Toast.makeText(this, DshUiLanguage.text(this@MainActivity, R.string.notification_session_unavailable), Toast.LENGTH_LONG).show()
                }
            }
        }
        select()
    }

    private fun restoreWebState(savedInstanceState: Bundle?) {
        if (savedInstanceState == null) return
        lastReadyLaunchUrl = savedInstanceState.getString(STATE_LAUNCH_URL)?.takeIf { LocalUrl.isAllowed(it) }
        val restored = runCatching { webView.restoreState(savedInstanceState) }.getOrNull()
        val restoredUrl = webView.url ?: restored?.currentItem?.url
        val restoredWebState = restored != null && WebNavigation.isCurrentOrigin(restoredUrl, lastReadyLaunchUrl)
        if (!restoredWebState) {
            webView.clearHistory()
            lastReadyLaunchUrl = null
        }
    }

    private fun restoreFileOperations(savedInstanceState: Bundle?) {
        if (savedInstanceState == null) return
        pendingConfigurationExport = savedInstanceState.getBoolean(STATE_CONFIGURATION_EXPORT)
        savedInstanceState.getBundle(STATE_PENDING_DOWNLOAD)?.let { state ->
            val url = state.getString("url")?.takeIf { LocalUrl.isAllowed(it) }
            if (url != null) {
                pendingDownload = AndroidDownloadRequest(url,
                    AndroidDownloadPolicy.filename(state.getString("filename").orEmpty()),
                    state.getString("mime") ?: "application/octet-stream", state.getString("userAgent"))
                pendingDownloadDestination = state.getString("destination")?.let(Uri::parse)
                    ?.takeIf { it.scheme == "content" }
            }
        }
        if (savedInstanceState.getBoolean(STATE_FILE_OPERATION_INTERRUPTED)) {
            Toast.makeText(this, DshUiLanguage.text(this@MainActivity, R.string.file_operation_interrupted), Toast.LENGTH_LONG).show()
        }
    }

    @Deprecated("Activity result compatibility for the native WebView file picker")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == WORKSPACE_PERMISSION_REQUEST) {
            val current = workspaceRequest ?: return
            if (current.stage != "permission") return
            if (!workspaceOriginCurrent(current)) { clearWorkspaceRequest(); return }
            if (Environment.isExternalStorageManager()) launchWorkspacePicker(current)
            else finishWorkspaceRequest(current, "error", "permission-denied")
            return
        }
        if (requestCode == WORKSPACE_FOLDER_REQUEST) {
            val current = workspaceRequest ?: return
            if (current.stage != "picker") return
            if (!workspaceOriginCurrent(current)) { clearWorkspaceRequest(); return }
            if (resultCode != RESULT_OK) finishWorkspaceRequest(current, "cancelled", null)
            else if (data?.data == null) finishWorkspaceRequest(current, "error", "folder-unavailable")
            else validateWorkspaceFolder(current, data.data!!)
            return
        }
        if (requestCode == CONFIGURATION_EXPORT_REQUEST) {
            val expected = pendingConfigurationExport
            pendingConfigurationExport = false
            if (resultCode == RESULT_OK && expected) {
                data?.data?.takeIf { it.scheme == "content" }?.let { saveConfigurationCopy(it) }
            }
            return
        }
        if (requestCode == DOWNLOAD_DESTINATION_REQUEST) {
            if (resultCode != RESULT_OK) {
                pendingDownload = null
                pendingDownloadDestination = null
                return
            }
            if (pendingDownload == null) {
                Toast.makeText(this, DshUiLanguage.text(this@MainActivity, R.string.file_operation_interrupted), Toast.LENGTH_LONG).show()
                return
            }
            pendingDownloadDestination = data?.data?.takeIf { it.scheme == "content" }
            if (pendingDownloadDestination == null) {
                pendingDownload = null
                Toast.makeText(this, DshUiLanguage.text(this@MainActivity, R.string.download_failed), Toast.LENGTH_LONG).show()
                return
            }
            // A restored Activity waits for a fresh Service READY, then the
            // corresponding page's cookie handshake, before using this URL.
            savePendingDownload()
            return
        }
        if (requestCode == ADB_FILE_REQUEST) {
            if (androidSettingsBridge.onAdbFileResult(resultCode, data)) return
        }
        if (requestCode != FILE_SELECTION_REQUEST) return
        val results = if (resultCode == RESULT_OK && data?.clipData != null) {
            val clips = data.clipData!!
            Array(clips.itemCount) { index -> clips.getItemAt(index).uri }
        } else WebChromeClient.FileChooserParams.parseResult(resultCode, data)
        val selected = results
            ?.filter { it.scheme == "content" }?.toTypedArray()
        pendingFileSelection?.onReceiveValue(selected)
        pendingFileSelection = null
    }

    private fun requestAdbFilePicker(): Boolean {
        if (isFinishing || isDestroyed || !readyHostConfirmed) return false
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "application/octet-stream"
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        return try {
            startActivityForResult(intent, ADB_FILE_REQUEST)
            true
        } catch (_: ActivityNotFoundException) { false }
        catch (_: SecurityException) { false }
    }

    /** The DSH WebUI owns consent and presentation; Android opens only system surfaces. */
    private fun chooseWorkspace(requestId: String, userGesture: Boolean) {
        if (workspaceRequest != null || !readyHostConfirmed || !dshUiVisible ||
            !WebNavigation.isCurrentOrigin(webView.url, lastReadyLaunchUrl)) return
        val current = WorkspaceRequest(requestId, lastReadyLaunchUrl ?: return)
        workspaceRequest = current
        if (!userGesture) { finishWorkspaceRequest(current, "error", "gesture-required"); return }
        if (Environment.isExternalStorageManager()) { launchWorkspacePicker(current); return }
        current.stage = "permission"
        val appSettings = Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION, Uri.parse("package:$packageName"))
        try { startActivityForResult(appSettings, WORKSPACE_PERMISSION_REQUEST) }
        catch (_: ActivityNotFoundException) {
            try { startActivityForResult(Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION), WORKSPACE_PERMISSION_REQUEST) }
            catch (_: ActivityNotFoundException) { finishWorkspaceRequest(current, "error", "settings-unavailable") }
            catch (_: SecurityException) { finishWorkspaceRequest(current, "error", "settings-unavailable") }
        } catch (_: SecurityException) { finishWorkspaceRequest(current, "error", "settings-unavailable") }
    }

    private fun workspaceOriginCurrent(current: WorkspaceRequest) = workspaceRequest === current &&
        current.readyUrl == lastReadyLaunchUrl && WebNavigation.isCurrentOrigin(webView.url, lastReadyLaunchUrl)

    private fun launchWorkspacePicker(current: WorkspaceRequest) {
        if (!workspaceOriginCurrent(current)) { clearWorkspaceRequest(); return }
        if (!Environment.isExternalStorageManager()) { finishWorkspaceRequest(current, "error", "permission-denied"); return }
        current.stage = "picker"
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT_TREE).apply {
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
            // SAF is used to choose the directory, never as native filesystem authority.
            // Do not retain a redundant persistable grant beyond this selection.
        }
        try { startActivityForResult(intent, WORKSPACE_FOLDER_REQUEST) }
        catch (_: ActivityNotFoundException) { finishWorkspaceRequest(current, "error", "picker-unavailable") }
        catch (_: SecurityException) { finishWorkspaceRequest(current, "error", "picker-unavailable") }
    }

    private fun validateWorkspaceFolder(current: WorkspaceRequest, uri: Uri) {
        current.stage = "validating"
        current.treeUri = uri.toString()
        Thread({
            val result = runCatching { WorkspaceStorage.validate(applicationContext, uri) }
            handler.post {
                if (isFinishing || isDestroyed || workspaceRequest !== current) return@post
                if (!workspaceOriginCurrent(current)) { clearWorkspaceRequest(); return@post }
                val folder = result.getOrNull()
                if (folder != null) { current.path = folder.path; finishWorkspaceRequest(current, "selected", null) }
                else finishWorkspaceRequest(current, "error", when ((result.exceptionOrNull() as? WorkspaceStorageException)?.failure) {
                    WorkspaceStorageFailure.PERMISSION_REQUIRED -> "permission-denied"
                    WorkspaceStorageFailure.UNSUPPORTED_PROVIDER -> "unsupported-provider"
                    WorkspaceStorageFailure.NOT_WRITABLE -> "folder-not-writable"
                    else -> "folder-unavailable"
                })
            }
        }, "dsh-workspace-check").apply { isDaemon = true }.start()
    }

    private fun finishWorkspaceRequest(current: WorkspaceRequest, status: String, error: String?) {
        if (workspaceRequest !== current) return
        current.stage = "result"
        current.status = status
        current.error = error
        current.treeUri = null
        deliverWorkspaceResult()
    }

    /** A true return means the existing frontend queued its own normal workspace flow. */
    private fun deliverWorkspaceResult() {
        val current = workspaceRequest ?: return
        if (current.stage != "result" || workspaceDeliveryPending || !dshUiVisible ||
            !WorkspaceSelectionPolicy.canDeliver(current.id, current.readyUrl, lastReadyLaunchUrl,
                webView.url, readyHostConfirmed)) return
        if (current.status == "selected" && !Environment.isExternalStorageManager()) {
            current.status = "error"; current.error = "permission-denied"; current.path = null
        }
        workspaceDeliveryPending = true
        val deadline = android.os.SystemClock.uptimeMillis() + 15_000
        fun deliver() {
            if (isFinishing || isDestroyed || workspaceRequest !== current) { workspaceDeliveryPending = false; return }
            if (!WorkspaceSelectionPolicy.canDeliver(current.id, current.readyUrl, lastReadyLaunchUrl, webView.url, readyHostConfirmed)) {
                workspaceDeliveryPending = false
                if (current.readyUrl != lastReadyLaunchUrl) clearWorkspaceRequest()
                return
            }
            val call = if (current.status == "selected" && current.path != null)
                "typeof window.__DSH_ANDROID_SELECT_WORKSPACE__==='function' && window.__DSH_ANDROID_SELECT_WORKSPACE__(" +
                    "${JSONObject.quote(current.path)},${JSONObject.quote(current.id)})===true"
            else "typeof window.__DSH_ANDROID_WORKSPACE_RESULT__==='function' && window.__DSH_ANDROID_WORKSPACE_RESULT__(" +
                "${JSONObject.quote(current.id)},${JSONObject.quote(current.status)},${current.error?.let(JSONObject::quote) ?: "null"})===true"
            webView.evaluateJavascript("(function(){try{return $call;}catch(error){return false;}})();") { value ->
                if (isFinishing || isDestroyed || workspaceRequest !== current) return@evaluateJavascript
                if (value == "true") clearWorkspaceRequest()
                else if (android.os.SystemClock.uptimeMillis() < deadline) handler.postDelayed({ deliver() }, 250)
                else {
                    clearWorkspaceRequest()
                    // The original request may have been cancelled by navigation; never reopen it.
                }
            }
        }
        deliver()
    }

    private fun clearWorkspaceRequest() { workspaceRequest = null; workspaceDeliveryPending = false }

    private fun restoreWorkspaceRequest(savedInstanceState: Bundle?) {
        val state = savedInstanceState?.getBundle(STATE_WORKSPACE_REQUEST) ?: return
        val id = state.getString("id")?.takeIf(WorkspaceSelectionPolicy::validRequestId) ?: return
        val ready = state.getString("ready")?.takeIf(LocalUrl::isAllowed) ?: return
        val stage = state.getString("stage")?.takeIf { it in setOf("permission", "picker", "validating", "result") } ?: return
        val current = WorkspaceRequest(id, ready, stage, state.getString("path"), state.getString("status") ?: "cancelled",
            state.getString("error"), state.getString("tree"))
        workspaceRequest = current
        if (stage == "validating") {
            val uri = current.treeUri?.let(Uri::parse)
            if (uri == null) finishWorkspaceRequest(current, "error", "selection-failed")
            else validateWorkspaceFolder(current, uri)
        }
    }

    private fun savePendingDownload() {
        val request = pendingDownload ?: return
        val destination = pendingDownloadDestination ?: return
        if (!readyHostConfirmed) return
        if (!WebNavigation.isCurrentOrigin(request.url, lastReadyLaunchUrl)) {
            pendingDownload = null
            pendingDownloadDestination = null
            Toast.makeText(this, DshUiLanguage.text(this@MainActivity, R.string.download_failed), Toast.LENGTH_LONG).show()
            return
        }
        if (!downloadPageReady) return
        pendingDownload = null
        pendingDownloadDestination = null
        if (!AndroidDownloadPolicy.isAllowed(request.url, webView.url, lastReadyLaunchUrl)) {
            Toast.makeText(this, DshUiLanguage.text(this@MainActivity, R.string.download_failed), Toast.LENGTH_LONG).show()
            return
        }
        // Capture the current cookie only now, and never put it in savedState.
        val cookie = CookieManager.getInstance().getCookie(request.url)
        if (!downloads.save(request, destination, cookie) { result ->
                if (isFinishing || isDestroyed) return@save
                when (result) {
                    AndroidDownloads.Result.SAVED -> Toast.makeText(this,
                        DshUiLanguage.text(this@MainActivity, R.string.download_saved, request.filename), Toast.LENGTH_LONG).show()
                    AndroidDownloads.Result.FAILED -> Toast.makeText(this,
                        DshUiLanguage.text(this@MainActivity, R.string.download_failed), Toast.LENGTH_LONG).show()
                    AndroidDownloads.Result.CANCELLED -> Unit
                }
            }) Toast.makeText(this, DshUiLanguage.text(this@MainActivity, R.string.download_busy), Toast.LENGTH_SHORT).show()
    }

    private fun chooseDownloadDestination(url: String, userAgent: String?, disposition: String?, mimeType: String?) {
        if (!AndroidDownloadPolicy.isAllowed(url, webView.url, lastReadyLaunchUrl)) {
            Toast.makeText(this, DshUiLanguage.text(this@MainActivity, R.string.download_local_only), Toast.LENGTH_LONG).show()
            return
        }
        if (pendingDownload != null || downloads.isRunning) {
            Toast.makeText(this, DshUiLanguage.text(this@MainActivity, R.string.download_busy), Toast.LENGTH_SHORT).show()
            return
        }
        val filename = AndroidDownloadPolicy.filename(URLUtil.guessFileName(url, disposition, mimeType))
        val type = mimeType?.substringBefore(';')?.trim()?.takeIf { it.contains('/') }
            ?: "application/octet-stream"
        pendingDownload = AndroidDownloadRequest(url, filename, type, userAgent ?: webView.settings.userAgentString)
        val intent = Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            setType(type)
            putExtra(Intent.EXTRA_TITLE, filename)
        }
        try {
            startActivityForResult(intent, DOWNLOAD_DESTINATION_REQUEST)
        } catch (_: ActivityNotFoundException) {
            pendingDownload = null
            Toast.makeText(this, DshUiLanguage.text(this@MainActivity, R.string.file_picker_unavailable), Toast.LENGTH_LONG).show()
        }
    }

    private fun startDsh() {
        startForegroundService(Intent(this, DshService::class.java).setAction(DshService.ACTION_START))
    }

    private fun openExternalUrl(uri: Uri) {
        try {
            startActivity(Intent(Intent.ACTION_VIEW, uri))
        } catch (_: ActivityNotFoundException) {
            Toast.makeText(this, DshUiLanguage.text(this@MainActivity, R.string.external_browser_unavailable), Toast.LENGTH_SHORT).show()
        }
    }

    private fun openConfigurationDocument() {
        val uri = ConfigurationDocumentProvider.uri(this)
        try {
            // Prove that the provider-owned document exists before launching
            // an editor. The frontend has already prepared it through DSH.
            contentResolver.openFileDescriptor(uri, "r")?.use { }
                ?: throw java.io.FileNotFoundException("Configuration unavailable")
            for (action in listOf(Intent.ACTION_EDIT, Intent.ACTION_VIEW)) {
                val intent = Intent(action).apply {
                    setDataAndType(uri, "text/plain")
                    clipData = ClipData.newRawUri(ConfigurationDocumentPolicy.FILENAME, uri)
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    if (action == Intent.ACTION_EDIT) addFlags(Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
                }
                if (intent.resolveActivity(packageManager) == null) continue
                try {
                    startActivity(Intent.createChooser(intent, DshUiLanguage.text(this@MainActivity, R.string.configuration_open_with)))
                    return
                } catch (_: ActivityNotFoundException) { }
            }
            // Stock Android need not include a YAML/text editor. Keep the
            // button useful by opening the real system document picker for a
            // copy, rather than silently failing the desktop open command.
            if (pendingConfigurationExport) return
            val intent = Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
                addCategory(Intent.CATEGORY_OPENABLE)
                type = "application/yaml"
                putExtra(Intent.EXTRA_TITLE, ConfigurationDocumentPolicy.FILENAME)
            }
            pendingConfigurationExport = true
            try {
                startActivityForResult(intent, CONFIGURATION_EXPORT_REQUEST)
                Toast.makeText(this, DshUiLanguage.text(this@MainActivity, R.string.configuration_save_copy), Toast.LENGTH_LONG).show()
            } catch (_: ActivityNotFoundException) {
                pendingConfigurationExport = false
                Toast.makeText(this, DshUiLanguage.text(this@MainActivity, R.string.file_picker_unavailable), Toast.LENGTH_LONG).show()
            }
        } catch (_: Exception) {
            Toast.makeText(this, DshUiLanguage.text(this@MainActivity, R.string.configuration_open_failed), Toast.LENGTH_LONG).show()
        }
    }

    private fun saveConfigurationCopy(destination: Uri) {
        Thread({
            val saved = runCatching {
                contentResolver.openInputStream(ConfigurationDocumentProvider.uri(this)).use { input ->
                    requireNotNull(input)
                    contentResolver.openOutputStream(destination, "wt").use { output ->
                        requireNotNull(output)
                        val buffer = ByteArray(32 * 1024)
                        var total = 0L
                        while (true) {
                            val count = input.read(buffer)
                            if (count < 0) break
                            total += count
                            check(total <= 16L * 1024 * 1024) { "Configuration document is too large" }
                            output.write(buffer, 0, count)
                        }
                        output.flush()
                    }
                }
            }.isSuccess
            handler.post {
                if (!isFinishing && !isDestroyed) Toast.makeText(this,
                    if (saved) DshUiLanguage.text(this@MainActivity, R.string.download_saved, ConfigurationDocumentPolicy.FILENAME)
                    else DshUiLanguage.text(this@MainActivity, R.string.download_failed), Toast.LENGTH_LONG).show()
            }
        }, "dsh-configuration-copy").apply { isDaemon = true }.start()
    }

    private fun showError(message: String) {
        terminalError = true
        readyHostConfirmed = false
        downloadPageReady = false
        if (pendingDownloadDestination != null) {
            pendingDownload = null
            pendingDownloadDestination = null
            Toast.makeText(this, DshUiLanguage.text(this@MainActivity, R.string.download_failed), Toast.LENGTH_LONG).show()
        }
        ++uiCheckGeneration
        lastErrorMessage = StartupDiagnostics.sanitize(message)
        errorBar.text = DshUiLanguage.text(this@MainActivity, R.string.error_summary, lastErrorMessage?.lineSequence()?.firstOrNull().orEmpty())
        errorBar.visibility = View.VISIBLE
        // Existing DSH content and genuine WebView error pages remain visible.
        if (webView.url != null && webView.url != "about:blank") webView.visibility = View.VISIBLE
    }

    private fun showErrorDetails() {
        val message = lastErrorMessage ?: return
        val text = TextView(this).apply {
            this.text = message
            setPadding(dp(16), dp(12), dp(16), dp(12))
            setTextIsSelectable(true)
        }
        val scroll = ScrollView(this).apply { addView(text) }
        AlertDialog.Builder(this)
            .setTitle(DshUiLanguage.text(this@MainActivity, R.string.error_details))
            .setView(scroll)
            .setPositiveButton(DshUiLanguage.text(this@MainActivity, R.string.copy_error)) { _, _ ->
                (getSystemService(CLIPBOARD_SERVICE) as ClipboardManager)
                    .setPrimaryClip(ClipData.newPlainText(DshUiLanguage.text(this@MainActivity, R.string.app_name), message))
                Toast.makeText(this, DshUiLanguage.text(this@MainActivity, R.string.error_copied), Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton(DshUiLanguage.text(this@MainActivity, android.R.string.cancel), null)
            .show()
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    private fun navigateBackOrFinish() {
        if (!::webView.isInitialized) {
            finish()
            return
        }
        if (webView.rootWindowInsets?.isVisible(WindowInsets.Type.ime()) == true) {
            window.insetsController?.hide(WindowInsets.Type.ime())
            return
        }
        if (backNavigationPending) return
        if (!WebNavigation.isCurrentOrigin(webView.url, lastReadyLaunchUrl)) {
            if (webView.canGoBack()) webView.goBack() else finish()
            return
        }
        backNavigationPending = true
        webView.evaluateJavascript(BACK_SCRIPT) { result ->
            backNavigationPending = false
            if (isFinishing || isDestroyed || result == "true") return@evaluateJavascript
            if (webView.canGoBack()) webView.goBack() else finish()
        }
    }

    private fun registerBackCallback() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
        val callback = OnBackInvokedCallback { navigateBackOrFinish() }
        backInvokedCallback = callback
        onBackInvokedDispatcher.registerOnBackInvokedCallback(OnBackInvokedDispatcher.PRIORITY_DEFAULT, callback)
    }

    private fun unregisterBackCallback() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
        val callback = backInvokedCallback as? OnBackInvokedCallback ?: return
        onBackInvokedDispatcher.unregisterOnBackInvokedCallback(callback)
        backInvokedCallback = null
    }

    @Suppress("DEPRECATION", "OVERRIDE_DEPRECATION")
    override fun onBackPressed() = navigateBackOrFinish()

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        if (::webView.isInitialized) webView.saveState(outState)
        lastReadyLaunchUrl?.let { outState.putString(STATE_LAUNCH_URL, it) }
        lastErrorMessage?.let { outState.putString(STATE_ERROR, it) }
        pendingDownload?.let { request ->
            outState.putBundle(STATE_PENDING_DOWNLOAD, Bundle().apply {
                putString("url", request.url)
                putString("filename", request.filename)
                putString("mime", request.mimeType)
                putString("userAgent", request.userAgent)
                pendingDownloadDestination?.let { putString("destination", it.toString()) }
            })
        }
        // Callback objects and running transfer handles belong to this Activity
        // and are cancelled on destruction. The replacement must say so.
        outState.putBoolean(STATE_FILE_OPERATION_INTERRUPTED,
            pendingFileSelection != null || downloads.isRunning)
        outState.putBoolean(STATE_CONFIGURATION_EXPORT, pendingConfigurationExport)
        pendingNotificationSession?.let { outState.putString(STATE_NOTIFICATION_SESSION, it) }
        workspaceRequest?.let { request ->
            outState.putBundle(STATE_WORKSPACE_REQUEST, Bundle().apply {
                putString("id", request.id); putString("ready", request.readyUrl); putString("stage", request.stage)
                putString("path", request.path); putString("status", request.status); putString("error", request.error)
                putString("tree", request.treeUri)
            })
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        androidSettingsBridge.onRequestPermissionsResult(requestCode)
    }

    override fun onDestroy() {
        androidSettingsBridge.invalidate()
        androidSettingsPortInstalled = false
        clearWorkspaceRequest()
        themeReadAllowed = false
        closeThemePorts()
        pendingDownload = null
        pendingDownloadDestination = null
        downloads.close()
        pendingFileSelection?.onReceiveValue(null)
        pendingFileSelection = null
        ++uiCheckGeneration
        handler.removeCallbacksAndMessages(null)
        unregisterBackCallback()
        unregisterReceiver(receiver)
        webView.destroy()
        super.onDestroy()
    }

    companion object {
        private const val STATE_ERROR = "dsh.startup.error"
        private const val STATE_LAUNCH_URL = "dsh.launch.url"
        private const val STATE_PENDING_DOWNLOAD = "dsh.pending.download"
        private const val STATE_FILE_OPERATION_INTERRUPTED = "dsh.file.operation.interrupted"
        private const val STATE_CONFIGURATION_EXPORT = "dsh.configuration.export"
        private const val STATE_NOTIFICATION_SESSION = "dsh.notification.session"
        private const val FILE_SELECTION_REQUEST = 43
        private const val ADB_FILE_REQUEST = 51
        private const val DOWNLOAD_DESTINATION_REQUEST = 44
        private const val CONFIGURATION_EXPORT_REQUEST = 45
        private const val WORKSPACE_PERMISSION_REQUEST = 49
        private const val WORKSPACE_FOLDER_REQUEST = 50
        private const val STATE_WORKSPACE_REQUEST = "dsh.workspace.request"
        private val BACK_SCRIPT = """
            (function () {
                try {
                    return typeof window.__DSH_ANDROID_BACK__ === 'function' &&
                        window.__DSH_ANDROID_BACK__() === true;
                } catch (error) { return false; }
            })();
        """.trimIndent()
        private val UI_STATE_SCRIPT = """
            (function () {
                const root = document.getElementById('root');
                const boot = root && root.querySelector('[data-dsh-boot]');
                const error = boot && /Failed to load plugins/.test(boot.innerText) ? boot.innerText : '';
                return {ready: !!root && root.childElementCount > 0 && !boot, error: error};
            })();
        """.trimIndent()
    }

    private data class WorkspaceRequest(val id: String, val readyUrl: String, var stage: String = "permission",
        var path: String? = null, var status: String = "cancelled", var error: String? = null, var treeUri: String? = null)
}
