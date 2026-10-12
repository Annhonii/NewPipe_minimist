package org.schabi.newpipe.account

import android.content.Context
import android.widget.Toast
import org.schabi.newpipe.R
import io.reactivex.rxjava3.android.schedulers.AndroidSchedulers
import io.reactivex.rxjava3.core.Single
import io.reactivex.rxjava3.schedulers.Schedulers
import org.schabi.newpipe.NewPipeDatabase
import org.schabi.newpipe.database.subscription.SubscriptionEntity
import org.schabi.newpipe.extractor.ServiceList
import org.schabi.newpipe.extractor.Image
import org.schabi.newpipe.util.image.ImageStrategy

object YtAccountActions {
    data class SyncResult(val total: Int, val added: Int)

    /** Fetches every subscribed channel of the signed-in account and stores it locally. */
    fun syncSubscriptions(context: Context): Single<SyncResult> {
        val app = context.applicationContext
        return Single.fromCallable {
            val session = YtAccountStore.load(app)
                ?: throw YtInnerTube.AuthException("Not signed in")

            val channels = LinkedHashMap<String, YtInnerTube.Channel>()
            var token: String? = null
            var pages = 0
            do {
                val page = YtInnerTube.subscriptionsPage(session, token)
                page.items.forEach { channels.putIfAbsent(it.id, it) }
                token = page.continuation
                pages++
            } while (token != null && pages < 60)

            if (channels.isEmpty()) {
                throw java.io.IOException(
                    "No subscribed channels were found (is this account subscribed to anything?)"
                )
            }

            val serviceId = ServiceList.YouTube.serviceId
            val dao = NewPipeDatabase.getInstance(app).subscriptionDAO()
            val existing = dao.getAll().blockingFirst().mapNotNull { it.url }.toHashSet()

            val entities = channels.values.map { c ->
                val url = "https://www.youtube.com/channel/${c.id}"
                SubscriptionEntity(
                    serviceId = serviceId,
                    url = url,
                    name = c.name,
                    avatarUrl = c.avatar?.let {
                        ImageStrategy.imageListToDbUrl(
                            listOf(
                                Image(it, Image.HEIGHT_UNKNOWN, Image.WIDTH_UNKNOWN, Image.ResolutionLevel.UNKNOWN)
                            )
                        )
                    }
                )
            }
            val added = entities.count { it.url !in existing }
            dao.upsertAll(entities)
            SyncResult(entities.size, added)
        }.subscribeOn(Schedulers.io()).observeOn(AndroidSchedulers.mainThread())
    }

    /**
     * Saves the session (after a best-effort name/email lookup) and returns it.
     * Runs on io; result delivered on main.
     */
    fun completeLogin(context: Context, session: YtAccountStore.Session): Single<YtAccountStore.Session> {
        val app = context.applicationContext
        return Single.fromCallable {
            val info = YtInnerTube.accountInfo(session)
            val full = if (info != null) {
                session.copy(
                    name = session.name.ifBlank { info.name },
                    email = session.email.ifBlank { info.email },
                    avatar = session.avatar.ifBlank { info.avatar }
                )
            } else {
                session
            }
            YtAccountStore.save(app, full)
            full
        }.subscribeOn(Schedulers.io()).observeOn(AndroidSchedulers.mainThread())
    }

    /**
     * Shared tail of both login methods: persist the session, tell the caller, then pull
     * the subscribed channels. Toasts use the application context so it is safe to call
     * this and immediately finish the calling screen.
     */
    fun loginAndSync(
        context: Context,
        session: YtAccountStore.Session,
        onLoggedIn: (YtAccountStore.Session) -> Unit = {},
        onLoginFailed: (Throwable) -> Unit = {}
    ) {
        val app = context.applicationContext
        var loggedIn = false
        completeLogin(app, session)
            .doOnSuccess {
                loggedIn = true
                onLoggedIn(it)
                Toast.makeText(app, app.getString(R.string.yt_signed_in_syncing), Toast.LENGTH_SHORT).show()
            }
            .flatMap { syncSubscriptions(app) }
            .subscribe(
                { r ->
                    Toast.makeText(
                        app,
                        app.getString(R.string.yt_sync_done, r.total, r.added),
                        Toast.LENGTH_LONG
                    ).show()
                },
                { e ->
                    if (loggedIn) {
                        Toast.makeText(
                            app,
                            app.getString(R.string.yt_sync_failed, e.message ?: e.javaClass.simpleName),
                            Toast.LENGTH_LONG
                        ).show()
                    } else {
                        onLoginFailed(e)
                    }
                }
            )
    }

    /** Re-reads name / photo of an already signed-in account (older sessions have no photo). */
    fun refreshProfile(context: Context): Single<Boolean> {
        val app = context.applicationContext
        return Single.fromCallable {
            val session = YtAccountStore.load(app) ?: return@fromCallable false
            val info = YtInnerTube.accountInfo(session) ?: return@fromCallable false
            YtAccountStore.save(
                app,
                session.copy(
                    name = info.name.ifBlank { session.name },
                    email = info.email.ifBlank { session.email },
                    avatar = info.avatar.ifBlank { session.avatar }
                )
            )
            true
        }.subscribeOn(Schedulers.io()).observeOn(AndroidSchedulers.mainThread())
    }

    /** Sync with progress / result toasts (used by the account dialog). */
    fun syncWithToasts(context: Context) {
        val app = context.applicationContext
        Toast.makeText(app, R.string.yt_syncing, Toast.LENGTH_SHORT).show()
        syncSubscriptions(app).subscribe(
            { r ->
                Toast.makeText(app, app.getString(R.string.yt_sync_done, r.total, r.added), Toast.LENGTH_LONG).show()
            },
            { e ->
                Toast.makeText(
                    app,
                    app.getString(R.string.yt_sync_failed, e.message ?: e.javaClass.simpleName),
                    Toast.LENGTH_LONG
                ).show()
            }
        )
    }
}
