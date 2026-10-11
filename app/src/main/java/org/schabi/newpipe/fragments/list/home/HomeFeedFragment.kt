package org.schabi.newpipe.fragments.list.home

import android.content.Context
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import io.reactivex.rxjava3.core.Single
import org.schabi.newpipe.R
import org.schabi.newpipe.account.YtAccountStore
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
 * account is signed in (Settings → Google account), generic otherwise.
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
        return Single.fromCallable {
            val page = YtInnerTube.homeFeed(YtAccountStore.load(app))
            HomeFeedInfo(title).apply {
                relatedItems = page.items.map { toItem(it) }
                nextPage = page.continuation?.let { Page(it) }
            }
        }
    }

    override fun loadMoreItemsLogic(): Single<ListExtractor.InfoItemsPage<StreamInfoItem>> {
        val app: Context = requireContext().applicationContext
        val token = currentNextPage?.url
        return Single.fromCallable {
            val page = YtInnerTube.homeFeed(YtAccountStore.load(app), token)
            ListExtractor.InfoItemsPage(
                page.items.map { toItem(it) },
                page.continuation?.let { Page(it) },
                emptyList<Throwable>()
            )
        }
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
