package org.schabi.newpipe.account

import android.content.Context
import android.webkit.CookieManager

/**
 * Stores the Google / YouTube session (cookie + a few ids) used for authenticated
 * InnerTube requests. Works for both the WebView login and the pasted-token login.
 */
object YtAccountStore {
    private const val PREFS = "yt_account_prefs"
    private const val K_COOKIE = "cookie"
    private const val K_VISITOR = "visitor_data"
    private const val K_DATASYNC = "datasync_id"
    private const val K_AUTH_USER = "auth_user"
    private const val K_NAME = "name"
    private const val K_EMAIL = "email"

    data class Session(
        val cookie: String,
        val visitorData: String = "",
        val dataSyncId: String = "",
        val authUser: String = "0",
        val name: String = "",
        val email: String = ""
    ) {
        val sapisid: String? get() = sapisidOf(cookie)
    }

    fun parseCookies(cookie: String): Map<String, String> =
        cookie.split(";")
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .mapNotNull {
                val i = it.indexOf('=')
                if (i <= 0) null else it.substring(0, i) to it.substring(i + 1)
            }
            .toMap()

    fun sapisidOf(cookie: String): String? {
        val map = parseCookies(cookie)
        return map["SAPISID"] ?: map["__Secure-3PAPISID"]
    }

    fun load(context: Context): Session? {
        val p = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val cookie = p.getString(K_COOKIE, null) ?: return null
        if (sapisidOf(cookie) == null) return null
        return Session(
            cookie = cookie,
            visitorData = p.getString(K_VISITOR, "").orEmpty(),
            dataSyncId = p.getString(K_DATASYNC, "").orEmpty(),
            authUser = p.getString(K_AUTH_USER, "0").orEmpty().ifBlank { "0" },
            name = p.getString(K_NAME, "").orEmpty(),
            email = p.getString(K_EMAIL, "").orEmpty()
        )
    }

    fun isLoggedIn(context: Context) = load(context) != null

    fun save(context: Context, s: Session) {
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString(K_COOKIE, s.cookie)
            .putString(K_VISITOR, s.visitorData)
            .putString(K_DATASYNC, s.dataSyncId.substringBefore("||"))
            .putString(K_AUTH_USER, s.authUser.filter(Char::isDigit).ifBlank { "0" })
            .putString(K_NAME, s.name)
            .putString(K_EMAIL, s.email)
            .apply()
    }

    fun clear(context: Context) {
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().clear().apply()
        runCatching {
            CookieManager.getInstance().removeAllCookies(null)
            CookieManager.getInstance().flush()
        }
    }

    /**
     * Accepts either a plain cookie string ("SID=..; SAPISID=..; ...") or the
     * Metrolist token template (lines like `***INNERTUBE COOKIE*** =...`).
     * Returns null when no usable cookie (with SAPISID) is found.
     */
    fun parseTokenText(text: String): Session? {
        var cookie = ""
        var visitor = ""
        var dataSync = ""
        var authUser = "0"
        var name = ""
        var email = ""
        var templated = false
        text.lines().forEach { raw ->
            val line = raw.trim()
            when {
                line.startsWith("***INNERTUBE COOKIE***") -> { cookie = line.substringAfter("=").trim(); templated = true }
                line.startsWith("***VISITOR DATA***") -> visitor = line.substringAfter("=").trim()
                line.startsWith("***DATASYNC ID***") -> dataSync = line.substringAfter("=").trim()
                line.startsWith("***AUTH USER***") -> authUser = line.substringAfter("=").trim()
                line.startsWith("***ACCOUNT NAME***") -> name = line.substringAfter("=").trim()
                line.startsWith("***ACCOUNT EMAIL***") -> email = line.substringAfter("=").trim()
            }
        }
        if (!templated) {
            cookie = text.replace("\r", " ").replace("\n", " ").trim()
                .removePrefix("Cookie:").removePrefix("cookie:").trim()
        }
        cookie = cookie.replace("\r", "").replace("\n", "").trim()
        if (sapisidOf(cookie) == null) return null
        return Session(cookie, visitor, dataSync, authUser, name, email)
    }
}
