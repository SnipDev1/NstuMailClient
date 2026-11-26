package ru.nstu.mail.ui

import android.annotation.SuppressLint
import android.app.DownloadManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Environment
import android.webkit.CookieManager
import android.webkit.URLUtil
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Logout
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import org.json.JSONObject
import ru.nstu.mail.data.Credentials
import ru.nstu.mail.data.DEFAULT_SERVER_URL

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun OwaWebViewScreen(
    credentials: Credentials?,
    onBack: () -> Unit,
    onLogout: () -> Unit
) {
    val context = LocalContext.current
    val serverBase = credentials?.serverUrl ?: DEFAULT_SERVER_URL
    val allowedHost = remember(serverBase) { Uri.parse(serverBase).host.orEmpty() }
    val owaUrl = "$serverBase/owa/"

    var webViewRef by remember { mutableStateOf<WebView?>(null) }
    var loadProgress by remember { mutableIntStateOf(0) }
    var filePathCallback by remember { mutableStateOf<ValueCallback<Array<Uri>>?>(null) }
    var autoFilledOnce by remember { mutableStateOf(false) }

    val fileChooserLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenMultipleDocuments()
    ) { uris ->
        filePathCallback?.onReceiveValue(uris.toTypedArray())
        filePathCallback = null
    }

    BackHandler {
        val wv = webViewRef
        if (wv != null && wv.canGoBack()) {
            wv.goBack()
        } else {
            onBack()
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("OWA · $allowedHost") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Назад")
                    }
                },
                actions = {
                    IconButton(onClick = { webViewRef?.reload() }) {
                        Icon(Icons.Default.Refresh, contentDescription = "Обновить")
                    }
                    IconButton(onClick = onLogout) {
                        Icon(Icons.AutoMirrored.Filled.Logout, contentDescription = "Выйти")
                    }
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            if (loadProgress in 1..99) {
                LinearProgressIndicator(
                    progress = { loadProgress / 100f },
                    modifier = Modifier.fillMaxWidth()
                )
            }

            OwaWebViewContent(
                owaUrl = owaUrl,
                allowedHost = allowedHost,
                credentials = credentials,
                autoFilledOnce = autoFilledOnce,
                onAutoFilled = { autoFilledOnce = true },
                onProgressChanged = { loadProgress = it },
                onWebViewReady = { webViewRef = it },
                onFileChooser = { callback ->
                    filePathCallback?.onReceiveValue(null)
                    filePathCallback = callback
                    fileChooserLauncher.launch(arrayOf("*/*"))
                },
                context = context,
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
            )
        }
    }
}

@SuppressLint("SetJavaScriptEnabled")
@Composable
private fun OwaWebViewContent(
    owaUrl: String,
    allowedHost: String,
    credentials: Credentials?,
    autoFilledOnce: Boolean,
    onAutoFilled: () -> Unit,
    onProgressChanged: (Int) -> Unit,
    onWebViewReady: (WebView) -> Unit,
    onFileChooser: (ValueCallback<Array<Uri>>) -> Unit,
    context: Context,
    modifier: Modifier = Modifier
) {
    AndroidView(
        modifier = modifier,
        factory = { ctx ->
            CookieManager.getInstance().apply {
                setAcceptCookie(true)
            }
            WebView(ctx).apply {
                CookieManager.getInstance().setAcceptThirdPartyCookies(this, false)
                settings.apply {
                    javaScriptEnabled = true
                    domStorageEnabled = true
                    allowFileAccess = false
                    allowContentAccess = false
                    mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
                    useWideViewPort = true
                    loadWithOverviewMode = true
                }
                webChromeClient = object : WebChromeClient() {
                    override fun onProgressChanged(view: WebView?, newProgress: Int) {
                        onProgressChanged(newProgress)
                    }

                    override fun onShowFileChooser(
                        webView: WebView?,
                        filePathCallback: ValueCallback<Array<Uri>>?,
                        fileChooserParams: FileChooserParams?
                    ): Boolean {
                        if (filePathCallback == null) return false
                        onFileChooser(filePathCallback)
                        return true
                    }
                }
                webViewClient = object : WebViewClient() {
                    override fun shouldOverrideUrlLoading(
                        view: WebView?,
                        request: WebResourceRequest?
                    ): Boolean {
                        val uri = request?.url ?: return true
                        val isAllowed = uri.scheme == "https" &&
                            uri.host.equals(allowedHost, ignoreCase = true)
                        if (isAllowed) return false

                        if (uri.scheme == "https" || uri.scheme == "http" || uri.scheme == "mailto") {
                            runCatching {
                                context.startActivity(Intent(Intent.ACTION_VIEW, uri))
                            }
                        }
                        return true
                    }

                    override fun onPageFinished(view: WebView?, url: String?) {
                        super.onPageFinished(view, url)
                        if (view == null || url == null || autoFilledOnce) return
                        val parsed = Uri.parse(url)
                        val isLogonPage = parsed.scheme.equals("https", ignoreCase = true) &&
                            parsed.host.equals(allowedHost, ignoreCase = true) &&
                            parsed.path?.contains("/owa/auth/logon", ignoreCase = true) == true &&
                            parsed.getQueryParameter("reason") == null
                        if (isLogonPage && credentials != null &&
                            credentials.email.isNotBlank() && credentials.password.isNotEmpty()
                        ) {
                            onAutoFilled()
                            val userJs = JSONObject.quote(credentials.email)
                            val passJs = JSONObject.quote(credentials.password)
                            val script = """
                                (function() {
                                    var u = document.getElementById('username') || document.querySelector('input[name="username"]');
                                    var p = document.getElementById('password') || document.querySelector('input[name="password"]');
                                    if (u && p) {
                                        u.value = $userJs;
                                        p.value = $passJs;
                                    }
                                })();
                            """.trimIndent()
                            view.evaluateJavascript(script, null)
                        }
                    }
                }
                setDownloadListener { url, userAgent, contentDisposition, mimetype, _ ->
                    val dlUri = Uri.parse(url)
                    val isTrustedDownload = dlUri.scheme.equals("https", ignoreCase = true) &&
                        dlUri.host.equals(allowedHost, ignoreCase = true)
                    if (!isTrustedDownload) {
                        Toast.makeText(ctx, "Загрузка со стороннего домена заблокирована", Toast.LENGTH_SHORT).show()
                        return@setDownloadListener
                    }
                    val fileName = URLUtil.guessFileName(url, contentDisposition, mimetype)
                    val cookies = CookieManager.getInstance().getCookie(url)
                    val req = DownloadManager.Request(dlUri).apply {
                        setMimeType(mimetype)
                        addRequestHeader("User-Agent", userAgent)
                        if (!cookies.isNullOrBlank()) {
                            addRequestHeader("Cookie", cookies)
                        }
                        setTitle(fileName)
                        setNotificationVisibility(
                            DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED
                        )
                        setDestinationInExternalPublicDir(
                            Environment.DIRECTORY_DOWNLOADS,
                            MailViewModel.sanitizeFilename(fileName)
                        )
                    }
                    val dm = ctx.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
                    dm.enqueue(req)
                    Toast.makeText(ctx, "Загрузка: $fileName", Toast.LENGTH_SHORT).show()
                }
                onWebViewReady(this)
                loadUrl(owaUrl)
            }
        }
    )
}
