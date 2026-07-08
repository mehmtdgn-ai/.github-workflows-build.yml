package com.kanalkutusu.app

import android.annotation.SuppressLint
import android.app.Activity
import android.content.res.AssetManager
import android.os.Bundle
import android.view.KeyEvent
import android.view.View
import android.webkit.WebChromeClient
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import fi.iki.elonen.NanoHTTPD
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.ByteArrayInputStream
import java.net.URLEncoder
import java.util.concurrent.TimeUnit

class MainActivity : Activity() {

    private lateinit var webView: WebView
    private var proxyServer: ProxyServer? = null
    private var port: Int = 8080

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        hideSystemUi()

        port = startProxyOnFreePort()

        webView = WebView(this)
        setContentView(webView)

        val settings: WebSettings = webView.settings
        settings.javaScriptEnabled = true
        settings.domStorageEnabled = true
        settings.mediaPlaybackRequiresUserGesture = false
        settings.mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
        settings.cacheMode = WebSettings.LOAD_DEFAULT
        settings.loadWithOverviewMode = true
        settings.useWideViewPort = true

        webView.webViewClient = WebViewClient()
        webView.webChromeClient = WebChromeClient()

        webView.loadUrl("http://127.0.0.1:$port/")
    }

    private fun hideSystemUi() {
        window.decorView.systemUiVisibility = (
            View.SYSTEM_UI_FLAG_LAYOUT_STABLE
            or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
            or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
            or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
            or View.SYSTEM_UI_FLAG_FULLSCREEN
            or View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
        )
    }

    private fun startProxyOnFreePort(): Int {
        var attempt = 8080
        var lastError: Exception? = null
        repeat(20) {
            try {
                val server = ProxyServer(attempt, assets)
                server.start()
                proxyServer = server
                return attempt
            } catch (e: Exception) {
                lastError = e
                attempt++
            }
        }
        throw RuntimeException("Yerel sunucu başlatılamadı", lastError)
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        if (keyCode == KeyEvent.KEYCODE_BACK) {
            webView.evaluateJavascript(
                "window.dispatchEvent(new KeyboardEvent('keydown',{key:'Escape'}));", null
            )
            return true
        }
        return super.onKeyDown(keyCode, event)
    }

    override fun onDestroy() {
        proxyServer?.stop()
        super.onDestroy()
    }
}

class ProxyServer(port: Int, private val assets: AssetManager) : NanoHTTPD("127.0.0.1", port) {

    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .build()

    override fun serve(session: IHTTPSession): Response {
        return try {
            when (session.uri) {
                "/", "/index.html" -> serveAsset("index.html", "text/html; charset=utf-8")
                "/proxy" -> handleProxy(session)
                else -> newFixedLengthResponse(Response.Status.NOT_FOUND, "text/plain", "not found")
            }
        } catch (e: Exception) {
            newFixedLengthResponse(
                Response.Status.INTERNAL_ERROR,
                "text/plain",
                "proxy hatası: ${e.message}"
            )
        }
    }

    private fun serveAsset(name: String, mime: String): Response {
        val stream = assets.open(name)
        val bytes = stream.readBytes()
        stream.close()
        val resp = newFixedLengthResponse(Response.Status.OK, mime, ByteArrayInputStream(bytes), bytes.size.toLong())
        resp.addHeader("Cache-Control", "no-store")
        return resp
    }

    private fun handleProxy(session: IHTTPSession): Response {
        val target = session.parameters["url"]?.firstOrNull()
            ?: return newFixedLengthResponse(Response.Status.BAD_REQUEST, "text/plain", "eksik url parametresi")

        val reqBuilder = Request.Builder().url(target)
        session.headers["range"]?.let { reqBuilder.addHeader("Range", it) }

        client.newCall(reqBuilder.build()).execute().use { upstream ->
            val bodyBytes = upstream.body?.bytes() ?: ByteArray(0)
            val contentType = upstream.header("Content-Type") ?: "application/octet-stream"
            val finalUrl = upstream.request.url.toString()
            val looksLikeManifest = finalUrl.substringBefore('?').endsWith(".m3u8") ||
                target.substringBefore('?').endsWith(".m3u8") ||
                contentType.contains("mpegurl", ignoreCase = true)

            val status = if (upstream.code == 206) Response.Status.PARTIAL_CONTENT else Response.Status.OK

            val response = if (looksLikeManifest) {
                val rewritten = rewriteManifest(String(bodyBytes, Charsets.UTF_8), finalUrl)
                newFixedLengthResponse(status, "application/vnd.apple.mpegurl", rewritten)
            } else {
                newFixedLengthResponse(status, contentType, ByteArrayInputStream(bodyBytes), bodyBytes.size.toLong())
            }

            response.addHeader("Access-Control-Allow-Origin", "*")
            upstream.header("Content-Range")?.let { response.addHeader("Content-Range", it) }
            upstream.header("Accept-Ranges")?.let { response.addHeader("Accept-Ranges", it) }
            return response
        }
    }

    private fun rewriteManifest(body: String, baseUrl: String): String {
        val base = baseUrl.toHttpUrlOrNull() ?: return body
        val attrUriPattern = Regex("URI=\"([^\"]+)\"")

        val out = StringBuilder()
        for (rawLine in body.lines()) {
            val line = rawLine.trimEnd('\r')
            when {
                line.isBlank() -> out.append(line).append('\n')
                line.startsWith("#") -> {
                    val rewritten = attrUriPattern.replace(line) { m ->
                        val abs = base.resolve(m.groupValues[1])?.toString() ?: m.groupValues[1]
                        "URI=\"" + toProxyUrl(abs) + "\""
                    }
                    out.append(rewritten).append('\n')
                }
                else -> {
                    val abs = base.resolve(line.trim())?.toString() ?: line.trim()
                    out.append(toProxyUrl(abs)).append('\n')
                }
            }
        }
        return out.toString()
    }

    private fun toProxyUrl(absoluteUrl: String): String =
        "/proxy?url=" + URLEncoder.encode(absoluteUrl, "UTF-8")
}
