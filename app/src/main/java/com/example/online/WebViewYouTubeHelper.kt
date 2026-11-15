package com.example.online

import android.annotation.SuppressLint
import android.content.Context
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.webkit.CookieManager
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import com.example.network.NetworkClient
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.Request
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Headless / offscreen WebView helper for modern YouTube stream unlocking:
 * 1. Fetches & parses YouTube base.js signatureTimestamp (`sts`), signature cipher steps, and `n`-transform.
 * 2. Executes real Blink/V8 JS cipher & `n`-parameter functions inside a lightweight offscreen WebView.
 * 3. Intercepts live YouTube embed playback (`googlevideo.com/videoplayback` + `pot=` PoToken) as a
 *    resilient fallback when direct InnerTube API profiles hit HTTP 403.
 */
object WebViewYouTubeHelper {

    private const val TAG = "WebViewYTHelper"
    private val decipherMutex = Mutex()
    private val embedMutex = Mutex()

    @Volatile
    private var appContext: Context? = null

    @Volatile
    private var cachedPlayerJsUrl: String? = null

    @Volatile
    private var cachedSts: Int? = null

    @Volatile
    private var cachedPlayerJsBody: String? = null

    @Volatile
    private var cachedPlayerJsFetchedAtMs: Long = 0L

    @Volatile
    private var cachedPoToken: String? = null

    @Volatile
    private var poTokenFetchedAtMs: Long = 0L

    private val nTransformCache = ConcurrentHashMap<String, String>()

    fun init(context: Context) {
        appContext = context.applicationContext
    }

    fun getCachedPoToken(): String? {
        val tok = cachedPoToken
        return if (!tok.isNullOrBlank() && System.currentTimeMillis() - poTokenFetchedAtMs < 20 * 60_000L) {
            tok
        } else null
    }

    fun getCachedSignatureTimestamp(): Int? = cachedSts

    /**
     * Fetches the active YouTube player.js URL & `sts` (signatureTimestamp) from embed/watch HTML
     * using lightweight OkHttp (safe to call off main thread).
     */
    fun ensurePlayerJsMetadata(videoIdHint: String = "dQw4w9WgXcQ"): Pair<String?, Int?> {
        val now = System.currentTimeMillis()
        if (!cachedPlayerJsUrl.isNullOrBlank() && cachedSts != null && now - cachedPlayerJsFetchedAtMs < 30 * 60_000L) {
            return cachedPlayerJsUrl to cachedSts
        }
        return try {
            val req = Request.Builder()
                .url("https://www.youtube.com/embed/$videoIdHint")
                .header("User-Agent", NetworkClient.BROWSER_UA)
                .header("Accept-Language", "en-US,en;q=0.9")
                .build()
            NetworkClient.fastProbeClient.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) return cachedPlayerJsUrl to cachedSts
                val html = resp.body?.string() ?: return cachedPlayerJsUrl to cachedSts
                val stsMatch = Regex(""""STS"\s*:\s*(\d+)""").find(html)
                    ?: Regex("""sts\s*:\s*(\d+)""").find(html)
                val sts = stsMatch?.groupValues?.getOrNull(1)?.toIntOrNull() ?: cachedSts

                val jsPath = Regex("""(/s/player/[a-zA-Z0-9_-]+(?:/player_ias\.vflset/[^"']+|/base\.js))""")
                    .find(html)?.groupValues?.getOrNull(1)
                val fullJsUrl = if (!jsPath.isNullOrBlank()) "https://www.youtube.com$jsPath" else cachedPlayerJsUrl

                if (!fullJsUrl.isNullOrBlank() && fullJsUrl != cachedPlayerJsUrl) {
                    val jsReq = Request.Builder()
                        .url(fullJsUrl)
                        .header("User-Agent", NetworkClient.BROWSER_UA)
                        .build()
                    NetworkClient.fastProbeClient.newCall(jsReq).execute().use { jsResp ->
                        if (jsResp.isSuccessful) {
                            cachedPlayerJsBody = jsResp.body?.string()
                        }
                    }
                }
                cachedPlayerJsUrl = fullJsUrl
                if (sts != null) cachedSts = sts
                cachedPlayerJsFetchedAtMs = now
                fullJsUrl to sts
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to fetch player.js metadata: ${e.message}")
            cachedPlayerJsUrl to cachedSts
        }
    }

    /**
     * Parses a YouTube `signatureCipher` / `cipher` query-encoded field (`url=...&sp=sig&s=...`)
     * and deciphers `s` + unthrottles the `n` parameter using V8 inside an offscreen WebView.
     */
    suspend fun decipherSignatureCipherUrl(
        signatureCipher: String,
        videoIdHint: String = "dQw4w9WgXcQ"
    ): String? = withContext(Dispatchers.IO) {
        if (signatureCipher.isBlank()) return@withContext null
        val params = parseQueryParams(signatureCipher)
        val rawUrl = params["url"] ?: return@withContext null
        val encryptedSig = params["s"]
        val sigParamName = params["sp"] ?: "signature"

        val (_, _) = ensurePlayerJsMetadata(videoIdHint)
        val jsBody = cachedPlayerJsBody

        var finalUrl = rawUrl
        if (!encryptedSig.isNullOrBlank()) {
            val decipheredSig = if (!jsBody.isNullOrBlank()) {
                evalSignatureInWebView(jsBody, encryptedSig)
            } else null

            if (decipheredSig.isNullOrBlank()) {
                return@withContext null
            }
            finalUrl = appendOrReplaceQueryParam(finalUrl, sigParamName, decipheredSig)
        }

        // Also unthrottle `n` parameter if present so CDN doesn't rate-limit or 403
        val nVal = runCatching { Uri.parse(finalUrl).getQueryParameter("n") }.getOrNull()
        if (!nVal.isNullOrBlank() && !jsBody.isNullOrBlank()) {
            val transformedN = transformNParamInWebView(jsBody, nVal)
            if (!transformedN.isNullOrBlank() && transformedN != nVal) {
                finalUrl = appendOrReplaceQueryParam(finalUrl, "n", transformedN)
            }
        }
        finalUrl
    }

    /**
     * Unthrottles an existing plain stream URL's `n` query parameter using player.js in WebView.
     */
    suspend fun unthrottleUrlIfNeeded(url: String, videoIdHint: String = "dQw4w9WgXcQ"): String =
        withContext(Dispatchers.IO) {
            val nVal = runCatching { Uri.parse(url).getQueryParameter("n") }.getOrNull()
            if (nVal.isNullOrBlank()) return@withContext url
            nTransformCache[nVal]?.let { cached ->
                return@withContext appendOrReplaceQueryParam(url, "n", cached)
            }
            ensurePlayerJsMetadata(videoIdHint)
            val jsBody = cachedPlayerJsBody ?: return@withContext url
            val transformed = transformNParamInWebView(jsBody, nVal)
            if (!transformed.isNullOrBlank() && transformed != nVal) {
                nTransformCache[nVal] = transformed
                appendOrReplaceQueryParam(url, "n", transformed)
            } else {
                url
            }
        }

    /**
     * Loads YouTube's official embed player in an offscreen WebView to bypass BotGuard/403
     * and intercepts the live `googlevideo.com/videoplayback` audio stream request + `pot` PoToken.
     */
    @SuppressLint("SetJavaScriptEnabled")
    suspend fun resolveViaWebViewEmbedIntercept(videoId: String): ResolvedStream? {
        val ctx = appContext ?: return null
        if (videoId.isBlank()) return null

        return embedMutex.withLock {
            val t0 = System.currentTimeMillis()
            val resultDeferred = CompletableDeferred<ResolvedStream?>()
            val finished = AtomicBoolean(false)
            val mainHandler = Handler(Looper.getMainLooper())
            var webViewRef: WebView? = null

            val cleanupOnMain: () -> Unit = {
                mainHandler.post {
                    runCatching {
                        webViewRef?.apply {
                            stopLoading()
                            loadUrl("about:blank")
                            removeAllViews()
                            destroy()
                        }
                        webViewRef = null
                    }
                }
            }

            withTimeoutOrNull(7_500L) {
                mainHandler.post {
                    runCatching {
                        CookieManager.getInstance().setAcceptCookie(true)
                        val wv = WebView(ctx).apply {
                            settings.javaScriptEnabled = true
                            settings.domStorageEnabled = true
                            settings.mediaPlaybackRequiresUserGesture = false
                            settings.userAgentString = NetworkClient.BROWSER_UA
                            settings.cacheMode = WebSettings.LOAD_DEFAULT
                        }
                        webViewRef = wv

                        wv.webViewClient = object : WebViewClient() {
                            override fun shouldInterceptRequest(
                                view: WebView?,
                                request: WebResourceRequest?
                            ): WebResourceResponse? {
                                val reqUrl = request?.url?.toString() ?: return null
                                if (reqUrl.contains("googlevideo.com/videoplayback")) {
                                    val parsed = Uri.parse(reqUrl)
                                    val mime = parsed.getQueryParameter("mime") ?: ""
                                    val pot = parsed.getQueryParameter("pot")
                                    if (!pot.isNullOrBlank()) {
                                        cachedPoToken = pot
                                        poTokenFetchedAtMs = System.currentTimeMillis()
                                    }
                                    val itag = parsed.getQueryParameter("itag") ?: ""
                                    val isAudio = mime.startsWith("audio/") ||
                                        itag in setOf("140", "139", "141", "249", "250", "251")
                                    if (isAudio && finished.compareAndSet(false, true)) {
                                        val cleanUrl = stripRangeParams(reqUrl)
                                        val ua = wv.settings.userAgentString ?: NetworkClient.BROWSER_UA
                                        val headers = mapOf(
                                            "Origin" to "https://www.youtube.com",
                                            "Referer" to "https://www.youtube.com/"
                                        )
                                        SourceDiagnosticsLog.record(
                                            SourceAttemptLog(
                                                sourceId = "webview_decipher",
                                                displayName = "YouTube • WebView JS",
                                                stage = "intercept",
                                                status = SourceAttemptStatus.SUCCESS,
                                                httpCode = 200,
                                                detail = if (!pot.isNullOrBlank()) {
                                                    "Перехвачен поток с PoToken (itag=$itag)"
                                                } else {
                                                    "Перехвачен аудиопоток (itag=$itag)"
                                                },
                                                latencyMs = (System.currentTimeMillis() - t0).coerceAtLeast(1L)
                                            )
                                        )
                                        resultDeferred.complete(
                                            ResolvedStream(
                                                url = cleanUrl,
                                                userAgent = ua,
                                                headers = headers,
                                                expiresAtMs = System.currentTimeMillis() + (2 * 3600_000L),
                                                engine = "webview_decipher"
                                            )
                                        )
                                        cleanupOnMain()
                                    }
                                }
                                return super.shouldInterceptRequest(view, request)
                            }

                            override fun onPageFinished(view: WebView?, url: String?) {
                                // Mute video element and trigger playback so googlevideo audio request fires
                                val triggerJs = """
                                    (function() {
                                      try {
                                        var vids = document.querySelectorAll('video, audio');
                                        for (var i = 0; i < vids.length; i++) {
                                          vids[i].muted = true;
                                          vids[i].volume = 0;
                                          if (vids[i].play) vids[i].play().catch(function(){});
                                        }
                                        var btn = document.querySelector('.ytp-large-play-button, button[aria-label*="Play"]');
                                        if (btn) btn.click();
                                      } catch(e) {}
                                    })();
                                """.trimIndent()
                                view?.evaluateJavascript(triggerJs, null)
                            }
                        }

                        val headers = mapOf("Referer" to "https://www.youtube.com/")
                        wv.loadUrl("https://www.youtube.com/embed/$videoId?autoplay=1&mute=1&playsinline=1", headers)
                    }.onFailure { err ->
                        Log.w(TAG, "WebView embed failed: ${err.message}")
                        if (finished.compareAndSet(false, true)) {
                            resultDeferred.complete(null)
                        }
                    }
                }
                resultDeferred.await()
            }.also { res ->
                if (res == null) {
                    cleanupOnMain()
                    if (finished.compareAndSet(false, true)) {
                        SourceDiagnosticsLog.record(
                            SourceAttemptLog(
                                sourceId = "webview_decipher",
                                displayName = "YouTube • WebView JS",
                                stage = "intercept",
                                status = SourceAttemptStatus.TIMEOUT,
                                httpCode = null,
                                detail = "WebView не перехватил аудиопоток за 7.5с",
                                latencyMs = (System.currentTimeMillis() - t0).coerceAtLeast(1L)
                            )
                        )
                    }
                }
            }
        }
    }

    private suspend fun evalSignatureInWebView(playerJsBody: String, encryptedSig: String): String? {
        val ctx = appContext ?: return null
        val snippet = extractSignatureDecipherSnippet(playerJsBody, encryptedSig) ?: return null
        return evaluateJsInEphemeralWebView(ctx, snippet)
    }

    private suspend fun transformNParamInWebView(playerJsBody: String, nParam: String): String? {
        val ctx = appContext ?: return null
        val snippet = extractNTransformSnippet(playerJsBody, nParam) ?: return null
        return evaluateJsInEphemeralWebView(ctx, snippet)
    }

    @SuppressLint("SetJavaScriptEnabled")
    private suspend fun evaluateJsInEphemeralWebView(ctx: Context, script: String): String? =
        decipherMutex.withLock {
            val deferred = CompletableDeferred<String?>()
            val mainHandler = Handler(Looper.getMainLooper())
            withTimeoutOrNull(3_000L) {
                mainHandler.post {
                    runCatching {
                        val wv = WebView(ctx).apply {
                            settings.javaScriptEnabled = true
                        }
                        wv.evaluateJavascript(script) { raw ->
                            val cleaned = raw?.trim()
                                ?.removeSurrounding("\"")
                                ?.takeIf { it.isNotBlank() && it != "null" && it != "undefined" }
                            runCatching {
                                wv.removeAllViews()
                                wv.destroy()
                            }
                            deferred.complete(cleaned)
                        }
                    }.onFailure {
                        deferred.complete(null)
                    }
                }
                deferred.await()
            }
        }

    internal fun extractSignatureDecipherSnippet(playerJs: String, sig: String): String? {
        // Common YouTube player.js decipher wrapper pattern:
        // xx=function(a){a=a.split("");yy.zz(a,3);...return a.join("")};
        val sigFnMatch = Regex(
            """([a-zA-Z0-9_$]+)\s*=\s*function\(([a-zA-Z0-9_$]+)\)\s*\{\s*\2\s*=\s*\2\.split\(["']{2}\)\s*;([\s\S]{10,500}?)\breturn\s+\2\.join\(["']{2}\)\s*\}"""
        ).find(playerJs) ?: return null

        val argName = sigFnMatch.groupValues[2]
        val fnBody = sigFnMatch.groupValues[3]
        // Find helper object name used inside fnBody, e.g. `Ab.cd(a,3)`
        val helperName = Regex("""([a-zA-Z0-9_$]{2,})\.[a-zA-Z0-9_$]+\($argName""").find(fnBody)
            ?.groupValues?.getOrNull(1)
        val helperDef = if (!helperName.isNullOrBlank()) {
            val esc = Regex.escape(helperName)
            Regex("""var\s+$esc\s*=\s*\{[\s\S]{15,800}?\};""").find(playerJs)?.value ?: ""
        } else ""

        val safeSig = sig.replace("\\", "\\\\").replace("\"", "\\\"")
        return """
            (function() {
              try {
                $helperDef
                var decipherFn = function($argName) {
                  $argName = $argName.split("");
                  $fnBody
                  return $argName.join("");
                };
                return decipherFn("$safeSig");
              } catch (e) { return ""; }
            })();
        """.trimIndent()
    }

    internal fun extractNTransformSnippet(playerJs: String, nParam: String): String? {
        // Look up the `n` transform function referenced by `.get("n"))&&(b=...([a-zA-Z0-9_$]+)`
        val callMatch = Regex("""\.get\("n"\)\)\s*&&\s*\([a-zA-Z0-9_$]+\s*=\s*([a-zA-Z0-9_$]+)(?:\[(\d+)\])?\(""").find(playerJs)
            ?: return null
        var fnName = callMatch.groupValues[1]
        val arrIndex = callMatch.groupValues[2].toIntOrNull()
        if (arrIndex != null) {
            val esc = Regex.escape(fnName)
            val arrMatch = Regex("""var\s+$esc\s*=\s*\[([^\]]+)\]""").find(playerJs)
            val items = arrMatch?.groupValues?.getOrNull(1)?.split(",")?.map { it.trim() }
            fnName = items?.getOrNull(arrIndex) ?: fnName
        }

        val escFn = Regex.escape(fnName)
        val defStart = Regex("""(?:var\s+$escFn\s*=\s*function\(|$escFn\s*=\s*function\(|function\s+$escFn\()""")
            .find(playerJs)?.range?.first ?: return null
        val bodyEnd = findMatchingBraceEnd(playerJs, playerJs.indexOf('{', defStart)) ?: return null
        val fnCode = playerJs.substring(defStart, bodyEnd + 1)

        val safeN = nParam.replace("\\", "\\\\").replace("\"", "\\\"")
        return """
            (function() {
              try {
                $fnCode
                return $fnName("$safeN");
              } catch (e) { return "$safeN"; }
            })();
        """.trimIndent()
    }

    private fun findMatchingBraceEnd(text: String, openBraceIdx: Int): Int? {
        if (openBraceIdx < 0 || openBraceIdx >= text.length) return null
        var depth = 0
        var inSingle = false
        var inDouble = false
        var escape = false
        for (i in openBraceIdx until text.length) {
            val c = text[i]
            if (escape) {
                escape = false
                continue
            }
            if (c == '\\') {
                escape = true
                continue
            }
            if (!inDouble && c == '\'') {
                inSingle = !inSingle
                continue
            }
            if (!inSingle && c == '"') {
                inDouble = !inDouble
                continue
            }
            if (!inSingle && !inDouble) {
                if (c == '{') depth++
                else if (c == '}') {
                    depth--
                    if (depth == 0) return i
                }
            }
        }
        return null
    }

    internal fun parseQueryParams(query: String): Map<String, String> {
        val out = LinkedHashMap<String, String>()
        for (pair in query.split("&")) {
            val eq = pair.indexOf('=')
            if (eq > 0) {
                val k = Uri.decode(pair.substring(0, eq))
                val v = Uri.decode(pair.substring(eq + 1))
                out[k] = v
            }
        }
        return out
    }

    private fun appendOrReplaceQueryParam(url: String, key: String, value: String): String {
        return try {
            val uri = Uri.parse(url)
            val builder = uri.buildUpon().clearQuery()
            var replaced = false
            for (name in uri.queryParameterNames) {
                if (name == key) {
                    builder.appendQueryParameter(name, value)
                    replaced = true
                } else {
                    uri.getQueryParameters(name).forEach { builder.appendQueryParameter(name, it) }
                }
            }
            if (!replaced) {
                builder.appendQueryParameter(key, value)
            }
            builder.build().toString()
        } catch (_: Exception) {
            val sep = if (url.contains("?")) "&" else "?"
            "$url$sep${Uri.encode(key)}=${Uri.encode(value)}"
        }
    }

    private fun stripRangeParams(url: String): String {
        return try {
            val uri = Uri.parse(url)
            val builder = uri.buildUpon().clearQuery()
            for (name in uri.queryParameterNames) {
                if (name == "range" || name == "rn" || name == "rbuf") continue
                uri.getQueryParameters(name).forEach { builder.appendQueryParameter(name, it) }
            }
            builder.build().toString()
        } catch (_: Exception) {
            url
        }
    }
}
