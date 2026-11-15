package com.example.ui.spotify

import android.annotation.SuppressLint
import android.os.Handler
import android.os.Looper
import android.webkit.CookieManager
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.example.ui.components.LinearWavyProgressIndicator

private val SpotifyAccent = Color(0xFF1DB954)

private const val CAPTURE_HOOK_JS = """
(function() {
  if (window.__imuxSpotifyHooked) return;
  window.__imuxSpotifyHooked = true;
  var capturedAuth = null;
  var capturedClient = null;
  function notify() {
    if (capturedAuth && window.ImuxBridge) {
      window.ImuxBridge.onCredentialsCaptured(capturedAuth, capturedClient || "");
    }
  }
  var origFetch = window.fetch;
  if (origFetch) {
    window.fetch = function(input, init) {
      try {
        var hdrs = init && init.headers;
        if (hdrs) {
          if (typeof hdrs.forEach === 'function') {
            hdrs.forEach(function(v, k) {
              var kl = String(k).toLowerCase();
              if (kl === 'authorization' && String(v).indexOf('Bearer ') === 0) capturedAuth = String(v).substring(7);
              if (kl === 'client-token') capturedClient = String(v);
            });
          } else {
            for (var k in hdrs) {
              var kl = String(k).toLowerCase();
              if (kl === 'authorization' && String(hdrs[k]).indexOf('Bearer ') === 0) capturedAuth = String(hdrs[k]).substring(7);
              if (kl === 'client-token') capturedClient = String(hdrs[k]);
            }
          }
          notify();
        }
      } catch (e) {}
      return origFetch.apply(this, arguments).then(function(resp) {
        try {
          if (resp && resp.url && resp.url.indexOf('get_access_token') !== -1) {
            resp.clone().json().then(function(data) {
              if (data && data.accessToken && !data.isAnonymous) {
                capturedAuth = data.accessToken;
                notify();
              }
            }).catch(function(){});
          }
        } catch (e) {}
        return resp;
      });
    };
  }
  var origSetHdr = XMLHttpRequest.prototype.setRequestHeader;
  XMLHttpRequest.prototype.setRequestHeader = function(name, val) {
    try {
      var nl = String(name).toLowerCase();
      if (nl === 'authorization' && String(val).indexOf('Bearer ') === 0) capturedAuth = String(val).substring(7);
      if (nl === 'client-token') capturedClient = String(val);
      notify();
    } catch (e) {}
    return origSetHdr.apply(this, arguments);
  };
  try {
    var sessionEl = document.getElementById('session');
    if (sessionEl && sessionEl.textContent) {
      var sess = JSON.parse(sessionEl.textContent);
      if (sess && sess.accessToken && !sess.isAnonymous) {
        capturedAuth = sess.accessToken;
        notify();
      }
    }
  } catch (e) {}
})();
"""

@SuppressLint("SetJavaScriptEnabled")
@Composable
fun SpotifyWebLoginDialog(
    onSessionCaptured: (accessToken: String?, clientToken: String?, spDcCookie: String?) -> Unit,
    onDismiss: () -> Unit
) {
    var pageProgress by remember { mutableIntStateOf(10) }
    var statusText by remember { mutableStateOf("Войдите в свой аккаунт Spotify") }
    var didCapture by remember { mutableStateOf(false) }
    var lastClientToken by remember { mutableStateOf<String?>(null) }
    var webViewRef by remember { mutableStateOf<WebView?>(null) }
    val mainHandler = remember { Handler(Looper.getMainLooper()) }

    fun completeCapture(accessToken: String?, clientToken: String?) {
        mainHandler.post {
            if (didCapture) return@post
            val cleanAuth = accessToken?.removePrefix("Bearer ")?.removePrefix("bearer ")?.trim()
            val cookies = CookieManager.getInstance().getCookie("https://open.spotify.com") ?: ""
            val spDc = cookies.split(";")
                .map { it.trim() }
                .firstOrNull { it.startsWith("sp_dc=") }
                ?.removePrefix("sp_dc=")
                ?.trim()

            if (!cleanAuth.isNullOrBlank()) {
                didCapture = true
                statusText = "Сессия получена! Подключаем плейлисты..."
                onSessionCaptured(cleanAuth, clientToken ?: lastClientToken, spDc)
            }
        }
    }

    DisposableEffect(Unit) {
        onDispose {
            webViewRef?.apply {
                stopLoading()
                removeJavascriptInterface("ImuxBridge")
                destroy()
            }
            webViewRef = null
        }
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Surface(
            modifier = Modifier
                .fillMaxSize()
                .padding(8.dp)
                .testTag("spotify_web_login_dialog"),
            shape = RoundedCornerShape(28.dp),
            color = MaterialTheme.colorScheme.surfaceContainerLow,
            tonalElevation = 6.dp
        ) {
            Column(modifier = Modifier.fillMaxSize()) {
                // Header
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.weight(1f)
                    ) {
                        Surface(
                            shape = CircleShape,
                            color = SpotifyAccent.copy(alpha = 0.16f),
                            modifier = Modifier.size(38.dp)
                        ) {
                            Box(contentAlignment = Alignment.Center) {
                                Icon(
                                    imageVector = Icons.Rounded.Lock,
                                    contentDescription = null,
                                    tint = SpotifyAccent,
                                    modifier = Modifier.size(18.dp)
                                )
                            }
                        }
                        Spacer(modifier = Modifier.width(10.dp))
                        Column {
                            Text(
                                text = "Вход в Spotify (Web)",
                                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.ExtraBold),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            Text(
                                text = statusText,
                                style = MaterialTheme.typography.labelSmall.copy(fontSize = 11.sp),
                                color = SpotifyAccent,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }

                    IconButton(
                        onClick = onDismiss,
                        modifier = Modifier
                            .size(48.dp)
                            .testTag("spotify_webview_close_btn")
                    ) {
                        Icon(imageVector = Icons.Rounded.Close, contentDescription = "Закрыть")
                    }
                }

                if (pageProgress in 1..99) {
                    LinearWavyProgressIndicator(
                        progress = { pageProgress / 100f },
                        isPlaying = true,
                        color = SpotifyAccent,
                        trackColor = MaterialTheme.colorScheme.surfaceContainerHighest,
                        height = 8.dp,
                        strokeWidth = 3.dp,
                        amplitudeDp = 2.dp
                    )
                }

                Spacer(modifier = Modifier.height(2.dp))

                AndroidView(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f),
                    factory = { ctx ->
                        val wv = WebView(ctx)
                        val cm = CookieManager.getInstance()
                        cm.setAcceptCookie(true)
                        cm.setAcceptThirdPartyCookies(wv, true)

                        wv.settings.apply {
                            javaScriptEnabled = true
                            domStorageEnabled = true
                            databaseEnabled = true
                            mixedContentMode = WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE
                            userAgentString =
                                "Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/133.0.0.0 Mobile Safari/537.36"
                        }

                        val bridge = object {
                            @JavascriptInterface
                            fun onCredentialsCaptured(accessToken: String?, clientToken: String?) {
                                if (!clientToken.isNullOrBlank()) {
                                    lastClientToken = clientToken
                                }
                                completeCapture(accessToken, clientToken)
                            }
                        }
                        wv.addJavascriptInterface(bridge, "ImuxBridge")

                        wv.webChromeClient = object : WebChromeClient() {
                            override fun onProgressChanged(view: WebView?, newProgress: Int) {
                                pageProgress = newProgress
                            }
                        }

                        wv.webViewClient = object : WebViewClient() {
                            override fun onPageFinished(view: WebView?, url: String?) {
                                super.onPageFinished(view, url)
                                view?.evaluateJavascript(CAPTURE_HOOK_JS, null)
                                if (url != null && url.contains("open.spotify.com")) {
                                    mainHandler.post {
                                        statusText = "Авторизовано! Считываем токен плейлистов..."
                                    }
                                    // Trigger Web Player token endpoint inside authenticated WebView if not yet captured
                                    view?.evaluateJavascript(
                                        """
                                        (function(){
                                          fetch('/get_access_token?reason=transport&productType=web_player')
                                            .then(function(r){ return r.json(); })
                                            .then(function(d){
                                              if (d && d.accessToken && !d.isAnonymous && window.ImuxBridge) {
                                                window.ImuxBridge.onCredentialsCaptured(d.accessToken, "");
                                              }
                                            }).catch(function(){});
                                        })();
                                        """.trimIndent(),
                                        null
                                    )
                                }
                            }

                            override fun shouldInterceptRequest(
                                view: WebView?,
                                request: WebResourceRequest?
                            ): WebResourceResponse? {
                                val reqUrl = request?.url?.toString().orEmpty()
                                val hdrs = request?.requestHeaders
                                if (hdrs != null && (reqUrl.contains("api.spotify.com") || reqUrl.contains("api-partner.spotify.com"))) {
                                    var authHdr: String? = null
                                    var clientHdr: String? = null
                                    hdrs.forEach { (k, v) ->
                                        if (k.equals("authorization", true) && v.startsWith("Bearer ", true)) {
                                            authHdr = v.substring(7).trim()
                                        }
                                        if (k.equals("client-token", true)) {
                                            clientHdr = v.trim()
                                        }
                                    }
                                    if (!clientHdr.isNullOrBlank()) {
                                        lastClientToken = clientHdr
                                    }
                                    if (!authHdr.isNullOrBlank()) {
                                        completeCapture(authHdr, clientHdr)
                                    }
                                }
                                return super.shouldInterceptRequest(view, request)
                            }
                        }

                        wv.loadUrl("https://accounts.spotify.com/login?continue=https%3A%2F%2Fopen.spotify.com%2F")
                        webViewRef = wv
                        wv
                    }
                )
            }
        }
    }
}
