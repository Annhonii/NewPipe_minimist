package org.schabi.newpipe.player.sponsorblock

import android.content.SharedPreferences
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Request
import org.json.JSONArray
import org.schabi.newpipe.DownloaderImpl
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

/** One skippable segment, times in milliseconds. */
data class SponsorSegment(val startMs: Long, val endMs: Long, val category: String)

/** Minimal SponsorBlock client (https://sponsor.ajay.app), YouTube only. */
object SponsorBlock {
    const val KEY_ENABLED = "sponsorblock_enabled_key"
    const val KEY_CATEGORIES = "sponsorblock_categories_key"
    const val KEY_SHOW_TOAST = "sponsorblock_show_toast_key"
    const val KEY_API_URL = "sponsorblock_api_url_key"
    const val DEFAULT_API_URL = "https://sponsor.ajay.app"

    val ALL_CATEGORIES = listOf(
        "sponsor", "selfpromo", "interaction", "intro", "outro", "preview", "music_offtopic"
    )
    val DEFAULT_CATEGORIES = setOf("sponsor", "selfpromo", "interaction", "intro", "outro")

    fun isEnabled(prefs: SharedPreferences) = prefs.getBoolean(KEY_ENABLED, false)

    fun categories(prefs: SharedPreferences): Set<String> =
        prefs.getStringSet(KEY_CATEGORIES, DEFAULT_CATEGORIES) ?: DEFAULT_CATEGORIES

    fun youtubeVideoId(url: String): String? {
        val u = url.toHttpUrlOrNull() ?: return null
        val host = u.host
        return when {
            host.endsWith("youtu.be") -> u.pathSegments.firstOrNull()?.takeIf { it.isNotEmpty() }
            host.endsWith("youtube.com") -> u.queryParameter("v")
                ?: u.pathSegments.takeIf { it.size >= 2 && (it[0] == "shorts" || it[0] == "live") }?.get(1)
            else -> null
        }
    }

    private fun String.toHttpUrlOrNull() = try { toHttpUrl() } catch (e: IllegalArgumentException) { null }

    /** Blocking network call: run off the main thread. Returns empty list on any failure. */
    fun fetch(videoId: String, categories: Set<String>, apiUrl: String): List<SponsorSegment> {
        if (categories.isEmpty()) return emptyList()
        return try {
            // Privacy-preserving lookup: only the first 4 hex chars of sha256(videoId) are sent.
            val prefix = MessageDigest.getInstance("SHA-256").digest(videoId.toByteArray())
                .joinToString("") { "%02x".format(it) }.substring(0, 4)
            val url = "${apiUrl.trimEnd('/')}/api/skipSegments/$prefix".toHttpUrl().newBuilder()
                .addQueryParameter("categories", JSONArray(categories.toList()).toString())
                .addQueryParameter("actionTypes", "[\"skip\"]")
                .build()
            val client = DownloaderImpl.getInstance().client.newBuilder()
                .callTimeout(8, TimeUnit.SECONDS).build()
            client.newCall(Request.Builder().url(url).build()).execute().use { resp ->
                if (!resp.isSuccessful) return emptyList()
                val videos = JSONArray(resp.body.string())
                val out = ArrayList<SponsorSegment>()
                for (i in 0 until videos.length()) {
                    val v = videos.getJSONObject(i)
                    if (v.getString("videoID") != videoId) continue
                    val segs = v.getJSONArray("segments")
                    for (j in 0 until segs.length()) {
                        val s = segs.getJSONObject(j)
                        if (s.optString("actionType", "skip") != "skip") continue
                        val range = s.getJSONArray("segment")
                        val start = (range.getDouble(0) * 1000).toLong()
                        val end = (range.getDouble(1) * 1000).toLong()
                        if (end > start) out.add(SponsorSegment(start, end, s.getString("category")))
                    }
                }
                out.sortedBy { it.startMs }
            }
        } catch (e: Exception) {
            emptyList()
        }
    }
}
