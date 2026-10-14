package org.schabi.newpipe.fragments.list.shorts

import android.content.Context
import androidx.fragment.app.FragmentManager
import androidx.preference.PreferenceManager
import org.schabi.newpipe.R
import org.schabi.newpipe.extractor.ServiceList
import org.schabi.newpipe.extractor.stream.StreamInfoItem
import org.schabi.newpipe.util.NavigationHelper

/**
 * Decides whether a Short is opened in the vertical Shorts player or in the normal video page
 * (Settings → Content → "Open Shorts videos with").
 */
object ShortsHelper {
    private val SHORTS_URL = Regex("""(?:youtube\.com|youtu\.be)/shorts/([A-Za-z0-9_-]{11})""")
    private val WATCH_URL = Regex("""[?&]v=([A-Za-z0-9_-]{11})""")

    /** Video id of a youtube.com/shorts/<id> link, or null for every other url. */
    @JvmStatic
    fun shortIdOfUrl(url: String?): String? = url?.let { SHORTS_URL.find(it)?.groupValues?.get(1) }

    @JvmStatic
    fun usesShortsPlayer(context: Context): Boolean {
        val prefs = PreferenceManager.getDefaultSharedPreferences(context)
        val value = prefs.getString(
            context.getString(R.string.open_shorts_with_key),
            context.getString(R.string.open_shorts_with_shorts_value)
        )
        return value == context.getString(R.string.open_shorts_with_shorts_value)
    }

    /** True for a short: a /shorts/ link, or an item the extractor itself marked as short-form. */
    @JvmStatic
    fun isShort(item: StreamInfoItem): Boolean {
        if (item.serviceId != ServiceList.YouTube.serviceId) return false
        if (shortIdOfUrl(item.url) != null) return true
        // Not every extractor version has this flag: look it up so this compiles with all of them.
        return runCatching {
            StreamInfoItem::class.java.getMethod("isShortFormContent").invoke(item) as? Boolean
        }.getOrNull() == true
    }

    private fun videoIdOf(url: String?): String? =
        shortIdOfUrl(url) ?: url?.let { WATCH_URL.find(it)?.groupValues?.get(1) }

    /** For a plain url (links, history, ...). Returns true if the Shorts player took over. */
    @JvmStatic
    fun openIfShortUrl(context: Context, fm: FragmentManager, url: String?): Boolean {
        val id = shortIdOfUrl(url) ?: return false
        if (!usesShortsPlayer(context)) return false
        NavigationHelper.openShortsFragment(fm, id)
        return true
    }

    /** For a list item (search, channel, ...). Returns true if the Shorts player took over. */
    @JvmStatic
    fun openIfShortItem(context: Context, fm: FragmentManager, item: StreamInfoItem): Boolean {
        if (!isShort(item) || !usesShortsPlayer(context)) return false
        val id = videoIdOf(item.url) ?: return false
        NavigationHelper.openShortsFragment(fm, id)
        return true
    }
}
