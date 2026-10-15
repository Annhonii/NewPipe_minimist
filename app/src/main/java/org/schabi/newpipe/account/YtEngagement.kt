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

    /** kind: "like", "unlike", "dislike" or "undislike". */
    @JvmStatic
    fun commentAction(context: Context, videoId: String, commentId: String, kind: String): Completable =
        Completable.fromAction { YtInnerTube.commentAction(session(context), videoId, commentId, kind) }
            .subscribeOn(Schedulers.io()).observeOn(AndroidSchedulers.mainThread())

    @JvmStatic
    fun replyToComment(context: Context, videoId: String, commentId: String, text: String): Completable =
        Completable.fromAction { YtInnerTube.replyToComment(session(context), videoId, commentId, text) }
            .subscribeOn(Schedulers.io()).observeOn(AndroidSchedulers.mainThread())

    /** Comment count as YouTube shows it (e.g. "1.9K"); empty when unknown. Needs no login. */
    @JvmStatic
    fun commentCount(videoId: String): Maybe<String> =
        Maybe.fromCallable<String> { YtInnerTube.commentCountText(videoId) }
            .subscribeOn(Schedulers.io()).observeOn(AndroidSchedulers.mainThread())

    @JvmStatic
    fun editComment(context: Context, videoId: String, commentId: String, text: String): Completable =
        Completable.fromAction { YtInnerTube.editComment(session(context), videoId, commentId, text) }
            .subscribeOn(Schedulers.io()).observeOn(AndroidSchedulers.mainThread())

    /** True if the comment was (most likely) written by the signed-in account. */
    @JvmStatic
    fun isOwnComment(context: Context, uploaderName: String?): Boolean {
        val s = YtAccountStore.load(context.applicationContext) ?: return false
        fun norm(x: String) = x.trim().removePrefix("@").lowercase()
        val me = norm(s.name)
        if (me.isEmpty()) return true // account name unknown: let the server decide
        return norm(uploaderName.orEmpty()) == me
    }
}
