package org.schabi.newpipe.account

import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.security.MessageDigest
import java.util.Locale
import java.util.concurrent.TimeUnit

/**
 * Thin authenticated InnerTube (youtubei) client. NewPipeExtractor cannot send the
 * account cookie / SAPISIDHASH, so the personalised parts (subscription list and the
 * home recommendations) are fetched here.
 */
object YtInnerTube {
    private const val ORIGIN = "https://www.youtube.com"
    private const val CLIENT_VERSION = "2.20250925.01.00"
    private const val UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
        "(KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36"

    class AuthException(message: String) : IOException(message)

    data class Video(
        val id: String,
        val title: String,
        val channelName: String?,
        val channelId: String?,
        val thumbnail: String?,
        val durationSec: Long,
        val viewCount: Long,
        val published: String?,
        val avatar: String?
    )

    data class Channel(val id: String, val name: String, val avatar: String?)

    /** One short as listed by YouTube's shorts feed (details are loaded when it is played). */
    data class ShortItem(val id: String, val title: String?, val thumbnail: String?)

    data class AccountInfo(val name: String, val email: String, val avatar: String)

    data class Page<T>(val items: List<T>, val continuation: String?)

    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    // ---------------------------------------------------------------- auth

    fun sapisidHash(sapisid: String): String {
        val ts = System.currentTimeMillis() / 1000
        val digest = MessageDigest.getInstance("SHA-1")
            .digest("$ts $sapisid $ORIGIN".toByteArray())
        return "${ts}_" + digest.joinToString("") { "%02x".format(it) }
    }

    private fun contextJson(session: YtAccountStore.Session?): JSONObject {
        val loc = Locale.getDefault()
        val c = JSONObject()
            .put("clientName", "WEB")
            .put("clientVersion", CLIENT_VERSION)
            .put("hl", loc.language.ifBlank { "en" })
            .put("gl", loc.country.ifBlank { "US" })
        session?.visitorData?.takeIf { it.isNotBlank() }?.let { c.put("visitorData", it) }
        val ctx = JSONObject().put("client", c)
        val user = JSONObject().put("lockedSafetyMode", false)
        session?.dataSyncId?.substringBefore("||")?.takeIf { it.isNotBlank() }
            ?.let { user.put("onBehalfOfUser", it) }
        return ctx.put("user", user)
    }

    private fun post(path: String, body: JSONObject, session: YtAccountStore.Session?): JSONObject {
        body.put("context", contextJson(session))
        val b = Request.Builder()
            .url("$ORIGIN/youtubei/v1/$path?prettyPrint=false")
            .post(body.toString().toRequestBody("application/json".toMediaType()))
            .header("User-Agent", UA)
            .header("Origin", ORIGIN)
            .header("Referer", "$ORIGIN/")
            .header("X-Youtube-Client-Name", "1")
            .header("X-Youtube-Client-Version", CLIENT_VERSION)
        if (session != null) {
            val sapisid = session.sapisid
                ?: throw AuthException("Invalid token")
            b.header("Cookie", session.cookie.replace("\n", "").replace("\r", "").trim())
            b.header("Authorization", "SAPISIDHASH ${sapisidHash(sapisid)}")
            b.header("X-Origin", ORIGIN)
            b.header("X-Goog-AuthUser", session.authUser.ifBlank { "0" })
            if (session.visitorData.isNotBlank()) b.header("X-Goog-Visitor-Id", session.visitorData)
        }
        client.newCall(b.build()).execute().use { resp ->
            if (resp.code == 401 || resp.code == 403) {
                throw AuthException("Google rejected the session (HTTP ${resp.code}). Please sign in again.")
            }
            if (!resp.isSuccessful) throw IOException("YouTube returned HTTP ${resp.code}")
            return JSONObject(resp.body?.string() ?: throw IOException("Empty response"))
        }
    }

    fun browse(session: YtAccountStore.Session?, browseId: String?, continuation: String?): JSONObject {
        val body = JSONObject()
        if (continuation != null) body.put("continuation", continuation) else body.put("browseId", browseId)
        return post("browse", body, session)
    }

    /** Best effort: name / email of the signed-in account. Returns null on any failure. */
    fun accountInfo(session: YtAccountStore.Session): AccountInfo? = runCatching {
        val json = post("account/account_menu", JSONObject(), session)
        val header = collect(json, "activeAccountHeaderRenderer").firstOrNull() ?: return null
        val name = text(header.optJSONObject("accountName")).orEmpty()
        val email = text(header.optJSONObject("email")).orEmpty()
        val photo = header.optJSONObject("accountPhoto") ?: collect(json, "accountPhoto").firstOrNull()
        val avatar = lastThumb(photo?.optJSONArray("thumbnails")).orEmpty()
        if (name.isBlank() && email.isBlank() && avatar.isBlank()) null
        else AccountInfo(name, email, avatar)
    }.getOrNull()

    // ---------------------------------------------------------------- JSON helpers

    /** Depth-first collection (document order) of every object stored under [key]. */
    fun collect(node: Any?, key: String, out: MutableList<JSONObject> = ArrayList()): List<JSONObject> {
        when (node) {
            is JSONObject -> {
                val keys = node.keys()
                while (keys.hasNext()) {
                    val k = keys.next()
                    val v = node.opt(k)
                    if (k == key && v is JSONObject) out.add(v)
                    if (v is JSONObject || v is JSONArray) collect(v, key, out)
                }
            }
            is JSONArray -> for (i in 0 until node.length()) collect(node.opt(i), key, out)
        }
        return out
    }

    fun text(o: JSONObject?): String? {
        if (o == null) return null
        o.optString("simpleText").takeIf { it.isNotEmpty() }?.let { return it }
        o.optString("content").takeIf { it.isNotEmpty() }?.let { return it }
        val runs = o.optJSONArray("runs") ?: return null
        val sb = StringBuilder()
        for (i in 0 until runs.length()) sb.append(runs.optJSONObject(i)?.optString("text").orEmpty())
        return sb.toString().takeIf { it.isNotEmpty() }
    }

    private fun fixUrl(u: String?): String? = when {
        u.isNullOrBlank() -> null
        u.startsWith("//") -> "https:$u"
        else -> u
    }

    private fun lastThumb(arr: JSONArray?, key: String = "url"): String? {
        if (arr == null || arr.length() == 0) return null
        return fixUrl(arr.optJSONObject(arr.length() - 1)?.optString(key))
    }

    private fun firstThumb(arr: JSONArray?): String? =
        if (arr == null || arr.length() == 0) null else fixUrl(arr.optJSONObject(0)?.optString("url"))

    fun parseDuration(s: String?): Long {
        if (s == null || !s.contains(":")) return -1
        val parts = s.trim().split(":").map { it.filter(Char::isDigit) }
        if (parts.any { it.isEmpty() }) return -1
        var total = 0L
        parts.forEach { total = total * 60 + it.toLong() }
        return total
    }

    fun parseCount(s: String?): Long {
        if (s == null) return -1
        val m = Regex("([\\d.,]+)\\s*([KMBkmb]?)").find(s) ?: return -1
        val num = m.groupValues[1]
        val suffix = m.groupValues[2].uppercase()
        return if (suffix.isEmpty()) {
            num.filter(Char::isDigit).toLongOrNull() ?: -1
        } else {
            val d = num.replace(',', '.').toDoubleOrNull() ?: return -1
            val mul = when (suffix) { "K" -> 1e3; "M" -> 1e6; else -> 1e9 }
            (d * mul).toLong()
        }
    }

    private fun continuationOf(json: JSONObject): String? =
        collect(json, "continuationItemRenderer").lastOrNull()
            ?.optJSONObject("continuationEndpoint")
            ?.optJSONObject("continuationCommand")
            ?.optString("token")?.takeIf { it.isNotEmpty() }

    // ---------------------------------------------------------------- home feed

    fun homeFeed(session: YtAccountStore.Session?, continuation: String? = null): Page<Video> {
        val json = browse(session, "FEwhat_to_watch", continuation)
        return parseVideos(json)
    }

    private const val SEARCH_PREFIX = "search:"
    private val FALLBACK_QUERIES = listOf(
        "trending now", "new videos today", "popular music videos", "gaming highlights",
        "tech reviews", "movie trailers", "podcast clips", "funny moments"
    )

    /**
     * Recommendations that work without a Google account (and as a safety net when the
     * personalised "what to watch" feed comes back empty): popular videos from a few search
     * queries, merged. The continuation token is prefixed with "search:" so [feed] can tell
     * which source a continuation belongs to.
     */
    fun searchFeed(session: YtAccountStore.Session?, continuation: String? = null): Page<Video> {
        val round = continuation?.removePrefix(SEARCH_PREFIX)?.toIntOrNull() ?: 0
        val queries = FALLBACK_QUERIES.drop(round * 2).take(2)
        if (queries.isEmpty()) return Page(emptyList(), null)
        val seen = HashSet<String>()
        val out = ArrayList<Video>()
        queries.forEach { q ->
            runCatching {
                parseVideos(post("search", JSONObject().put("query", q), session)).items
            }.getOrDefault(emptyList()).forEach { if (seen.add(it.id)) out.add(it) }
        }
        val next = if ((round + 1) * 2 < FALLBACK_QUERIES.size) SEARCH_PREFIX + (round + 1) else null
        return Page(out, next)
    }

    /** Home feed with fallbacks: personalised when signed in, generic otherwise / on failure. */
    fun feed(session: YtAccountStore.Session?, continuation: String? = null): Page<Video> {
        if (continuation != null && continuation.startsWith(SEARCH_PREFIX)) {
            return searchFeed(session, continuation)
        }
        val primary = runCatching { homeFeed(session, continuation) }.getOrNull()
        if (primary != null && primary.items.isNotEmpty()) return primary
        if (continuation != null) return primary ?: Page(emptyList(), null)
        // signed in but empty/rejected -> try again as a guest, then fall back to search
        if (session != null) {
            val guest = runCatching { homeFeed(null) }.getOrNull()
            if (guest != null && guest.items.isNotEmpty()) return guest
        }
        return searchFeed(session?.takeIf { primary != null })
    }

    private fun parseVideos(json: JSONObject): Page<Video> {
        val seen = HashSet<String>()
        val out = ArrayList<Video>()

        collect(json, "videoRenderer").forEach { vr ->
            val id = vr.optString("videoId")
            if (id.isEmpty() || !seen.add(id)) return@forEach
            val bylineObj = vr.optJSONObject("longBylineText")
                ?: vr.optJSONObject("ownerText") ?: vr.optJSONObject("shortBylineText")
            val chId = bylineObj?.optJSONArray("runs")?.optJSONObject(0)
                ?.optJSONObject("navigationEndpoint")?.optJSONObject("browseEndpoint")
                ?.optString("browseId")
            val avatar = vr.optJSONObject("channelThumbnailSupportedRenderers")
                ?.optJSONObject("channelThumbnailWithLinkRenderer")
                ?.optJSONObject("thumbnail")?.optJSONArray("thumbnails")
            out.add(
                Video(
                    id = id,
                    title = text(vr.optJSONObject("title")).orEmpty(),
                    channelName = text(bylineObj),
                    channelId = chId,
                    thumbnail = lastThumb(vr.optJSONObject("thumbnail")?.optJSONArray("thumbnails"))
                        ?: "https://i.ytimg.com/vi/$id/hqdefault.jpg",
                    durationSec = parseDuration(text(vr.optJSONObject("lengthText"))),
                    viewCount = parseCount(text(vr.optJSONObject("viewCountText"))),
                    published = text(vr.optJSONObject("publishedTimeText")),
                    avatar = firstThumb(avatar)
                )
            )
        }

        collect(json, "lockupViewModel").forEach { lv ->
            if (lv.optString("contentType") != "LOCKUP_CONTENT_TYPE_VIDEO") return@forEach
            val id = lv.optString("contentId")
            if (id.isEmpty() || !seen.add(id)) return@forEach
            val meta = lv.optJSONObject("metadata")?.optJSONObject("lockupMetadataViewModel")
            val parts = ArrayList<String>()
            val rows = meta?.optJSONObject("metadata")?.optJSONObject("contentMetadataViewModel")
                ?.optJSONArray("metadataRows")
            if (rows != null) for (r in 0 until rows.length()) {
                val ps = rows.optJSONObject(r)?.optJSONArray("metadataParts") ?: continue
                for (p in 0 until ps.length()) {
                    text(ps.optJSONObject(p)?.optJSONObject("text"))?.let { parts.add(it) }
                }
            }
            val channel = parts.getOrNull(0)
            val rest = parts.drop(1)
            val views = rest.firstOrNull { it.contains("view", true) || it.contains("watching", true) }
                ?: rest.getOrNull(0)
            val published = rest.firstOrNull { it != views }
            val duration = collect(lv, "thumbnailBadgeViewModel")
                .map { parseDuration(it.optString("text")) }.firstOrNull { it >= 0 } ?: -1L
            val chId = meta?.let { collect(it, "browseEndpoint") }
                ?.map { it.optString("browseId") }?.firstOrNull { it.startsWith("UC") }
            val thumbSources = lv.optJSONObject("contentImage")?.optJSONObject("thumbnailViewModel")
                ?.optJSONObject("image")?.optJSONArray("sources")
            val avatarSources = meta?.optJSONObject("image")?.optJSONObject("decoratedAvatarViewModel")
                ?.optJSONObject("avatar")?.optJSONObject("avatarViewModel")
                ?.optJSONObject("image")?.optJSONArray("sources")
            out.add(
                Video(
                    id = id,
                    title = text(meta?.optJSONObject("title")).orEmpty(),
                    channelName = channel,
                    channelId = chId,
                    thumbnail = lastThumb(thumbSources) ?: "https://i.ytimg.com/vi/$id/hqdefault.jpg",
                    durationSec = duration,
                    viewCount = parseCount(views),
                    published = published,
                    avatar = firstThumb(avatarSources)
                )
            )
        }
        return Page(out.filter { it.title.isNotBlank() }, continuationOf(json))
    }

    // ---------------------------------------------------------------- shorts

    /**
     * Shorts recommended by YouTube itself, in the order YouTube's own Shorts player would show
     * them (personalised when [session] is signed in, generic otherwise).
     *
     * First call ([continuation] == null): a seed short is picked ([seedId] if given, otherwise
     * the first short of YouTube's Shorts / home feed, otherwise a search), then YouTube's reel
     * sequence for that seed is requested. Every later call passes the returned continuation.
     * If the reel sequence is not available, the seed candidates themselves are returned, so the
     * list is never empty just because one endpoint changed.
     */
    fun shortsFeed(
        session: YtAccountStore.Session?,
        continuation: String? = null,
        seedId: String? = null
    ): Page<ShortItem> {
        if (continuation != null) {
            val json = post(
                "reel/reel_watch_sequence",
                JSONObject().put("sequenceParams", continuation),
                session
            )
            return Page(parseShorts(json), reelContinuation(json))
        }

        val candidates = ArrayList<ShortItem>()
        if (seedId != null) {
            candidates.add(ShortItem(seedId, null, "https://i.ytimg.com/vi/$seedId/hqdefault.jpg"))
        } else {
            candidates.addAll(shortsSeedCandidates(session))
        }
        val seed = candidates.firstOrNull() ?: return Page(emptyList(), null)

        val sequence = runCatching {
            val body = JSONObject()
                .put("disablePlayerResponse", true)
                .put("playerRequest", JSONObject().put("videoId", seed.id).put("params", "8AEB"))
                .put("params", "CAUwAg%3D%3D")
            val json = post("reel/reel_item_watch", body, session)
            val token = reelContinuation(json)
            if (token == null) {
                null
            } else {
                val more = post(
                    "reel/reel_watch_sequence",
                    JSONObject().put("sequenceParams", token),
                    session
                )
                Page(parseShorts(more), reelContinuation(more))
            }
        }.getOrNull()

        val out = LinkedHashMap<String, ShortItem>()
        out[seed.id] = seed
        sequence?.items?.forEach { if (!out.containsKey(it.id)) out[it.id] = it }
        if (sequence == null || sequence.items.isEmpty()) {
            candidates.forEach { if (!out.containsKey(it.id)) out[it.id] = it }
        }
        return Page(out.values.toList(), sequence?.continuation)
    }

    /** Shorts that YouTube itself puts in front of the user: Shorts page, home shelf, search. */
    private fun shortsSeedCandidates(session: YtAccountStore.Session?): List<ShortItem> {
        val sources = listOf<() -> JSONObject>(
            { browse(session, "FEshorts", null) },
            { browse(session, "FEwhat_to_watch", null) },
            { post("search", JSONObject().put("query", "#shorts"), session) }
        )
        for (source in sources) {
            val found = runCatching { parseShorts(source()) }.getOrDefault(emptyList())
            if (found.isNotEmpty()) return found
        }
        return emptyList()
    }

    private fun reelContinuation(json: JSONObject): String? {
        json.optString("sequenceContinuation").takeIf { it.isNotEmpty() }?.let { return it }
        return json.optJSONObject("continuationEndpoint")
            ?.optJSONObject("continuationCommand")
            ?.optString("token")?.takeIf { it.isNotEmpty() }
    }

    /** Collects every short of a reel / browse / search response (document order, no duplicates). */
    private fun parseShorts(json: JSONObject): List<ShortItem> {
        val seen = LinkedHashMap<String, ShortItem>()
        fun add(id: String?, title: String?, thumb: String?) {
            if (id.isNullOrEmpty() || id.length != 11 || seen.containsKey(id)) return
            seen[id] = ShortItem(id, title?.takeIf { it.isNotBlank() }, thumb ?: "https://i.ytimg.com/vi/$id/hqdefault.jpg")
        }

        // classic shelf item
        collect(json, "reelItemRenderer").forEach { r ->
            add(
                r.optString("videoId"),
                text(r.optJSONObject("headline")),
                lastThumb(r.optJSONObject("thumbnail")?.optJSONArray("thumbnails"))
            )
        }
        // newer shelf item
        collect(json, "shortsLockupViewModel").forEach { v ->
            val id = collect(v, "reelWatchEndpoint").firstOrNull()?.optString("videoId")
            val title = v.optJSONObject("overlayMetadata")?.optJSONObject("primaryText")
                ?.optString("content")
            val thumb = v.optJSONObject("thumbnail")?.optJSONArray("sources")?.let { lastThumb(it) }
            add(id, title, thumb)
        }
        // reel sequence entries / navigation endpoints
        collect(json, "reelWatchEndpoint").forEach { e ->
            add(
                e.optString("videoId"),
                null,
                lastThumb(e.optJSONObject("thumbnail")?.optJSONArray("thumbnails"))
            )
        }
        // search results: videos that are really shorts (short link or <= 60 s)
        collect(json, "videoRenderer").forEach { vr ->
            val id = vr.optString("videoId")
            val url = vr.optJSONObject("navigationEndpoint")?.optJSONObject("commandMetadata")
                ?.optJSONObject("webCommandMetadata")?.optString("url").orEmpty()
            val isShort = url.contains("/shorts/") ||
                (parseDuration(text(vr.optJSONObject("lengthText"))) in 1L..60L)
            if (isShort) {
                add(
                    id,
                    text(vr.optJSONObject("title")),
                    lastThumb(vr.optJSONObject("thumbnail")?.optJSONArray("thumbnails"))
                )
            }
        }
        return seen.values.toList()
    }

    // ---------------------------------------------------------------- subscriptions

    fun subscriptionsPage(session: YtAccountStore.Session, continuation: String? = null): Page<Channel> {
        val json = browse(session, "FEchannels", continuation)
        val seen = HashSet<String>()
        val out = ArrayList<Channel>()

        listOf("channelRenderer", "gridChannelRenderer").forEach { key ->
            collect(json, key).forEach { cr ->
                val id = cr.optString("channelId")
                if (!id.startsWith("UC") || !seen.add(id)) return@forEach
                out.add(
                    Channel(
                        id = id,
                        name = text(cr.optJSONObject("title")).orEmpty(),
                        avatar = firstThumb(cr.optJSONObject("thumbnail")?.optJSONArray("thumbnails"))
                    )
                )
            }
        }
        collect(json, "lockupViewModel").forEach { lv ->
            if (lv.optString("contentType") != "LOCKUP_CONTENT_TYPE_CHANNEL") return@forEach
            val id = lv.optString("contentId")
            if (!id.startsWith("UC") || !seen.add(id)) return@forEach
            val meta = lv.optJSONObject("metadata")?.optJSONObject("lockupMetadataViewModel")
            val avatarSources = lv.optJSONObject("contentImage")?.optJSONObject("decoratedAvatarViewModel")
                ?.optJSONObject("avatar")?.optJSONObject("avatarViewModel")
                ?.optJSONObject("image")?.optJSONArray("sources")
            out.add(Channel(id, text(meta?.optJSONObject("title")).orEmpty(), firstThumb(avatarSources)))
        }
        return Page(out.filter { it.name.isNotBlank() }, continuationOf(json))
    }

    // ---------------------------------------------------------------- likes & comments

    private fun allStrings(node: Any?, key: String, out: MutableList<String> = ArrayList()): List<String> {
        when (node) {
            is JSONObject -> {
                val keys = node.keys()
                while (keys.hasNext()) {
                    val k = keys.next()
                    val v = node.opt(k)
                    if (k == key && v is String && v.isNotEmpty()) out.add(v)
                    if (v is JSONObject || v is JSONArray) allStrings(v, key, out)
                }
            }
            is JSONArray -> for (i in 0 until node.length()) allStrings(node.opt(i), key, out)
        }
        return out
    }

    /** action: "like", "dislike" or "removelike". */
    fun rate(session: YtAccountStore.Session, videoId: String, action: String) {
        val body = JSONObject().put("target", JSONObject().put("videoId", videoId))
        post("like/$action", body, session)
    }

    /** "LIKE", "DISLIKE" or "INDIFFERENT"; null when it could not be determined. */
    fun likeStatus(session: YtAccountStore.Session, videoId: String): String? {
        val json = post("next", JSONObject().put("videoId", videoId), session)
        return allStrings(json, "likeStatus").firstOrNull { it in setOf("LIKE", "DISLIKE", "INDIFFERENT") }
    }

    private fun createCommentParams(session: YtAccountStore.Session, videoId: String): String? {
        val next = post("next", JSONObject().put("videoId", videoId), session)
        allStrings(next, "createCommentParams").firstOrNull()?.let { return it }

        val tokens = ArrayList<String>()
        collect(next, "itemSectionRenderer").forEach { sec ->
            val sid = sec.optString("sectionIdentifier")
            val tid = sec.optString("targetId")
            if (sid == "comment-item-section" || tid.contains("comments-section")) {
                allStrings(sec, "token").forEach { tokens.add(it) }
            }
        }
        if (tokens.isEmpty()) allStrings(next, "token").forEach { tokens.add(it) }

        for (t in tokens.take(4)) {
            val r = runCatching { post("next", JSONObject().put("continuation", t), session) }.getOrNull() ?: continue
            allStrings(r, "createCommentParams").firstOrNull()?.let { return it }
        }
        return null
    }

    fun postComment(session: YtAccountStore.Session, videoId: String, commentText: String) {
        val params = createCommentParams(session, videoId)
            ?: throw IOException("Comments are not available for this video")
        val body = JSONObject().put("createCommentParams", params).put("commentText", commentText)
        val r = post("comment/create_comment", body, session)
        if (r.has("error")) {
            throw IOException(r.optJSONObject("error")?.optString("message").orEmpty().ifBlank { "Comment was rejected" })
        }
    }

    // ---------------------------------------------------------------- comment actions

    /** Server-side action tokens of one comment. like/dislike = [toggle-on, toggle-off]. */
    class CommentActions(
        val like: List<String>,
        val dislike: List<String>,
        val replyParams: String?,
        val editParams: String? = null
    )

    private val commentActionCache = HashMap<String, CommentActions>()

    private fun commentTokens(next: JSONObject): List<String> {
        val tokens = ArrayList<String>()
        collect(next, "itemSectionRenderer").forEach { sec ->
            if (sec.optString("sectionIdentifier") == "comment-item-section" ||
                sec.optString("targetId").contains("comments-section")
            ) allStrings(sec, "token").forEach { tokens.add(it) }
        }
        if (tokens.isEmpty()) allStrings(next, "token").forEach { tokens.add(it) }
        return tokens.take(4)
    }

    private fun actionsOf(node: JSONObject, key: String): List<String> {
        val sub = collect(node, key).firstOrNull() ?: return emptyList()
        return collect(sub, "performCommentActionEndpoint")
            .mapNotNull { it.optString("action").takeIf { a -> a.isNotEmpty() } }
    }

    /** True if the base64 token (url-safe or not) contains [needle] in its decoded bytes. */
    private fun tokenMentions(token: String, needle: String): Boolean = runCatching {
        val fixed = token.replace("%3D", "=").replace('-', '+').replace('_', '/')
        String(android.util.Base64.decode(fixed, android.util.Base64.DEFAULT), Charsets.ISO_8859_1)
            .contains(needle)
    }.getOrDefault(false)

    private fun extractActions(page: JSONObject, commentId: String): CommentActions? {
        // 1) the thread that contains the comment id (classic and view-model layouts)
        val threads = collect(page, "commentThreadRenderer")
        for (t in threads) {
            if (!t.toString().contains("\"$commentId\"")) continue
            val like = actionsOf(t, "likeButton")
            val dislike = actionsOf(t, "dislikeButton")
            val reply = allStrings(t, "createReplyParams").firstOrNull()
            val edit = allStrings(t, "updateCommentParams").firstOrNull()
            if (like.isNotEmpty() || dislike.isNotEmpty() || reply != null) {
                return CommentActions(like, dislike, reply, edit)
            }
        }
        // 2) fallback: tokens that embed the comment id, in document order
        val mine = collect(page, "performCommentActionEndpoint")
            .mapNotNull { it.optString("action").takeIf { a -> a.isNotEmpty() } }
            .filter { tokenMentions(it, commentId) }.distinct()
        val reply = allStrings(page, "createReplyParams").firstOrNull { tokenMentions(it, commentId) }
        val edit = allStrings(page, "updateCommentParams").firstOrNull { tokenMentions(it, commentId) }
        if (mine.isEmpty() && reply == null) return null
        val half = (mine.size + 1) / 2
        return CommentActions(mine.take(half), mine.drop(half), reply, edit)
    }

    private fun nextPageToken(page: JSONObject): String? =
        collect(page, "continuationItemRenderer").lastOrNull()
            ?.let { allStrings(it, "token").firstOrNull() }

    private class PageState {
        val pages = ArrayList<JSONObject>()
        var next: String? = null
        var started = false
    }

    private val pageStates = HashMap<String, PageState>()

    /**
     * Looks the comment up in the signed-in comment pages (top comments, max 8 new pages per
     * call). Fetched pages are kept for the current video, so the next like / reply on another
     * comment of the same video usually needs no network request just to find its tokens.
     */
    @Synchronized
    private fun findCommentActions(
        session: YtAccountStore.Session, videoId: String, commentId: String
    ): CommentActions {
        commentActionCache[commentId]?.let { return it }
        if (pageStates.size > 1 || (pageStates.size == 1 && !pageStates.containsKey(videoId))) {
            pageStates.clear() // keep only the video that is being watched
        }
        val st = pageStates.getOrPut(videoId) { PageState() }
        for (p in st.pages) {
            extractActions(p, commentId)?.let { commentActionCache[commentId] = it; return it }
        }
        if (!st.started) {
            val next = post("next", JSONObject().put("videoId", videoId), session)
            for (t in commentTokens(next)) {
                val r = runCatching { post("next", JSONObject().put("continuation", t), session) }
                    .getOrNull() ?: continue
                if (collect(r, "commentThreadRenderer").isNotEmpty()) {
                    st.started = true
                    st.pages.add(r)
                    st.next = nextPageToken(r)
                    extractActions(r, commentId)?.let { commentActionCache[commentId] = it; return it }
                    break
                }
            }
        }
        var fetched = 0
        while (fetched < 8) {
            val token = st.next ?: break
            val page = post("next", JSONObject().put("continuation", token), session)
            st.pages.add(page)
            st.next = nextPageToken(page)
            fetched++
            extractActions(page, commentId)?.let { commentActionCache[commentId] = it; return it }
        }
        throw IOException("Could not find this comment on YouTube")
    }

    private fun checkError(r: JSONObject) {
        if (r.has("error")) {
            throw IOException(r.optJSONObject("error")?.optString("message").orEmpty()
                .ifBlank { "YouTube rejected the request" })
        }
    }

    /** kind: "like", "unlike", "dislike" or "undislike". */
    fun commentAction(session: YtAccountStore.Session, videoId: String, commentId: String, kind: String) {
        val a = findCommentActions(session, videoId, commentId)
        val action = when (kind) {
            "like" -> a.like.getOrNull(0)
            "unlike" -> a.like.getOrNull(1)
            "dislike" -> a.dislike.getOrNull(0)
            else -> a.dislike.getOrNull(1)
        } ?: throw IOException("This action is not available for this comment")
        checkError(post("comment/perform_comment_action",
            JSONObject().put("actions", JSONArray().put(action)), session))
    }

    fun replyToComment(session: YtAccountStore.Session, videoId: String, commentId: String, text: String) {
        val params = findCommentActions(session, videoId, commentId).replyParams
            ?: throw IOException("Replying is not available for this comment")
        checkError(post("comment/create_comment_reply",
            JSONObject().put("createReplyParams", params).put("commentText", text), session))
    }

    /** The comment count text from the video page ("1.9K"), or null. */
    fun commentCountText(videoId: String): String? {
        val next = post("next", JSONObject().put("videoId", videoId), null)
        val entry = collect(next, "commentsEntryPointHeaderRenderer").firstOrNull()
        val count = entry?.optJSONObject("commentCount")
        return text(count)?.trim()?.takeIf { it.isNotEmpty() }
    }

    /** Edits one of the signed-in user's own comments. */
    fun editComment(session: YtAccountStore.Session, videoId: String, commentId: String, text: String) {
        commentActionCache.remove(commentId) // always use fresh tokens for an edit
        val params = findCommentActions(session, videoId, commentId).editParams
            ?: throw IOException("Only your own comments can be edited")
        checkError(post("comment/update_comment",
            JSONObject().put("updateCommentParams", params).put("commentText", text), session))
    }
}
