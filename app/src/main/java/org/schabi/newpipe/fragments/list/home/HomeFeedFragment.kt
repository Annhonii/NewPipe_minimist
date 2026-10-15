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
    ): View? = inflater.inflate(R.layout.fragment_kiosk, container, false)

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
            val page = YtInnerTube.feed(YtAccountStore.load(app))
            HomeFeedInfo(title).apply {
                relatedItems = page.items.map { toItem(it) }
                nextPage = page.continuation?.let { Page(it) }
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
            val page = YtInnerTube.feed(YtAccountStore.load(app), token)
            ListExtractor.InfoItemsPage(
                page.items.map { toItem(it) },
                page.continuation?.let { Page(it) },
                emptyList<Throwable>()
            )
        }
    }

    companion object {
        private const val CACHE_MAX_AGE_MS = 30 * 60 * 1000L

        @Volatile private var cache: HomeFeedInfo? = null
        @Volatile private var cacheKey = 0
        @Volatile private var cacheTime = 0L
    }

    private val feedbackDisposables = io.reactivex.rxjava3.disposables.CompositeDisposable()

    override fun onDestroyView() {
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
