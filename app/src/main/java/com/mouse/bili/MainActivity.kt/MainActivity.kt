package com.mouse.bili

import android.annotation.SuppressLint
import android.app.Activity
import android.graphics.Bitmap
import android.graphics.Color
import android.os.Bundle
import android.view.Gravity
import android.view.inputmethod.EditorInfo
import android.webkit.*
import android.widget.*
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import java.io.ByteArrayInputStream
import java.net.URL
import java.net.URLEncoder

class MainActivity : Activity() {
    private val home = "https://www.bilinovel.com/"
    private val chromeUa = "Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 " +
        "(KHTML, like Gecko) Chrome/130.0.0.0 Mobile Safari/537.36"

    // 我无法从沙箱里读取该站实际加载的广告域名，名单是通用的。
    // 用电脑 Chrome 的 F12 → Network 找出漏网的域名，补进来即可。
    private val adHosts = listOf(
        "doubleclick.net", "googlesyndication.com", "googleadservices.com",
        "google-analytics.com", "googletagmanager.com", "adnxs.com", "taboola.com",
        "outbrain.com", "popads.net", "propellerads.com", "exoclick.com",
        "hm.baidu.com", "cpro.baidu.com", "pos.baidu.com", "union.baidu.com",
        "cnzz.com", "umeng.com", "tanx.com", "alimama.com", "gdt.qq.com",
        "e.qq.com", "csjplatform.com", "pangolin-sdk-toutiao.com"
    )

    // 页面脚本执行前注入：让"广告脚本是否加载"类检测得到正常结果，并隐藏广告容器。
    // 不隐藏 .adsbox / .ad-banner 这类诱饵元素，避免被"元素是否被隐藏"检测发现。
    private val guestJs = """
        (function(){
          try {
            window.adsbygoogle = window.adsbygoogle || {loaded:true, push:function(){}};
            window.canRunAds = true; window._hmt = window._hmt || []; window.dataLayer = window.dataLayer || [];
          } catch(e){}
          var css = 'ins.adsbygoogle,iframe[src*="doubleclick"],iframe[src*="googlesyndication"],' +
            '[id^="google_ads"],[id^="div-gpt-ad"],[class*="ad-wrap"],[class*="adv-"],[id*="BAIDU_"],' +
            '[id^="cpro"],.gg-box,.ad-container,.ad_box{display:none!important}';
          document.addEventListener('DOMContentLoaded', function(){
            var s = document.createElement('style'); s.textContent = css;
            document.documentElement.appendChild(s);
          });
        })();
    """.trimIndent()

    private lateinit var web: WebView
    private lateinit var urlBar: EditText
    private lateinit var progress: ProgressBar

    private fun isAd(url: String): Boolean = try {
        val h = URL(url).host
        adHosts.any { h == it || h.endsWith(".$it") }
    } catch (e: Exception) { false }

    private fun norm(v: String): String {
        val t = v.trim()
        return when {
            Regex("^https?://.*").matches(t) -> t
            Regex("^[\\w-]+(\\.[\\w-]+)+(/.*)?$").matches(t) -> "https://$t"
            else -> "https://www.google.com/search?q=" + URLEncoder.encode(t, "UTF-8")
        }
    }

    private fun dp(n: Int) = (n * resources.displayMetrics.density).toInt()

    private fun btn(label: String, onClick: () -> Unit) = Button(this).apply {
        text = label; setBackgroundColor(Color.TRANSPARENT); minWidth = 0; minimumWidth = dp(40)
        setPadding(0, 0, 0, 0); setOnClickListener { onClick() }
    }

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = Color.parseColor("#DEE1E6")

        urlBar = EditText(this).apply {
            setSingleLine(); setBackgroundColor(Color.parseColor("#F1F3F4"))
            setPadding(dp(14), 0, dp(14), 0); textSize = 14f
            imeOptions = EditorInfo.IME_ACTION_GO
            setOnEditorActionListener { _, _, _ -> web.loadUrl(norm(text.toString())); clearFocus(); true }
        }
        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            setBackgroundColor(Color.parseColor("#DEE1E6")); setPadding(dp(4), dp(4), dp(4), dp(4))
            addView(btn("←") { if (web.canGoBack()) web.goBack() })
            addView(btn("⟳") { web.reload() })
            addView(btn("⌂") { web.loadUrl(home) })
            addView(urlBar, LinearLayout.LayoutParams(0, dp(40), 1f))
        }
        progress = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply { max = 100 }

        web = WebView(this)
        CookieManager.getInstance().setAcceptThirdPartyCookies(web, false)
        web.settings.apply {
            javaScriptEnabled = true; domStorageEnabled = true
            userAgentString = chromeUa
            setSupportMultipleWindows(false)          // 不允许弹窗，target=_blank 在当前页打开
            javaScriptCanOpenWindowsAutomatically = false
            mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
        }

        if (WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)) {
            WebViewCompat.addDocumentStartJavaScript(web, guestJs, setOf("*"))
        }

        web.webViewClient = object : WebViewClient() {
            override fun shouldInterceptRequest(v: WebView, r: WebResourceRequest): WebResourceResponse? {
                val u = r.url.toString()
                if (!isAd(u)) return null
                // 返回"成功但内容为空"，比直接失败更不容易触发反屏蔽检测
                val mime = if (u.substringBefore('?').endsWith(".js")) "application/javascript" else "text/plain"
                return WebResourceResponse(mime, "utf-8", ByteArrayInputStream(ByteArray(0)))
            }
            override fun shouldOverrideUrlLoading(v: WebView, r: WebResourceRequest): Boolean = isAd(r.url.toString())
            override fun onPageStarted(v: WebView, url: String, f: Bitmap?) {
                urlBar.setText(url)
                if (!WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)) v.evaluateJavascript(guestJs, null)
            }
        }
        web.webChromeClient = object : WebChromeClient() {
            override fun onProgressChanged(v: WebView, p: Int) {
                progress.progress = p; progress.visibility = if (p in 1..99) android.view.View.VISIBLE else android.view.View.GONE
            }
        }

        setContentView(LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(bar); addView(progress, LinearLayout.LayoutParams(-1, dp(3)))
            addView(web, LinearLayout.LayoutParams(-1, 0, 1f))
        })
        web.loadUrl(home)
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() { if (web.canGoBack()) web.goBack() else super.onBackPressed() }
}
