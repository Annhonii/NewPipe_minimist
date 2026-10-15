package org.schabi.newpipe.account

import android.content.Context
import androidx.preference.PreferenceManager
import org.schabi.newpipe.R
import java.util.concurrent.Executors

/**
 * Tells YouTube what was watched in the app (playback start + watch time), the same stats
 * pings its own player sends. This is what lets YouTube's recommendations (Home and Shorts)
 * adapt to the user, signed in (account history) or signed out (visitor profile).
 * Nothing is sent when the app's watch history is switched off.
 */
object YtWatchSignals {
    private const val CPN_CHARS = "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789-_"
    private const val MIN_PING_GAP_MS = 20_000L

    private val executor = Executors.newSingleThreadExecutor { r ->
        Thread(r, "yt-watch-signals").apply { isDaemon = true }
    }

    private class Watch(val videoId: String) {
        val cpn: String = (1..16).map { CPN_CHARS.random() }.joinToString("")
        @Volatile var tracking: YtInnerTube.Tracking? = null
        @Volatile var trackingLoaded = false
        @Volatile var positionMs = 0L
        @Volatile var durationMs = 0L
        @Volatile var lastPingAt = 0L
        @Volatile var lastPingedMs = 0L
    }

    @Volatile private var current: Watch? = null

    private fun enabled(context: Context): Boolean =
        PreferenceManager.getDefaultSharedPreferences(context)
            .getBoolean(context.getString(R.string.enable_watch_history_key), true)

    /** A video (or short) started playing. The one watched before is closed first. */
    @JvmStatic
    fun started(context: Context, videoId: String?) {
        if (videoId.isNullOrBlank()) return
        val app = context.applicationContext
        if (!enabled(app)) return
        val previous = current
        if (previous?.videoId == videoId) return
        val watch = Watch(videoId)
        current = watch
        executor.execute {
            runCatching {
                if (previous != null) sendWatchtime(app, previous, final = true)
                val session = YtAccountStore.load(app)
                watch.tracking = YtInnerTube.trackingFor(session, videoId)
                watch.trackingLoaded = true
                watch.tracking?.playbackUrl?.let {
                    YtInnerTube.ping(session, it, baseParams(watch) + mapOf("cmt" to "0"))
                }
            }
        }
    }

    /** Playback position update; a watch time ping goes out at most every 20 seconds. */
    @JvmStatic
    fun progress(context: Context, videoId: String?, positionMs: Long, durationMs: Long) {
        val watch = current ?: return
        if (videoId != watch.videoId || positionMs < 0) return
        watch.positionMs = positionMs
        if (durationMs > 0) watch.durationMs = durationMs
        val now = System.currentTimeMillis()
        if (!watch.trackingLoaded || now - watch.lastPingAt < MIN_PING_GAP_MS) return
        watch.lastPingAt = now
        val app = context.applicationContext
        executor.execute { runCatching { sendWatchtime(app, watch, final = false) } }
    }

    private fun baseParams(w: Watch): Map<String, String> = mapOf(
        "ver" to "2",
        "c" to "WEB",
        "cpn" to w.cpn,
        "el" to "detailpage"
    )

    private fun sendWatchtime(app: Context, w: Watch, final: Boolean) {
        val url = w.tracking?.watchtimeUrl ?: return
        val end = w.positionMs / 1000.0
        if (end <= 0.0) return
        val start = w.lastPingedMs / 1000.0
        val params = baseParams(w) + mapOf(
            "st" to "%.3f".format(java.util.Locale.US, start),
            "et" to "%.3f".format(java.util.Locale.US, end),
            "cmt" to "%.3f".format(java.util.Locale.US, end),
            "len" to "%.3f".format(java.util.Locale.US, w.durationMs / 1000.0),
            "state" to if (final) "paused" else "playing"
        ) + (if (final) mapOf("final" to "1") else emptyMap())
        YtInnerTube.ping(YtAccountStore.load(app), url, params)
        w.lastPingedMs = w.positionMs
    }
}
