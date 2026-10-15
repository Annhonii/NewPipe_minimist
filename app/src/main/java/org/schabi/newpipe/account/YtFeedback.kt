package org.schabi.newpipe.account

import android.content.Context
import io.reactivex.rxjava3.android.schedulers.AndroidSchedulers
import io.reactivex.rxjava3.core.Completable
import io.reactivex.rxjava3.schedulers.Schedulers

/** "Not interested" / "Don't recommend channel" for the recommended videos (signed in or not). */
object YtFeedback {
    @JvmStatic
    fun canNotInterested(videoId: String): Boolean =
        YtInnerTube.feedbackFor(videoId)?.notInterested != null

    @JvmStatic
    fun canDontRecommendChannel(videoId: String): Boolean =
        YtInnerTube.feedbackFor(videoId)?.dontRecommendChannel != null

    @JvmStatic
    fun notInterested(context: Context, videoId: String): Completable =
        send(context, YtInnerTube.feedbackFor(videoId)?.notInterested)

    @JvmStatic
    fun dontRecommendChannel(context: Context, videoId: String): Completable =
        send(context, YtInnerTube.feedbackFor(videoId)?.dontRecommendChannel)

    private fun send(context: Context, token: String?): Completable {
        val app = context.applicationContext
        return Completable.fromAction {
            if (token == null) throw java.io.IOException("No feedback option for this video")
            if (!YtInnerTube.sendFeedback(YtAccountStore.load(app), token)) {
                throw java.io.IOException("YouTube did not accept the feedback")
            }
        }.subscribeOn(Schedulers.io()).observeOn(AndroidSchedulers.mainThread())
    }
}
