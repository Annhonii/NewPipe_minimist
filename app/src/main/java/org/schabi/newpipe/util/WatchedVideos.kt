package org.schabi.newpipe.util

import android.content.Context
import org.schabi.newpipe.NewPipeDatabase
import org.schabi.newpipe.extractor.ServiceList

/**
 * Which YouTube videos / shorts were already watched (they are in the app's watch history).
 * Used so that refreshing Home or Shorts recommends new videos instead of old ones.
 */
object WatchedVideos {
    private val ID_IN_URL = Regex("""(?:[?&]v=|/shorts/|youtu\.be/|/embed/)([A-Za-z0-9_-]{11})""")

    /** The 11 character video id of a YouTube url, or null. */
    @JvmStatic
    fun idOfUrl(url: String?): String? = url?.let { ID_IN_URL.find(it)?.groupValues?.get(1) }

    /**
     * Ids of all watched YouTube videos. Reads the database, so call it from a background
     * thread. Returns an empty set when the history can't be read.
     */
    @JvmStatic
    fun ids(context: Context): Set<String> = runCatching {
        NewPipeDatabase.getInstance(context.applicationContext)
            .streamHistoryDAO()
            .getWatchedUrls(ServiceList.YouTube.serviceId)
            .mapNotNullTo(HashSet()) { idOfUrl(it) }
    }.getOrDefault(emptySet())
}
