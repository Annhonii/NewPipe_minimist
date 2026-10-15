package org.schabi.newpipe.fragments.list.shorts

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.annotation.SuppressLint
import android.net.Uri
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.method.LinkMovementMethod
import android.text.style.ForegroundColorSpan
import android.text.style.StyleSpan
import android.graphics.Typeface
import android.view.HapticFeedbackConstants
import android.widget.FrameLayout
import android.widget.ImageView
import androidx.appcompat.app.AlertDialog
import org.schabi.newpipe.database.subscription.NotificationMode
import kotlin.random.Random
import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Color
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.GestureDetector
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import androidx.recyclerview.widget.RecyclerView
import androidx.viewpager2.widget.ViewPager2
import com.google.android.exoplayer2.C
import com.google.android.exoplayer2.ExoPlayer
import com.google.android.exoplayer2.PlaybackException
import com.google.android.exoplayer2.Player
import com.google.android.exoplayer2.audio.AudioAttributes
import com.google.android.exoplayer2.ui.AspectRatioFrameLayout
import com.google.android.exoplayer2.upstream.DefaultBandwidthMeter
import com.google.android.exoplayer2.video.VideoSize
import io.reactivex.rxjava3.android.schedulers.AndroidSchedulers
import io.reactivex.rxjava3.core.Completable
import io.reactivex.rxjava3.core.Single
import io.reactivex.rxjava3.disposables.CompositeDisposable
import io.reactivex.rxjava3.disposables.Disposable
import io.reactivex.rxjava3.schedulers.Schedulers
import org.schabi.newpipe.BaseFragment
import org.schabi.newpipe.R
import org.schabi.newpipe.account.YtAccountStore
import org.schabi.newpipe.account.YtEngagement
import org.schabi.newpipe.account.YtInnerTube
import org.schabi.newpipe.database.subscription.SubscriptionEntity
import org.schabi.newpipe.databinding.FragmentShortsBinding
import org.schabi.newpipe.databinding.ItemShortBinding
import org.schabi.newpipe.extractor.ServiceList
import org.schabi.newpipe.extractor.stream.StreamInfo
import org.schabi.newpipe.fragments.detail.VideoInfoBottomSheet
import org.schabi.newpipe.local.subscription.SubscriptionManager
import org.schabi.newpipe.player.helper.PlayerDataSource
import org.schabi.newpipe.player.resolver.VideoPlaybackResolver
import org.schabi.newpipe.util.ListHelper
import org.schabi.newpipe.util.Localization
import org.schabi.newpipe.util.NavigationHelper
import org.schabi.newpipe.util.image.CoilHelper
import org.schabi.newpipe.util.image.ImageStrategy

/**
 * Shorts player: a vertical, full-screen pager of short videos that looks like the YouTube Shorts
 * player (like / comments / share on the right, channel + Subscribe + title at the bottom, red
 * progress line). Used both as the "Shorts" tab (recommendations from YouTube's own Shorts feed,
 * personalised after Google login) and, with a seed video, as the player that opens when a Short is
 * tapped and Settings → Content → "Open Shorts videos with" is "Shorts player".
 *
 * One ExoPlayer is shared by all pages and moved to the page that is on screen.
 */
class ShortsFragment : BaseFragment() {

    private class ShortHolder(val b: ItemShortBinding) : RecyclerView.ViewHolder(b.root) {
        var pagePosition = -1
        var videoId: String? = null
    }

    private var binding: FragmentShortsBinding? = null
    private val disposables = CompositeDisposable()
    private val handler = Handler(Looper.getMainLooper())

    // feed
    private val entries = ArrayList<YtInnerTube.ShortItem>()
    private val knownIds = HashSet<String>()
    private var nextToken: String? = null
    private var exhausted = false
    private var loading = false
    private var seedId: String? = null

    // pages
    private val holders = HashMap<Int, ShortHolder>()
    private var adapter: ShortsAdapter? = null
    private var currentPosition = -1
    private var userPaused = false
    private var dragging = false

    // playback
    private var player: ExoPlayer? = null
    private var resolver: VideoPlaybackResolver? = null

    // per-video data
    private val infoCache = HashMap<String, StreamInfo>()
    private val infoSingles = HashMap<String, Single<StreamInfo>>()
    private val likeStates = HashMap<String, String>()
    private val likeCounts = HashMap<String, Long>()
    private val commentTexts = HashMap<String, String>()
    private var subscribeDisposable: Disposable? = null
    private var subscribed = false
    private var notificationsOn = false
    private val dislikeCounts = HashMap<String, String>()
    private val expandedTitles = HashSet<String>()
    private var playbackSpeed = 1f
    private var captionsOn = false
    private var chosenQuality: String? = null

    private val standalone: Boolean get() = seedId != null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        seedId = arguments?.getString(ARG_SEED)
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View = inflater.inflate(R.layout.fragment_shorts, container, false)

    override fun initViews(rootView: View, savedInstanceState: Bundle?) {
        super.initViews(rootView, savedInstanceState)
        val b = FragmentShortsBinding.bind(rootView)
        binding = b

        val pagerAdapter = ShortsAdapter()
        adapter = pagerAdapter
        b.shortsPager.orientation = ViewPager2.ORIENTATION_VERTICAL
        b.shortsPager.offscreenPageLimit = 1
        b.shortsPager.adapter = pagerAdapter
        b.shortsPager.registerOnPageChangeCallback(object : ViewPager2.OnPageChangeCallback() {
            override fun onPageSelected(position: Int) {
                activate(position)
            }
        })
        b.shortsRetry.setOnClickListener { loadFeed() }

        if (entries.isEmpty()) {
            loadFeed()
        } else {
            pagerAdapter.notifyDataSetChanged()
            b.shortsPager.setCurrentItem(currentPosition.coerceAtLeast(0), false)
        }
    }

    // ------------------------------------------------------------------ lifecycle

    override fun onStart() {
        super.onStart()
        ensurePlayer()
        if (currentPosition >= 0) {
            // the player was released in onStop: put the page that is on screen back on it
            val position = currentPosition
            currentPosition = -1
            activate(position)
        }
    }

    override fun onResume() {
        super.onResume()
        setTitle(getString(if (standalone) R.string.tab_shorts else R.string.main_toolbar_title))
        player?.playWhenReady = !userPaused && currentPosition >= 0
        handler.removeCallbacks(ticker)
        handler.post(ticker)
    }

    override fun onPause() {
        super.onPause()
        handler.removeCallbacks(ticker)
        player?.playWhenReady = false
    }

    override fun onStop() {
        super.onStop()
        releasePlayer()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        handler.removeCallbacksAndMessages(null)
        subscribeDisposable?.dispose()
        subscribeDisposable = null
        disposables.clear()
        releasePlayer()
        holders.clear()
        infoSingles.clear()
        adapter = null
        binding = null
    }

    // ------------------------------------------------------------------ feed

    private fun loadFeed() {
        val b = binding ?: return
        if (loading || exhausted) return
        loading = true
        val first = entries.isEmpty()
        if (first) {
            b.shortsEmpty.visibility = View.GONE
            b.shortsLoading.visibility = View.VISIBLE
        }
        val app = requireContext().applicationContext
        val token = nextToken
        // first call: the seed video (if any); later calls without a token: continue from the
        // last short, so the feed never just ends
        val seed = when {
            token != null -> null
            first -> seedId
            else -> entries.lastOrNull()?.id
        }
        disposables.add(
            Single.fromCallable { YtInnerTube.shortsFeed(YtAccountStore.load(app), token, seed) }
                .subscribeOn(Schedulers.io())
                .observeOn(AndroidSchedulers.mainThread())
                .subscribe(
                    { page ->
                        loading = false
                        onFeed(page)
                    },
                    {
                        loading = false
                        binding?.shortsLoading?.visibility = View.GONE
                        if (entries.isEmpty()) binding?.shortsEmpty?.visibility = View.VISIBLE
                    }
                )
        )
    }

    private fun onFeed(page: YtInnerTube.Page<YtInnerTube.ShortItem>) {
        val b = binding ?: return
        b.shortsLoading.visibility = View.GONE
        nextToken = page.continuation
        val fresh = page.items.filter { knownIds.add(it.id) }
        if (fresh.isEmpty() && page.continuation == null) {
            exhausted = true
        }
        if (fresh.isNotEmpty()) {
            val start = entries.size
            entries.addAll(fresh)
            adapter?.notifyItemRangeInserted(start, fresh.size)
        }
        if (entries.isEmpty()) {
            b.shortsEmpty.visibility = View.VISIBLE
            return
        }
        b.shortsEmpty.visibility = View.GONE
        if (currentPosition < 0) {
            b.shortsPager.post { activate(0) }
        }
    }

    // ------------------------------------------------------------------ player

    private fun ensurePlayer() {
        if (player != null || !isAdded) return
        val ctx = requireContext()
        val exo = ExoPlayer.Builder(ctx)
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(C.USAGE_MEDIA)
                    .setContentType(C.AUDIO_CONTENT_TYPE_MOVIE)
                    .build(),
                true
            )
            .build()
        exo.repeatMode = Player.REPEAT_MODE_ONE
        exo.setPlaybackSpeed(playbackSpeed)
        applyCaptions(exo)
        exo.addListener(playerListener)
        player = exo
        resolver = VideoPlaybackResolver(
            ctx,
            PlayerDataSource(ctx, DefaultBandwidthMeter.Builder(ctx).build()),
            object : VideoPlaybackResolver.QualityResolver {
                override fun getDefaultResolutionIndex(
                    sortedVideos: MutableList<org.schabi.newpipe.extractor.stream.VideoStream>
                ): Int = ListHelper.getDefaultResolutionIndex(ctx, sortedVideos)

                override fun getOverrideResolutionIndex(
                    sortedVideos: MutableList<org.schabi.newpipe.extractor.stream.VideoStream>,
                    playbackQuality: String?
                ): Int {
                    val wanted = chosenQuality
                    val idx = if (wanted == null) -1 else sortedVideos.indexOfFirst { it.getResolution() == wanted }
                    return if (idx >= 0) idx else ListHelper.getDefaultResolutionIndex(ctx, sortedVideos)
                }
            }
        )
    }

    private fun releasePlayer() {
        holders.values.forEach { detachFromHolder(it) }
        player?.removeListener(playerListener)
        player?.release()
        player = null
        resolver = null
    }

    private val playerListener = object : Player.Listener {
        override fun onRenderedFirstFrame() {
            holders[currentPosition]?.b?.shortThumb?.visibility = View.GONE
        }

        override fun onVideoSizeChanged(videoSize: VideoSize) {
            val view = holders[currentPosition]?.b?.shortPlayer ?: return
            if (videoSize.width > 0 && videoSize.height > 0) {
                val ratio = videoSize.width.toFloat() / videoSize.height
                // portrait shorts fill the page, wide videos keep their shape
                view.resizeMode = if (ratio < 0.8f) {
                    AspectRatioFrameLayout.RESIZE_MODE_ZOOM
                } else {
                    AspectRatioFrameLayout.RESIZE_MODE_FIT
                }
            }
        }

        override fun onPlayerError(error: PlaybackException) {
            context?.let { Toast.makeText(it, R.string.shorts_error, Toast.LENGTH_SHORT).show() }
        }
    }

    /** Moves the progress line while a short is playing. */
    private val ticker = object : Runnable {
        override fun run() {
            val p = player
            val h = holders[currentPosition]
            if (p != null && h != null && !dragging) {
                val duration = p.duration
                if (duration != C.TIME_UNSET && duration > 0) {
                    h.b.shortSeek.progress =
                        ((p.currentPosition * 1000L) / duration).toInt().coerceIn(0, 1000)
                }
            }
            handler.postDelayed(this, 50)
        }
    }

    // ------------------------------------------------------------------ pages

    private fun activate(position: Int) {
        if (position == currentPosition || position !in entries.indices) return
        holders[currentPosition]?.let { detachFromHolder(it) }
        currentPosition = position
        userPaused = false
        subscribeDisposable?.dispose()
        subscribeDisposable = null
        if (isAdded) VideoInfoBottomSheet.dismissIfShown(childFragmentManager)

        player?.stop()
        player?.clearMediaItems()
        holders[position]?.let { attachToHolder(it) }

        val id = entries[position].id
        disposables.add(
            infoOf(id).subscribe(
                { info -> if (position == currentPosition) onInfoReady(position, info) },
                {
                    if (position == currentPosition) {
                        context?.let { Toast.makeText(it, R.string.shorts_error, Toast.LENGTH_SHORT).show() }
                    }
                }
            )
        )
        // get the next one ready, and more of the feed when the end is close
        entries.getOrNull(position + 1)?.let { disposables.add(infoOf(it.id).subscribe({ }, { })) }
        if (position >= entries.size - 3) loadFeed()
    }

    private fun attachToHolder(h: ShortHolder) {
        h.b.shortPlayer.player = player
        h.b.shortThumb.visibility = View.VISIBLE
        h.b.shortPauseIcon.visibility = View.GONE
        setPausedUi(h, false, false)
    }

    private fun detachFromHolder(h: ShortHolder) {
        h.b.shortPlayer.player = null
        h.b.shortThumb.visibility = View.VISIBLE
        h.b.shortPauseIcon.visibility = View.GONE
        h.b.shortSeek.progress = 0
    }

    private fun infoOf(id: String): Single<StreamInfo> = infoSingles.getOrPut(id) {
        Single.fromCallable { StreamInfo.getInfo(ServiceList.YouTube, watchUrl(id)) }
            .subscribeOn(Schedulers.io())
            .observeOn(AndroidSchedulers.mainThread())
            .doOnSuccess { infoCache[id] = it }
            .doOnError { infoSingles.remove(id) }
            .cache()
    }

    private fun onInfoReady(position: Int, info: StreamInfo) {
        holders[position]?.let { applyInfo(it, info, true) }
        val exo = player ?: return
        val source = try {
            resolver?.resolve(info)
        } catch (e: Exception) {
            null
        }
        if (source == null) {
            context?.let { Toast.makeText(it, R.string.shorts_error, Toast.LENGTH_SHORT).show() }
            return
        }
        exo.setMediaSource(source)
        exo.setPlaybackSpeed(playbackSpeed)
        applyCaptions(exo)
        exo.prepare()
        exo.playWhenReady = isResumed && !userPaused
    }

    // ------------------------------------------------------------------ page content

    private inner class ShortsAdapter : RecyclerView.Adapter<ShortHolder>() {
        override fun getItemCount() = entries.size

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ShortHolder {
            val b = ItemShortBinding.inflate(LayoutInflater.from(parent.context), parent, false)
            return ShortHolder(b)
        }

        override fun onBindViewHolder(holder: ShortHolder, position: Int) {
            val entry = entries[position]
            if (holders[holder.pagePosition] === holder) holders.remove(holder.pagePosition)
            holder.pagePosition = position
            holder.videoId = entry.id
            holders[position] = holder
            bindHolder(holder, entry)
            infoCache[entry.id]?.let { applyInfo(holder, it, position == currentPosition) }
            if (position == currentPosition) attachToHolder(holder)
        }

        override fun onViewRecycled(holder: ShortHolder) {
            if (holders[holder.pagePosition] === holder) holders.remove(holder.pagePosition)
            holder.b.shortPlayer.player = null
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun bindHolder(h: ShortHolder, entry: YtInnerTube.ShortItem) {
        val b = h.b
        val id = entry.id
        val bottomInset = if (standalone) {
            resources.getDimensionPixelSize(R.dimen.nav_pill_margin_bottom)
        } else {
            // the pill floats over the page: keep the controls above it
            resources.getDimensionPixelSize(R.dimen.nav_pill_content_inset)
        }
        b.shortOverlay.setPadding(0, 0, 0, bottomInset)
        b.shortHeader.setPadding(
            b.shortHeader.paddingLeft, dp(2), b.shortHeader.paddingRight, b.shortHeader.paddingBottom
        )
        // round channel logo
        b.shortAvatar.outlineProvider = object : android.view.ViewOutlineProvider() {
            override fun getOutline(view: View, outline: android.graphics.Outline) {
                outline.setOval(0, 0, view.width, view.height)
            }
        }
        b.shortAvatar.clipToOutline = true
        // channel row, title and progress line only show while the short is paused
        setPausedUi(h, false, false)
        b.shortMenu.setOnClickListener { showSettingsMenu(id, true) }
        b.shortDislike.setOnClickListener { onDislikeClicked(id, h) }
        b.shortBell.setOnClickListener { toggleNotifications(h) }

        setTitleText(h, entry.title.orEmpty())
        b.shortBell.visibility = View.GONE
        b.shortChannel.text = ""
        b.shortSubscribe.visibility = View.GONE
        b.shortSeek.progress = 0
        b.shortAvatar.setImageDrawable(null)
        CoilHelper.loadThumbnail(b.shortThumb, entry.thumbnail)
        b.shortThumb.visibility = View.VISIBLE
        b.shortPauseIcon.visibility = View.GONE
        applyLike(h)
        b.shortCommentsCount.text = commentTexts[id].orEmpty()

        b.shortLike.setOnClickListener {
            val wasLiked = likeStates[id] == "LIKE"
            onLikeClicked(id, h)
            if (!wasLiked) {
                val cx = b.shortActions.x + b.shortLike.x + b.shortLike.width / 2f
                val cy = b.shortActions.y + b.shortLike.y + b.shortLike.height / 2f
                burst(h, cx, cy)
            }
        }
        b.shortComments.setOnClickListener { showComments(id) }
        b.shortCommentsCount.setOnClickListener { showComments(id) }
        b.shortShare.setOnClickListener { share(id) }

        val detector = GestureDetector(
            b.root.context,
            object : GestureDetector.SimpleOnGestureListener() {
                override fun onDown(e: MotionEvent) = true

                override fun onSingleTapConfirmed(e: MotionEvent): Boolean {
                    togglePause(h)
                    return true
                }

                override fun onDoubleTap(e: MotionEvent): Boolean {
                    val w = b.root.width.coerceAtLeast(1)
                    when {
                        e.x < w * 0.33f -> seekBy(h, -SEEK_STEP_MS)
                        e.x > w * 0.67f -> seekBy(h, SEEK_STEP_MS)
                        else -> {
                            if (likeStates[id] != "LIKE") onLikeClicked(id, h)
                            burst(h, e.x, e.y)
                        }
                    }
                    return true
                }

                override fun onLongPress(e: MotionEvent) {
                    b.root.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
                    showSettingsMenu(id, false)
                }
            }
        )
        b.root.setOnTouchListener { _, event -> detector.onTouchEvent(event) }

        b.shortSeek.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar, progress: Int, fromUser: Boolean) {
                val p = player ?: return
                if (fromUser && h.pagePosition == currentPosition) {
                    val duration = p.duration
                    if (duration != C.TIME_UNSET && duration > 0) {
                        p.seekTo(duration * progress / 1000L)
                    }
                }
            }

            override fun onStartTrackingTouch(seekBar: SeekBar) {
                dragging = true
            }

            override fun onStopTrackingTouch(seekBar: SeekBar) {
                dragging = false
            }
        })
    }

    private fun applyInfo(h: ShortHolder, info: StreamInfo, active: Boolean) {
        val id = h.videoId ?: return
        if (info.id != id) return
        val b = h.b
        setTitleText(h, info.name.orEmpty(), info.description?.content)
        b.shortChannel.text = info.uploaderName
        CoilHelper.loadAvatar(b.shortAvatar, info.uploaderAvatars)
        val openChannel = View.OnClickListener {
            val url = info.uploaderUrl
            if (!url.isNullOrEmpty()) {
                NavigationHelper.openChannelFragment(getFM(), info.serviceId, url, info.uploaderName.orEmpty())
            }
        }
        b.shortAvatar.setOnClickListener(openChannel)
        b.shortChannel.setOnClickListener(openChannel)

        if (!likeCounts.containsKey(id) && info.likeCount >= 0) likeCounts[id] = info.likeCount
        applyLike(h)

        if (!active) return
        setupSubscribe(h, info)

        val ctx = context ?: return
        if (!commentTexts.containsKey(id)) {
            disposables.add(
                YtEngagement.commentCount(id).subscribe(
                    { text ->
                        commentTexts[id] = text
                        if (h.videoId == id) h.b.shortCommentsCount.text = text
                    },
                    { if (h.videoId == id) h.b.shortCommentsCount.setText(R.string.comments_tab_description) },
                    { if (h.videoId == id) h.b.shortCommentsCount.setText(R.string.comments_tab_description) }
                )
            )
        }
        if (YtEngagement.isLoggedIn(ctx) && !likeStates.containsKey(id)) {
            disposables.add(
                YtEngagement.likeStatus(ctx, id).subscribe(
                    { state ->
                        likeStates[id] = state
                        if (h.videoId == id) applyLike(h)
                    },
                    { }
                )
            )
        }
    }

    private fun applyLike(h: ShortHolder) {
        val ctx = context ?: return
        val id = h.videoId ?: return
        val liked = likeStates[id] == "LIKE"
        // never pass null here: it wipes the vector's own tint
        h.b.shortLike.imageTintList =
            ColorStateList.valueOf(if (liked) LIKED_COLOR else Color.WHITE)
        h.b.shortDislike.imageTintList =
            ColorStateList.valueOf(if (likeStates[id] == "DISLIKE") DISLIKED_COLOR else Color.WHITE)
        val count = likeCounts[id]
        h.b.shortLikeCount.text =
            if (count != null && count >= 0) Localization.shortCount(ctx, count) else ""
    }

    private fun togglePause(h: ShortHolder) {
        val p = player ?: return
        if (h.pagePosition != currentPosition) return
        val icon = h.b.shortPauseIcon
        icon.animate().cancel()
        if (p.playWhenReady) {
            p.playWhenReady = false
            userPaused = true
            icon.setImageResource(R.drawable.ic_play_arrow)
        } else {
            p.playWhenReady = true
            userPaused = false
            icon.setImageResource(R.drawable.ic_pause)
        }
        // subtle central overlay that fades away again
        icon.visibility = View.VISIBLE
        icon.alpha = 0.85f
        icon.scaleX = 0.8f
        icon.scaleY = 0.8f
        setPausedUi(h, userPaused, true)
        icon.animate().alpha(0f).scaleX(1.15f).scaleY(1.15f).setStartDelay(350).setDuration(350)
            .setListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) {
                    icon.visibility = View.GONE
                    icon.animate().setListener(null)
                }
            }).start()
    }

    // ------------------------------------------------------------------ actions

    private fun onLikeClicked(id: String, h: ShortHolder) {
        val ctx = context ?: return
        if (!YtEngagement.isLoggedIn(ctx)) {
            Toast.makeText(ctx, R.string.yt_sign_in_required, Toast.LENGTH_LONG).show()
            return
        }
        val previous = likeStates[id] ?: "INDIFFERENT"
        val unlike = previous == "LIKE"
        val next = if (unlike) "INDIFFERENT" else "LIKE"
        val previousCount = likeCounts[id]
        likeStates[id] = next
        previousCount?.let { likeCounts[id] = (it + if (unlike) -1 else 1).coerceAtLeast(0) }
        applyLike(h)
        disposables.add(
            YtEngagement.rate(ctx, id, if (unlike) "removelike" else "like").subscribe(
                { },
                { e ->
                    likeStates[id] = previous
                    if (previousCount != null) likeCounts[id] = previousCount
                    if (h.videoId == id) applyLike(h)
                    context?.let {
                        Toast.makeText(it, getString(R.string.yt_rate_failed, e.message), Toast.LENGTH_LONG).show()
                    }
                }
            )
        )
    }

    private fun showComments(id: String) {
        if (!isAdded || childFragmentManager.isStateSaved) return
        val title = infoCache[id]?.name ?: entries.firstOrNull { it.id == id }?.title.orEmpty()
        // the sheet covers the lower part of the page, the short keeps playing above it
        val topOffset = (resources.displayMetrics.heightPixels * 0.40f).toInt()
        VideoInfoBottomSheet.showComments(
            childFragmentManager,
            ServiceList.YouTube.serviceId,
            watchUrl(id),
            title,
            topOffset
        )
    }

    private fun share(id: String) {
        val send = Intent(Intent.ACTION_SEND)
            .setType("text/plain")
            .putExtra(Intent.EXTRA_TEXT, "https://www.youtube.com/shorts/$id")
        startActivity(Intent.createChooser(send, getString(R.string.shorts_share)))
    }

    private fun setupSubscribe(h: ShortHolder, info: StreamInfo) {
        val ctx = context ?: return
        val button: TextView = h.b.shortSubscribe
        val channelUrl = info.uploaderUrl
        subscribeDisposable?.dispose()
        if (channelUrl.isNullOrEmpty()) {
            button.visibility = View.GONE
            return
        }
        button.visibility = View.VISIBLE
        val manager = SubscriptionManager(ctx)
        val serviceId = info.serviceId
        styleSubscribe(button, false)

        val flow = manager.subscriptionTable().getSubscriptionFlowable(serviceId, channelUrl)
            .observeOn(AndroidSchedulers.mainThread())
            .subscribe(
                { list ->
                    subscribed = list.isNotEmpty()
                    notificationsOn = list.firstOrNull()?.notificationMode == NotificationMode.ENABLED
                    styleSubscribe(button, subscribed)
                    h.b.shortBell.visibility = if (subscribed) View.VISIBLE else View.GONE
                    h.b.shortBell.alpha = if (notificationsOn) 1f else 0.6f
                },
                { }
            )
        subscribeDisposable = flow

        button.setOnClickListener {
            if (subscribed) {
                disposables.add(
                    manager.deleteSubscription(serviceId, channelUrl).subscribe(
                        { toast(R.string.channel_unsubscribed) },
                        { toast(R.string.subscription_change_failed) }
                    )
                )
            } else {
                val entity = SubscriptionEntity()
                entity.serviceId = serviceId
                entity.url = channelUrl
                entity.name = info.uploaderName
                entity.avatarUrl = ImageStrategy.imageListToDbUrl(info.uploaderAvatars)
                disposables.add(
                    Completable.fromAction { manager.insertSubscription(entity) }
                        .subscribeOn(Schedulers.io())
                        .observeOn(AndroidSchedulers.mainThread())
                        .subscribe({ }, { toast(R.string.subscription_change_failed) })
                )
            }
        }
    }

    private fun styleSubscribe(button: TextView, isSubscribed: Boolean) {
        button.setText(if (isSubscribed) R.string.subscribed_button_title else R.string.subscribe_button_title)
        button.setBackgroundResource(
            if (isSubscribed) R.drawable.bg_shorts_subscribed else R.drawable.bg_shorts_subscribe
        )
        button.setTextColor(if (isSubscribed) Color.WHITE else Color.BLACK)
    }

    private fun toast(res: Int) {
        context?.let { Toast.makeText(it, res, Toast.LENGTH_SHORT).show() }
    }

    // ------------------------------------------------------------------ new UI pieces

    /** Channel row, title and progress line are only visible while the short is paused. */
    private fun setPausedUi(h: ShortHolder, paused: Boolean, animate: Boolean) {
        val views = listOf(h.b.shortInfo, h.b.shortSeek, h.b.shortScrim)
        views.forEach { v ->
            v.animate().cancel()
            if (paused) {
                v.visibility = View.VISIBLE
                if (animate) {
                    v.alpha = 0f
                    v.animate().alpha(1f).setDuration(200).start()
                } else {
                    v.alpha = 1f
                }
            } else if (animate) {
                v.animate().alpha(0f).setDuration(150)
                    .withEndAction { v.visibility = View.INVISIBLE }.start()
            } else {
                v.alpha = 0f
                v.visibility = View.INVISIBLE
            }
        }
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    /** Title with blue bold #hashtags, "...more" when longer than two lines. */
    private fun setTitleText(h: ShortHolder, title: String, description: String? = null) {
        val id = h.videoId ?: return
        val tv = h.b.shortTitle
        val expanded = expandedTitles.contains(id)
        val full = if (expanded && !description.isNullOrBlank() && description != title) {
            "$title\n\n$description"
        } else {
            title
        }
        val sb = SpannableStringBuilder(full)
        Regex("#[\\p{L}\\p{N}_]+").findAll(full).forEach { m ->
            sb.setSpan(StyleSpan(Typeface.BOLD), m.range.first, m.range.last + 1, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            sb.setSpan(ForegroundColorSpan(HASHTAG_COLOR), m.range.first, m.range.last + 1, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
        tv.movementMethod = LinkMovementMethod.getInstance()
        tv.maxLines = if (expanded) 12 else 2
        tv.text = sb
        tv.setOnClickListener {
            if (expandedTitles.add(id)) {
                setTitleText(h, title, infoCache[id]?.description?.content)
            } else {
                expandedTitles.remove(id)
                setTitleText(h, title, null)
            }
        }
        if (!expanded) {
            tv.post {
                val layout = tv.layout ?: return@post
                if (layout.lineCount > 2 || (layout.lineCount > 0 && layout.getEllipsisCount(layout.lineCount - 1) > 0)) {
                    val end = layout.getLineEnd(1).coerceAtMost(sb.length)
                    val cut = SpannableStringBuilder(sb.subSequence(0, (end - 8).coerceAtLeast(0)).toString().trimEnd())
                    // re-apply hashtag spans on the shortened text
                    Regex("#[\\p{L}\\p{N}_]+").findAll(cut).forEach { m ->
                        cut.setSpan(StyleSpan(Typeface.BOLD), m.range.first, m.range.last + 1, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                        cut.setSpan(ForegroundColorSpan(HASHTAG_COLOR), m.range.first, m.range.last + 1, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                    }
                    val start = cut.length
                    cut.append("… ").append(getString(R.string.shorts_more))
                    cut.setSpan(StyleSpan(Typeface.BOLD), start, cut.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                    tv.text = cut
                }
            }
        }
    }

    private fun seekBy(h: ShortHolder, deltaMs: Long) {
        val p = player ?: return
        if (h.pagePosition != currentPosition) return
        val duration = if (p.duration == C.TIME_UNSET) Long.MAX_VALUE else p.duration
        p.seekTo((p.currentPosition + deltaMs).coerceIn(0L, duration))
        val view = if (deltaMs < 0) h.b.shortSeekLeft else h.b.shortSeekRight
        view.animate().cancel()
        view.alpha = 0.9f
        view.scaleX = 0.6f
        view.scaleY = 0.6f
        view.animate().scaleX(1f).scaleY(1f).alpha(0f).setDuration(500).start()
    }

    /** Floating hearts rising from the touch / button position. */
    private fun burst(h: ShortHolder, x: Float, y: Float) {
        val layer: FrameLayout = h.b.shortBurstLayer
        repeat(7) {
            val heart = ImageView(layer.context)
            val size = dp(Random.nextInt(24, 44))
            heart.layoutParams = FrameLayout.LayoutParams(size, size)
            heart.setImageResource(R.drawable.ic_favorite)
            heart.imageTintList = ColorStateList.valueOf(LIKED_COLOR)
            heart.x = x - size / 2f
            heart.y = y - size / 2f
            heart.alpha = 0f
            layer.addView(heart)
            heart.animate()
                .alpha(1f).setDuration(120)
                .withEndAction {
                    heart.animate()
                        .translationXBy(Random.nextInt(-dp(60), dp(60)).toFloat())
                        .translationYBy(-Random.nextInt(dp(120), dp(260)).toFloat())
                        .scaleX(1.4f).scaleY(1.4f).alpha(0f)
                        .setDuration(Random.nextLong(600, 1000))
                        .withEndAction { layer.removeView(heart) }
                        .start()
                }.start()
        }
    }

    private fun onDislikeClicked(id: String, h: ShortHolder) {
        val ctx = context ?: return
        if (!YtEngagement.isLoggedIn(ctx)) {
            Toast.makeText(ctx, R.string.yt_sign_in_required, Toast.LENGTH_LONG).show()
            return
        }
        val previous = likeStates[id] ?: "INDIFFERENT"
        val previousLikes = likeCounts[id]
        val undo = previous == "DISLIKE"
        likeStates[id] = if (undo) "INDIFFERENT" else "DISLIKE"
        if (previous == "LIKE") previousLikes?.let { likeCounts[id] = (it - 1).coerceAtLeast(0) }
        applyLike(h)
        disposables.add(
            YtEngagement.rate(ctx, id, if (undo) "removelike" else "dislike").subscribe(
                { },
                { e ->
                    likeStates[id] = previous
                    if (previousLikes != null) likeCounts[id] = previousLikes
                    if (h.videoId == id) applyLike(h)
                    context?.let {
                        Toast.makeText(it, getString(R.string.yt_rate_failed, e.message), Toast.LENGTH_LONG).show()
                    }
                }
            )
        )
    }

    private fun toggleNotifications(h: ShortHolder) {
        val ctx = context ?: return
        val info = h.videoId?.let { infoCache[it] } ?: return
        val url = info.uploaderUrl ?: return
        val mode = if (notificationsOn) NotificationMode.DISABLED else NotificationMode.ENABLED
        disposables.add(
            SubscriptionManager(ctx).updateNotificationMode(info.serviceId, url, mode)
                .observeOn(AndroidSchedulers.mainThread())
                .subscribe({ }, { toast(R.string.subscription_change_failed) })
        )
    }

    private fun applyCaptions(exo: ExoPlayer) {
        exo.trackSelectionParameters = exo.trackSelectionParameters.buildUpon()
            .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, !captionsOn)
            .build()
    }

    /** Settings menu: [full] = three-dot menu (everything), otherwise the long-press quick menu. */
    private fun showSettingsMenu(id: String, full: Boolean) {
        val ctx = context ?: return
        val labels = ArrayList<String>()
        val actions = ArrayList<() -> Unit>()
        labels.add(getString(R.string.shorts_playback_speed) + " (" + speedLabel(playbackSpeed) + ")")
        actions.add { showSpeedDialog() }
        labels.add(getString(R.string.shorts_quality) + " (" + (chosenQuality ?: getString(R.string.shorts_auto)) + ")")
        actions.add { showQualityDialog(id) }
        labels.add(getString(R.string.caption_setting_title) + ": " +
            getString(if (captionsOn) R.string.shorts_on else R.string.shorts_off))
        actions.add { toggleCaptions(id) }
        if (full) {
            labels.add(getString(R.string.shorts_description))
            actions.add {
                infoCache[id]?.let {
                    VideoInfoBottomSheet.showDescription(
                        childFragmentManager, it, (resources.displayMetrics.heightPixels * 0.40f).toInt()
                    )
                }
            }
            labels.add(getString(R.string.shorts_audio_source))
            actions.add {
                val info = infoCache[id]
                val text = getString(R.string.shorts_original_audio, info?.uploaderName.orEmpty())
                Toast.makeText(ctx, text, Toast.LENGTH_LONG).show()
            }
        }
        labels.add(getString(R.string.shorts_report))
        actions.add {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://www.youtube.com/shorts/$id")))
        }
        AlertDialog.Builder(ctx)
            .setItems(labels.toTypedArray()) { _, which -> actions[which]() }
            .show()
    }

    private fun speedLabel(v: Float) = (if (v == v.toInt().toFloat()) v.toInt().toString() else v.toString()) + "x"

    private fun showSpeedDialog() {
        val ctx = context ?: return
        val speeds = listOf(0.25f, 0.5f, 0.75f, 1f, 1.25f, 1.5f, 2f)
        AlertDialog.Builder(ctx)
            .setTitle(R.string.shorts_playback_speed)
            .setSingleChoiceItems(
                speeds.map { speedLabel(it) }.toTypedArray(), speeds.indexOf(playbackSpeed)
            ) { d, which ->
                playbackSpeed = speeds[which]
                player?.setPlaybackSpeed(playbackSpeed)
                d.dismiss()
            }.show()
    }

    private fun showQualityDialog(id: String) {
        val ctx = context ?: return
        val info = infoCache[id] ?: return
        val options = ArrayList<String>()
        info.videoStreams.orEmpty().map { it.getResolution() }
            .distinct().filter { it.isNotEmpty() }.forEach { options.add(it) }
        val labels = (listOf(getString(R.string.shorts_auto)) + options).toTypedArray()
        val checked = chosenQuality?.let { options.indexOf(it) + 1 }?.coerceAtLeast(0) ?: 0
        AlertDialog.Builder(ctx)
            .setTitle(R.string.shorts_quality)
            .setSingleChoiceItems(labels, checked) { d, which ->
                chosenQuality = if (which == 0) null else options[which - 1]
                d.dismiss()
                val exo = player ?: return@setSingleChoiceItems
                val position = exo.currentPosition
                val source = try { resolver?.resolve(info) } catch (e: Exception) { null }
                if (source != null) {
                    exo.setMediaSource(source, position)
                    exo.prepare()
                    exo.playWhenReady = isResumed && !userPaused
                }
            }.show()
    }

    private fun toggleCaptions(id: String) {
        captionsOn = !captionsOn
        player?.let { applyCaptions(it) }
        val hasSubs = infoCache[id]?.subtitles.orEmpty().isNotEmpty()
        if (captionsOn && !hasSubs) toast(R.string.caption_none)
    }

    private fun watchUrl(id: String) = "https://www.youtube.com/watch?v=$id"

    companion object {
        private const val ARG_SEED = "shorts_seed_id"
        private val LIKED_COLOR = Color.parseColor("#FFD71921")
        private val DISLIKED_COLOR = Color.parseColor("#FF8AB4F8")
        private val HASHTAG_COLOR = Color.parseColor("#FF5AA9FF")
        private const val SEEK_STEP_MS = 5000L

        /** Shorts player that starts with the given short, then continues with YouTube's picks. */
        @JvmStatic
        fun newInstance(seedVideoId: String): ShortsFragment = ShortsFragment().apply {
            arguments = Bundle().apply { putString(ARG_SEED, seedVideoId) }
        }
    }
}
