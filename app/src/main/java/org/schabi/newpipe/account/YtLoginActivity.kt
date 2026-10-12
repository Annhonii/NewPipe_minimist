package org.schabi.newpipe.account

import android.annotation.SuppressLint
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.webkit.CookieManager
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import org.json.JSONObject
import org.json.JSONTokener
import org.schabi.newpipe.R
import org.schabi.newpipe.util.ThemeHelper

/** "Manual" login: the user signs in to Google inside a WebView, we then grab the session. */
class YtLoginActivity : AppCompatActivity() {
    private lateinit var webView: WebView
    private lateinit var progress: ProgressBar
    private var handled = false

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        setTheme(ThemeHelper.getSettingsThemeStyle(this))
        org.schabi.newpipe.util.FontHelper.applyFontOverlay(this)
        org.schabi.newpipe.util.AppearanceHelper.applyOverlays(this)
        super.onCreate(savedInstanceState)
        title = getString(R.string.yt_login_title)

        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val hint = TextView(this).apply {
            setText(R.string.yt_login_hint)
            val pad = (12 * resources.displayMetrics.density).toInt()
            setPadding(pad, pad, pad, pad)
        }
        progress = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            isIndeterminate = true
        }
        webView = WebView(this)
        root.addView(hint)
        root.addView(progress, LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
        root.addView(webView, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))
        setContentView(root)

        webView.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            // Google refuses sign-in inside embedded WebViews; drop the "wv" markers.
            userAgentString = userAgentString
                .replace("; wv", "")
                .replace(Regex("Version/\\d+\\.\\d+ "), "")
        }
        val cm = CookieManager.getInstance()
        cm.setAcceptCookie(true)
        cm.setAcceptThirdPartyCookies(webView, true)

        webView.webViewClient = object : WebViewClient() {
            override fun onPageStarted(view: WebView, url: String?, favicon: android.graphics.Bitmap?) {
                progress.visibility = View.VISIBLE
            }

            override fun onPageFinished(view: WebView, url: String?) {
                progress.visibility = View.GONE
                val host = Uri.parse(url ?: "").host ?: return
                if (host.endsWith("youtube.com")) tryCapture(view)
            }
        }

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (webView.canGoBack()) webView.goBack() else finish()
            }
        })

        // start from a clean cookie jar so the account chooser always shows up
        cm.removeAllCookies { webView.loadUrl(LOGIN_URL) }
    }

    private fun tryCapture(view: WebView) {
        if (handled) return
        val cookie = CookieManager.getInstance().getCookie("https://www.youtube.com").orEmpty()
        if (YtAccountStore.sapisidOf(cookie) == null) return
        handled = true

        val js = "(function(){try{var c=window.ytcfg;return JSON.stringify({" +
            "v:c.get('VISITOR_DATA')||'',d:c.get('DATASYNC_ID')||''," +
            "s:String(c.get('SESSION_INDEX')||0)});}catch(e){" +
            "return JSON.stringify({v:'',d:'',s:'0'});}})()"
        view.evaluateJavascript(js) { raw ->
            var visitor = ""
            var dataSync = ""
            var authUser = "0"
            runCatching {
                val o = JSONObject(JSONTokener(raw).nextValue() as String)
                visitor = o.optString("v")
                dataSync = o.optString("d")
                authUser = o.optString("s", "0")
            }
            val session = YtAccountStore.Session(cookie, visitor, dataSync, authUser)
            YtAccountActions.loginAndSync(
                this,
                session,
                onLoggedIn = {
                    setResult(RESULT_OK)
                    finish()
                },
                onLoginFailed = {
                    handled = false
                    Toast.makeText(this, it.message ?: "Login failed", Toast.LENGTH_LONG).show()
                }
            )
        }
    }

    override fun onDestroy() {
        runCatching {
            webView.stopLoading()
            webView.destroy()
        }
        super.onDestroy()
    }

    companion object {
        private const val LOGIN_URL = "https://accounts.google.com/ServiceLogin?service=youtube&hl=en" +
            "&continue=https%3A%2F%2Fwww.youtube.com%2Fsignin%3Faction_handle_signin%3Dtrue" +
            "%26next%3D%252F"
    }
}
