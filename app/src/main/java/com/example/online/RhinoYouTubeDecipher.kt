package com.example.online

import android.net.Uri
import android.util.Log
import com.example.network.NetworkClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Request
import org.mozilla.javascript.Context as RhinoContext
import java.util.concurrent.ConcurrentHashMap

/**
 * Pure-JVM Mozilla Rhino JS decipherer for YouTube `player.js`:
 * 1. Fetches YouTube embed/watch HTML + `player.js` metadata (`sts` signatureTimestamp).
 * 2. Extracts the signature cipher helper and `n`-parameter transform function cleanly.
 * 3. Evaluates JS inside Rhino in interpreted mode (`optimizationLevel = -1`, safe on ART/Dalvik)
 *    with zero Android `WebView` / Chromium renderer processes.
 */
object RhinoYouTubeDecipher {

    private const val TAG = "RhinoYTDecipher"

    @Volatile
    private var cachedPlayerJsUrl: String? = null

    @Volatile
    private var cachedSts: Int? = null

    @Volatile
    private var cachedPlayerJsBody: String? = null

    @Volatile
    private var cachedPlayerJsFetchedAtMs: Long = 0L

    private val nTransformCache = ConcurrentHashMap<String, String>()
    private val sigTransformCache = ConcurrentHashMap<String, String>()
    private val rhinoLock = Any()

    fun getCachedSignatureTimestamp(): Int? = cachedSts

    fun ensurePlayerJsMetadata(videoIdHint: String = "dQw4w9WgXcQ"): Pair<String?, Int?> {
        val now = System.currentTimeMillis()
        if (!cachedPlayerJsUrl.isNullOrBlank() &&
            !cachedPlayerJsBody.isNullOrBlank() &&
            cachedSts != null &&
            now - cachedPlayerJsFetchedAtMs < 30 * 60_000L
        ) {
            return cachedPlayerJsUrl to cachedSts
        }
        return try {
            var foundJsUrl: String? = null
            var foundSts: Int? = cachedSts
            val pageUrls = listOf(
                "https://www.youtube.com/embed/$videoIdHint",
                "https://www.youtube.com/watch?v=$videoIdHint&bpctr=9999999999&has_verified=1"
            )
            for (pageUrl in pageUrls) {
                if (!foundJsUrl.isNullOrBlank() && foundSts != null) break
                val req = Request.Builder()
                    .url(pageUrl)
                    .header("User-Agent", NetworkClient.BROWSER_UA)
                    .header("Accept-Language", "en-US,en;q=0.9")
                    .build()
                runCatching {
                    NetworkClient.fastProbeClient.newCall(req).execute().use { resp ->
                        if (!resp.isSuccessful) return@use
                        val html = resp.body?.string() ?: return@use
                        if (foundSts == null) {
                            val stsMatch = Regex(""""STS"\s*:\s*(\d+)""").find(html)
                                ?: Regex(""""signatureTimestamp"\s*:\s*(\d+)""").find(html)
                                ?: Regex("""sts\s*:\s*(\d+)""").find(html)
                            foundSts = stsMatch?.groupValues?.getOrNull(1)?.toIntOrNull() ?: foundSts
                        }
                        if (foundJsUrl.isNullOrBlank()) {
                            val rawJsPath = Regex("""(/s/player/[a-zA-Z0-9_-]+(?:/player_ias\.vflset/[^"'\\]+|/base\.js))""")
                                .find(html)?.groupValues?.getOrNull(1)
                                ?: Regex(""""jsUrl"\s*:\s*"([^"]+)"""").find(html)?.groupValues?.getOrNull(1)
                                    ?.replace("\\/", "/")
                            if (!rawJsPath.isNullOrBlank()) {
                                foundJsUrl = if (rawJsPath.startsWith("http")) rawJsPath else "https://www.youtube.com$rawJsPath"
                            }
                        }
                    }
                }
            }

            val fullJsUrl = foundJsUrl ?: cachedPlayerJsUrl
            if (!fullJsUrl.isNullOrBlank() && (fullJsUrl != cachedPlayerJsUrl || cachedPlayerJsBody.isNullOrBlank())) {
                val jsReq = Request.Builder()
                    .url(fullJsUrl)
                    .header("User-Agent", NetworkClient.BROWSER_UA)
                    .build()
                NetworkClient.fastProbeClient.newCall(jsReq).execute().use { jsResp ->
                    if (jsResp.isSuccessful) {
                        cachedPlayerJsBody = jsResp.body?.string()
                        nTransformCache.clear()
                        sigTransformCache.clear()
                    }
                }
            }
            cachedPlayerJsUrl = fullJsUrl
            if (foundSts != null) cachedSts = foundSts
            cachedPlayerJsFetchedAtMs = now
            fullJsUrl to foundSts
        } catch (e: Exception) {
            runCatching { Log.w(TAG, "Failed to fetch player.js metadata: ${e.message}") }
            cachedPlayerJsUrl to cachedSts
        }
    }

    suspend fun decipherSignatureCipherUrl(
        signatureCipher: String,
        videoIdHint: String = "dQw4w9WgXcQ"
    ): String? = withContext(Dispatchers.IO) {
        if (signatureCipher.isBlank()) return@withContext null
        val params = parseQueryParams(signatureCipher)
        val rawUrl = params["url"] ?: return@withContext null
        val encryptedSig = params["s"]
        val sigParamName = params["sp"] ?: "signature"

        ensurePlayerJsMetadata(videoIdHint)
        val jsBody = cachedPlayerJsBody

        var finalUrl = rawUrl
        if (!encryptedSig.isNullOrBlank()) {
            val decipheredSig = sigTransformCache[encryptedSig] ?: if (!jsBody.isNullOrBlank()) {
                val snippet = extractSignatureDecipherSnippet(jsBody, encryptedSig)
                snippet?.let { evalInRhino(it) }?.also { decoded ->
                    if (decoded.isNotBlank()) sigTransformCache[encryptedSig] = decoded
                }
            } else null

            if (decipheredSig.isNullOrBlank()) {
                return@withContext null
            }
            finalUrl = appendOrReplaceQueryParam(finalUrl, sigParamName, decipheredSig)
        }

        val nVal = runCatching { Uri.parse(finalUrl).getQueryParameter("n") }.getOrNull()
        if (!nVal.isNullOrBlank() && !jsBody.isNullOrBlank()) {
            val transformedN = nTransformCache[nVal] ?: run {
                val snippet = extractNTransformSnippet(jsBody, nVal)
                snippet?.let { evalInRhino(it) }?.also { out ->
                    if (out.isNotBlank() && out != nVal) nTransformCache[nVal] = out
                }
            }
            if (!transformedN.isNullOrBlank() && transformedN != nVal) {
                finalUrl = appendOrReplaceQueryParam(finalUrl, "n", transformedN)
            }
        }
        finalUrl
    }

    suspend fun unthrottleUrlIfNeeded(url: String, videoIdHint: String = "dQw4w9WgXcQ"): String =
        withContext(Dispatchers.IO) {
            val nVal = runCatching { Uri.parse(url).getQueryParameter("n") }.getOrNull()
            if (nVal.isNullOrBlank()) return@withContext url
            nTransformCache[nVal]?.let { cached ->
                return@withContext appendOrReplaceQueryParam(url, "n", cached)
            }
            ensurePlayerJsMetadata(videoIdHint)
            val jsBody = cachedPlayerJsBody ?: return@withContext url
            val snippet = extractNTransformSnippet(jsBody, nVal) ?: return@withContext url
            val transformed = evalInRhino(snippet)
            if (!transformed.isNullOrBlank() && transformed != nVal) {
                nTransformCache[nVal] = transformed
                appendOrReplaceQueryParam(url, "n", transformed)
            } else {
                url
            }
        }

    internal fun evalInRhino(script: String): String? = synchronized(rhinoLock) {
        val rhino = RhinoContext.enter()
        return try {
            rhino.optimizationLevel = -1
            rhino.languageVersion = RhinoContext.VERSION_ES6
            val scope = rhino.initSafeStandardObjects()
            val result = rhino.evaluateString(scope, script, "yt_decipher.js", 1, null)
            val str = RhinoContext.toString(result)?.trim()
            str?.takeIf { it.isNotBlank() && it != "null" && it != "undefined" }
        } catch (t: Throwable) {
            runCatching { Log.w(TAG, "Rhino evaluation failed: ${t.message}") }
            null
        } finally {
            RhinoContext.exit()
        }
    }

    internal fun extractSignatureDecipherSnippet(playerJs: String, sig: String): String? {
        val headerMatch = Regex(
            """([a-zA-Z0-9_$]+)\s*=\s*function\(([a-zA-Z0-9_$]+)\)\s*\{\s*\2\s*=\s*\2\.split\(["']{2}\)\s*;"""
        ).find(playerJs) ?: return null

        val fnStartBrace = playerJs.indexOf('{', headerMatch.range.first)
        if (fnStartBrace < 0) return null
        val fnEndBrace = findMatchingBraceEnd(playerJs, fnStartBrace) ?: return null
        val fullFn = playerJs.substring(fnStartBrace + 1, fnEndBrace)
        val argName = headerMatch.groupValues[2]

        val helperNames = Regex("""([a-zA-Z0-9_$]{2,})\.[a-zA-Z0-9_$]+\($argName""")
            .findAll(fullFn)
            .map { it.groupValues[1] }
            .distinct()
            .take(4)
            .toList()

        val helperDefs = helperNames.mapNotNull { h ->
            extractHelperDeclaration(playerJs, h, allowRegexFallback = true)
        }.joinToString("\n")

        val safeSig = sig.replace("\\", "\\\\").replace("\"", "\\\"")
        return """
            (function() {
              $helperDefs
              var decipherFn = function($argName) {
                $fullFn
              };
              return decipherFn("$safeSig");
            })();
        """.trimIndent()
    }

    internal fun extractNTransformSnippet(playerJs: String, nParam: String): String? {
        val callMatch = Regex("""\.get\("n"\)\)\s*&&\s*\([a-zA-Z0-9_$]+\s*=\s*([a-zA-Z0-9_$]+)(?:\[(\d+)\])?\(""").find(playerJs)
            ?: Regex("""\([a-zA-Z0-9_$]+\s*=\s*[a-zA-Z0-9_$]+\.get\(["']n["']\)\)\s*&&\s*\([a-zA-Z0-9_$]+\s*=\s*([a-zA-Z0-9_$]+)(?:\[(\d+)\])?\(""").find(playerJs)
            ?: Regex("""String\.fromCharCode\(110\)[^;]{0,60}\.get\([^)]+\)[^;]{0,40}\(([a-zA-Z0-9_$]+)(?:\[(\d+)\])?\(""").find(playerJs)
            ?: return null
        var fnName = callMatch.groupValues[1]
        val arrIndex = callMatch.groupValues[2].toIntOrNull()
        if (arrIndex != null) {
            val esc = Regex.escape(fnName)
            val arrMatch = Regex("""(?:var|let|const)?\s*$esc\s*=\s*\[([^\]]+)\]""").find(playerJs)
            val items = arrMatch?.groupValues?.getOrNull(1)?.split(",")?.map { it.trim() }
            fnName = items?.getOrNull(arrIndex) ?: fnName
        }

        val escFn = Regex.escape(fnName)
        val defStart = Regex("""(?:(?:var|let|const)\s+$escFn\s*=\s*function\(|$escFn\s*=\s*function\(|function\s+$escFn\()""")
            .find(playerJs)?.range?.first ?: return null
        val openBrace = playerJs.indexOf('{', defStart)
        if (openBrace < 0) return null
        val bodyEnd = findMatchingBraceEnd(playerJs, openBrace) ?: return null
        val fnCode = playerJs.substring(defStart, bodyEnd + 1).let { code ->
            if (code.endsWith(";")) code else "$code;"
        }

        val extraHelpers = collectReferencedHelpers(playerJs, fnCode, excludeName = fnName)

        val safeN = nParam.replace("\\", "\\\\").replace("\"", "\\\"")
        return """
            (function() {
              $extraHelpers
              $fnCode
              return $fnName("$safeN");
            })();
        """.trimIndent()
    }

    private fun collectReferencedHelpers(playerJs: String, fnCode: String, excludeName: String): String {
        val candidateIds = Regex("""\b([a-zA-Z_$][a-zA-Z0-9_$]{1,})\b(?=\s*(?:\[|\(|\.))""")
            .findAll(fnCode)
            .map { it.groupValues[1] }
            .filter {
                it != excludeName && it !in setOf(
                    "function", "return", "var", "let", "const", "for", "if", "else", "while",
                    "switch", "case", "break", "try", "catch", "throw", "new", "typeof",
                    "Array", "String", "Math", "Object", "Number", "Boolean", "Date", "RegExp",
                    "Error", "parseInt", "decodeURIComponent", "encodeURIComponent", "atob", "btoa",
                    "split", "join", "reverse", "splice", "slice", "push", "pop", "shift",
                    "unshift", "indexOf", "charCodeAt", "fromCharCode", "length", "prototype", "forEach", "map"
                )
            }
            .distinct()
            .take(6)
            .toList()

        if (candidateIds.isEmpty()) return ""
        return candidateIds.mapNotNull { id ->
            extractHelperDeclaration(playerJs, id, allowRegexFallback = false)
        }.joinToString("\n")
    }

    private fun extractHelperDeclaration(
        playerJs: String,
        ident: String,
        allowRegexFallback: Boolean
    ): String? {
        val esc = Regex.escape(ident)
        // Object literal or function assignment or bracket array declaration
        val objOrFnMatch = Regex("""(?:(?:var|let|const)\s+$esc\s*=\s*\{|function\s+$esc\s*\([^)]*\)\s*\{|(?:var|let|const)?\s*$esc\s*=\s*function\s*\([^)]*\)\s*\{)""")
            .find(playerJs)
        if (objOrFnMatch != null) {
            val braceIdx = playerJs.indexOf('{', objOrFnMatch.range.first)
            val end = if (braceIdx >= 0) findMatchingBraceEnd(playerJs, braceIdx) else null
            if (end != null && end - objOrFnMatch.range.first < 16_000) {
                val raw = playerJs.substring(objOrFnMatch.range.first, end + 1)
                return if (raw.endsWith(";")) raw else "$raw;"
            }
        }
        val arrMatch = Regex("""(?:var|let|const)\s+$esc\s*=\s*\[""").find(playerJs)
        if (arrMatch != null) {
            val bracketIdx = playerJs.indexOf('[', arrMatch.range.first)
            val end = if (bracketIdx >= 0) findMatchingDelimiterEnd(playerJs, bracketIdx, '[', ']') else null
            if (end != null && end - arrMatch.range.first < 16_000) {
                return playerJs.substring(arrMatch.range.first, end + 1) + ";"
            }
        }
        if (allowRegexFallback) {
            val strMatch = Regex("""(?:var|let|const)\s+$esc\s*=\s*(['"`])[^'"`]{1,200}\1\s*;""").find(playerJs)
            if (strMatch != null) return strMatch.value
        }
        return null
    }

    private fun findMatchingBraceEnd(text: String, openBraceIdx: Int): Int? =
        findMatchingDelimiterEnd(text, openBraceIdx, '{', '}')

    private fun findMatchingDelimiterEnd(text: String, openIdx: Int, openCh: Char, closeCh: Char): Int? {
        if (openIdx < 0 || openIdx >= text.length) return null
        var depth = 0
        var inSingle = false
        var inDouble = false
        var escape = false
        for (i in openIdx until text.length) {
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
                if (c == openCh) depth++
                else if (c == closeCh) {
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
}
