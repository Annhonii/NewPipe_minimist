package org.schabi.newpipe.player.sponsorblock

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.widget.Toast
import androidx.preference.PreferenceManager
import com.google.android.exoplayer2.ExoPlayer
import org.schabi.newpipe.R
import java.util.concurrent.Executors

/**
 * Loads SponsorBlock segments for the current YouTube stream and seeks past them during playback.
 * All public methods must be called on the main thread.
 */
class SponsorBlockSkipper(
    private val context: Context,
    private val playerProvider: () -> ExoPlayer?
) {
    private val handler = Handler(Looper.getMainLooper())
    private val prefs = PreferenceManager.getDefaultSharedPreferences(context)
    private var segments: List<SponsorSegment> = emptyList()
    private var currentVideoId: String? = null
    private var lastSkippedEnd = -1L
    private var running = false

    private val tick = object : Runnable {
        override fun run() {
            if (!running) return
            checkPosition()
            handler.postDelayed(this, CHECK_INTERVAL_MS)
        }
    }

    /** Called when a new stream starts (or the same one is re-announced). */
    fun onStreamChanged(url: String, isYoutube: Boolean) {
        val id = if (isYoutube && SponsorBlock.isEnabled(prefs)) SponsorBlock.youtubeVideoId(url) else null
        if (id == currentVideoId) return
        currentVideoId = id
        segments = emptyList()
        lastSkippedEnd = -1L
        if (id == null) {
            stop()
            return
        }
        val categories = SponsorBlock.categories(prefs)
        val api = prefs.getString(SponsorBlock.KEY_API_URL, SponsorBlock.DEFAULT_API_URL)
            ?.takeIf { it.isNotBlank() } ?: SponsorBlock.DEFAULT_API_URL
        executor.execute {
            val result = SponsorBlock.fetch(id, categories, api)
            handler.post {
                if (currentVideoId == id) {
                    segments = result
                    if (result.isNotEmpty()) start()
                }
            }
        }
    }

    fun start() {
        if (running || segments.isEmpty()) return
        running = true
        handler.post(tick)
    }

    fun stop() {
        running = false
        handler.removeCallbacks(tick)
    }

    fun release() {
        stop()
        segments = emptyList()
        currentVideoId = null
    }

    private fun checkPosition() {
        val player = playerProvider() ?: return
        if (!player.isPlaying) return
        val pos = player.currentPosition
        val seg = segments.firstOrNull { pos >= it.startMs && pos < it.endMs - 300 } ?: return
        if (seg.endMs == lastSkippedEnd) return
        lastSkippedEnd = seg.endMs
        val duration = player.duration
        // Segment reaching the end of the video: jump to the very end so the queue advances.
        val target = if (duration > 0 && seg.endMs >= duration - 1000) duration else seg.endMs
        player.seekTo(target)
        if (prefs.getBoolean(SponsorBlock.KEY_SHOW_TOAST, true)) {
            Toast.makeText(
                context, context.getString(R.string.sponsorblock_skipped, seg.category),
                Toast.LENGTH_SHORT
            ).show()
        }
    }

    companion object {
        private const val CHECK_INTERVAL_MS = 250L
        private val executor = Executors.newSingleThreadExecutor()
    }
}
