package org.schabi.newpipe.account

import android.content.Context
import io.reactivex.rxjava3.android.schedulers.AndroidSchedulers
import io.reactivex.rxjava3.core.Completable
import io.reactivex.rxjava3.core.Maybe
import io.reactivex.rxjava3.schedulers.Schedulers

/** Like / dislike / comment actions for the signed-in Google account (Java friendly). */
object YtEngagement {
    private fun session(context: Context) =
        YtAccountStore.load(context.applicationContext)
            ?: throw YtInnerTube.AuthException("Not signed in")

    @JvmStatic
    fun rate(context: Context, videoId: String, action: String): Completable =
        Completable.fromAction { YtInnerTube.rate(session(context), videoId, action) }
            .subscribeOn(Schedulers.io()).observeOn(AndroidSchedulers.mainThread())

    @JvmStatic
    fun likeStatus(context: Context, videoId: String): Maybe<String> =
        Maybe.fromCallable<String> { YtInnerTube.likeStatus(session(context), videoId) }
            .subscribeOn(Schedulers.io()).observeOn(AndroidSchedulers.mainThread())

    @JvmStatic
    fun postComment(context: Context, videoId: String, text: String): Completable =
        Completable.fromAction { YtInnerTube.postComment(session(context), videoId, text) }
            .subscribeOn(Schedulers.io()).observeOn(AndroidSchedulers.mainThread())

    @JvmStatic
    fun isLoggedIn(context: Context): Boolean = YtAccountStore.isLoggedIn(context)
}
