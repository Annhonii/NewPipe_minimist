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
    private const val K_AVATAR = "avatar"

    data class Session(
        val cookie: String,
        val visitorData: String = "",
        val dataSyncId: String = "",
        val authUser: String = "0",
        val name: String = "",
        val email: String = "",
        val avatar: String = ""
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
            email = p.getString(K_EMAIL, "").orEmpty(),
            avatar = p.getString(K_AVATAR, "").orEmpty()
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
            .putString(K_AVATAR, s.avatar)
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
     * Accepts either a plain cookie string or the token text produced by "copy token"
     * (lines like `***COOKIE*** =...`). Returns null when no usable cookie is found.
     */
    fun parseTokenText(text: String): Session? {
        var cookie = ""
        var visitor = ""
        var dataSync = ""
        var authUser = "0"
        var name = ""
        var email = ""
        var templated = false
        fun value(line: String) = line.substringAfter("=").trim()
        text.lines().forEach { raw ->
            val line = raw.trim()
            when {
                line.startsWith("***COOKIE***") || line.startsWith("***INNERTUBE COOKIE***") -> {
                    cookie = value(line); templated = true
                }
                line.startsWith("***VISITOR***") || line.startsWith("***VISITOR DATA***") -> visitor = value(line)
                line.startsWith("***SYNC ID***") || line.startsWith("***DATASYNC ID***") -> dataSync = value(line)
                line.startsWith("***USER INDEX***") || line.startsWith("***AUTH USER***") -> authUser = value(line)
                line.startsWith("***NAME***") || line.startsWith("***ACCOUNT NAME***") -> name = value(line)
                line.startsWith("***EMAIL***") || line.startsWith("***ACCOUNT EMAIL***") -> email = value(line)
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
