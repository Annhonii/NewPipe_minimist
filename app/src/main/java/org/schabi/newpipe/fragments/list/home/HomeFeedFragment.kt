package org.schabi.newpipe.fragments.list.home

import android.content.Context
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import io.reactivex.rxjava3.core.Single
import org.schabi.newpipe.R
import org.schabi.newpipe.account.YtAccountStore
import org.schabi.newpipe.account.YtFeedback
import org.schabi.newpipe.account.YtInnerTube
import org.schabi.newpipe.error.UserAction
import org.schabi.newpipe.extractor.Image
import org.schabi.newpipe.extractor.ListExtractor
import org.schabi.newpipe.extractor.ListInfo
import org.schabi.newpipe.extractor.Page
import org.schabi.newpipe.extractor.ServiceList
import org.schabi.newpipe.extractor.stream.StreamInfoItem
import org.schabi.newpipe.extractor.stream.StreamType
import org.schabi.newpipe.fragments.list.BaseListInfoFragment
import org.schabi.newpipe.info_list.dialog.InfoItemDialog
import org.schabi.newpipe.info_list.dialog.StreamDialogDefaultEntry
import android.widget.Toast
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout
import org.schabi.newpipe.util.WatchedVideos

/** Info holder so the standard list-fragment machinery can show the home feed. */
class HomeFeedInfo(name: String) : ListInfo<StreamInfoItem>(
    ServiceList.YouTube.serviceId,
    "home",
    HOME_URL,
    HOME_URL,
    name,
    emptyList(),
    ""
) {
    companion object {
        const val HOME_URL = "https://www.youtube.com/"
    }
}

/**
 * Home tab: recommended videos from YouTube's "what to watch" feed. Personalised when a Google
 * account is signed in (Settings → Google account), a generic popular-videos feed otherwise (no login needed).
 */
class HomeFeedFragment : BaseListInfoFragment<StreamInfoItem, HomeFeedInfo>(UserAction.REQUESTED_KIOSK) {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setInitialData(ServiceList.YouTube.serviceId, HomeFeedInfo.HOME_URL, getString(R.string.yt_home_title))
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? = inflater.inflate(R.layout.fragment_home_feed, container, false)

    private var refreshLayout: SwipeRefreshLayout? = null

    override fun initViews(rootView: View, savedInstanceState: Bundle?) {
        super.initViews(rootView, savedInstanceState)
        // pull down = new recommendations (videos that were watched or shown before are skipped)
        refreshLayout = rootView.findViewById<SwipeRefreshLayout>(R.id.home_feed_refresh)?.also {
            it.setOnRefreshListener { reloadContent() }
        }
    }

    /** Same as pulling down: back to the top and new recommendations. */
    fun refreshFromNavBar() {
        itemsList?.scrollToPosition(0)
        refreshLayout?.isRefreshing = true
        reloadContent()
    }

    /** While the user pulled to refresh, the pull spinner is the loading indicator. */
    override fun showLoading() {
        if (refreshLayout?.isRefreshing == true) {
            hideErrorPanel()
            return
        }
        super.showLoading()
    }

    override fun hideLoading() {
        super.hideLoading()
        refreshLayout?.isRefreshing = false
    }

    override fun handleError() {
        super.handleError()
        refreshLayout?.isRefreshing = false
    }

    override fun loadResult(forceLoad: Boolean): Single<HomeFeedInfo> {
        val app = requireContext().applicationContext
        val title = getString(R.string.yt_home_title)
        val key = YtAccountStore.load(app)?.hashCode() ?: 0

        // Coming back to the Home tab (the page may have been destroyed while another tab was
        // open) must not buffer again: reuse the last feed unless the user pulled to refresh.
        val cached = cache
        if (!forceLoad && cached != null && cacheKey == key &&
            System.currentTimeMillis() - cacheTime < CACHE_MAX_AGE_MS
        ) {
            return Single.just(cached)
        }

        return Single.fromCallable {
            // a refresh must not bring back what was on screen before
            val (videos, next) = freshVideos(app, null, avoidShown = forceLoad)
            HomeFeedInfo(title).apply {
                relatedItems = videos.map { toItem(it) }
                nextPage = next?.let { Page(it) }
            }
        }.doOnSuccess {
            cache = it
            cacheKey = key
            cacheTime = System.currentTimeMillis()
        }
    }

    override fun loadMoreItemsLogic(): Single<ListExtractor.InfoItemsPage<StreamInfoItem>> {
        val app: Context = requireContext().applicationContext
        val token = currentNextPage?.url
        return Single.fromCallable {
            val (videos, next) = freshVideos(app, token, avoidShown = true)
            ListExtractor.InfoItemsPage(
                videos.map { toItem(it) },
                next?.let { Page(it) },
                emptyList<Throwable>()
            )
        }
    }

    /**
     * One page of recommendations without videos that are already in the watch history and
     * (when [avoidShown]) without videos shown earlier. Reads further pages when too little is
     * left after filtering. Blocking: call from a background thread.
     */
    private fun freshVideos(
        app: Context,
        token: String?,
        avoidShown: Boolean
    ): Pair<List<YtInnerTube.Video>, String?> {
        val session = YtAccountStore.load(app)
        val watched = WatchedVideos.ids(app)
        val shown = if (avoidShown) seenSnapshot() else emptySet()

        fun collect(exclude: Set<String>): Pair<List<YtInnerTube.Video>, String?> {
            val taken = HashSet<String>()
            var page = YtInnerTube.feed(session, token)
            val out = ArrayList<YtInnerTube.Video>()
            fun take(videos: List<YtInnerTube.Video>) {
                videos.forEach { if (it.id !in exclude && taken.add(it.id)) out.add(it) }
            }
            take(page.items)
            var next = page.continuation
            var guard = 0
            while (out.size < MIN_ITEMS && next != null && guard++ < MAX_FILTER_PAGES) {
                page = YtInnerTube.feed(session, next)
                take(page.items)
                next = page.continuation
            }
            return out to next
        }

        var result = collect(watched + shown)
        // everything was shown before: repeat those rather than show an empty home page
        if (result.first.isEmpty() && shown.isNotEmpty()) result = collect(watched)
        markSeen(result.first.map { it.id })
        return result
    }

    companion object {
        private const val CACHE_MAX_AGE_MS = 30 * 60 * 1000L
        private const val MIN_ITEMS = 8
        private const val MAX_FILTER_PAGES = 4
        private const val MAX_SEEN = 600

        /** Ids of the videos the Home feed already showed (oldest dropped first). */
        private val seenIds = LinkedHashSet<String>()

        private fun seenSnapshot(): Set<String> = synchronized(seenIds) { HashSet(seenIds) }

        private fun markSeen(ids: Collection<String>) = synchronized(seenIds) {
            seenIds.addAll(ids)
            while (seenIds.size > MAX_SEEN) {
                val oldest = seenIds.iterator()
                oldest.next()
                oldest.remove()
            }
        }

        @Volatile private var cache: HomeFeedInfo? = null
        @Volatile private var cacheKey = 0
        @Volatile private var cacheTime = 0L
    }

    private val feedbackDisposables = io.reactivex.rxjava3.disposables.CompositeDisposable()

    override fun onDestroyView() {
        refreshLayout = null
        feedbackDisposables.clear()
        super.onDestroyView()
    }

    private fun videoIdOf(item: StreamInfoItem): String =
        item.url.substringAfter("v=", "").take(11)

    /** Same menu as everywhere, plus YouTube's "Not interested" / "Don't recommend channel". */
    override fun showInfoItemDialog(item: StreamInfoItem) {
        val videoId = videoIdOf(item)
        try {
            val builder = InfoItemDialog.Builder(activity, context, this, item)
            if (YtFeedback.canNotInterested(videoId)) {
                builder.addEntry(StreamDialogDefaultEntry.NOT_INTERESTED)
                builder.setAction(StreamDialogDefaultEntry.NOT_INTERESTED) { _, _ ->
                    sendFeedback(YtFeedback.notInterested(requireContext(), videoId)) {
                        it.url == item.url
                    }
                }
            }
            if (YtFeedback.canDontRecommendChannel(videoId) && !item.uploaderUrl.isNullOrEmpty()) {
                builder.addEntry(StreamDialogDefaultEntry.DONT_RECOMMEND_CHANNEL)
                builder.setAction(StreamDialogDefaultEntry.DONT_RECOMMEND_CHANNEL) { _, _ ->
                    sendFeedback(YtFeedback.dontRecommendChannel(requireContext(), videoId)) {
                        it.uploaderUrl == item.uploaderUrl
                    }
                }
            }
            builder.create().show()
        } catch (e: IllegalArgumentException) {
            InfoItemDialog.Builder.reportErrorDuringInitialization(e, item)
        }
    }

    /** Sends the feedback and, once YouTube accepted it, drops the matching cards from the list. */
    private fun sendFeedback(
        call: io.reactivex.rxjava3.core.Completable,
        matches: (StreamInfoItem) -> Boolean
    ) {
        val app = requireContext().applicationContext
        feedbackDisposables.add(
            call.subscribe(
                {
                    Toast.makeText(app, R.string.yt_feedback_done, Toast.LENGTH_SHORT).show()
                    val list = infoListAdapter.itemsList
                    list.removeAll { it is StreamInfoItem && matches(it) }
                    infoListAdapter.notifyDataSetChanged()
                    cache?.let { c ->
                        c.relatedItems = c.relatedItems.filterNot { matches(it) }
                    }
                },
                { Toast.makeText(app, R.string.yt_feedback_failed, Toast.LENGTH_SHORT).show() }
            )
        )
    }

    override fun showEmptyState() {
        super.showEmptyState()
        setEmptyStateMessage(R.string.yt_home_empty)
    }

    private fun toItem(v: YtInnerTube.Video): StreamInfoItem {
        val item = StreamInfoItem(
            ServiceList.YouTube.serviceId,
            "https://www.youtube.com/watch?v=${v.id}",
            v.title,
            StreamType.VIDEO_STREAM
        )
        item.uploaderName = v.channelName
        v.channelId?.let { item.uploaderUrl = "https://www.youtube.com/channel/$it" }
        item.duration = v.durationSec
        item.viewCount = v.viewCount
        item.textualUploadDate = v.published
        v.thumbnail?.let { item.thumbnails = listOf(img(it)) }
        v.avatar?.let { item.uploaderAvatars = listOf(img(it)) }
        return item
    }

    private fun img(url: String) =
        Image(url, Image.HEIGHT_UNKNOWN, Image.WIDTH_UNKNOWN, Image.ResolutionLevel.UNKNOWN)
}
