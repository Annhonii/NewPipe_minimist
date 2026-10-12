package org.schabi.newpipe.account

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.BitmapShader
import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.Shader
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import io.reactivex.rxjava3.android.schedulers.AndroidSchedulers
import io.reactivex.rxjava3.core.Single
import io.reactivex.rxjava3.schedulers.Schedulers
import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit
import okhttp3.OkHttpClient
import okhttp3.Request

/**
 * The Google account photo shown in the top bar.
 *
 * The photo is downloaded once, cropped to a circle and kept on disk, so the top bar shows it
 * instantly on every start and never falls back to a grey placeholder square. While there is
 * no photo (not downloaded yet, or the download failed) [InitialAvatarDrawable] is shown.
 */
object YtAvatar {
    private const val SIZE_PX = 256
    private const val FILE_PREFIX = "yt_avatar_"

    private val http = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .followRedirects(true)
        .build()

    /** Google photo urls end in "=s88-c-k-..." : ask for a sharper size instead. */
    fun sharpUrl(url: String): String {
        val fixed = if (url.startsWith("//")) "https:$url" else url
        return Regex("=s\\d+(-[^/?=]*)?$").replace(fixed, "=s$SIZE_PX-c")
            .let { if (it == fixed && fixed.contains("googleusercontent.com") && !fixed.contains("=")) "$fixed=s$SIZE_PX-c" else it }
    }

    private fun fileFor(context: Context, url: String) =
        File(context.filesDir, FILE_PREFIX + sharpUrl(url).hashCode().toString(16) + ".png")

    /** The photo from disk if it was downloaded before, else null. */
    fun cached(context: Context, url: String): Bitmap? {
        val file = fileFor(context, url)
        if (!file.exists()) return null
        return runCatching { BitmapFactory.decodeFile(file.path) }.getOrNull()
            .also { if (it == null) file.delete() }
    }

    /** Downloads the photo as a circle and stores it. Runs on io, result delivered on main. */
    fun fetch(context: Context, url: String): Single<Bitmap> {
        val app = context.applicationContext
        return Single.fromCallable {
            val request = Request.Builder().url(sharpUrl(url)).header("User-Agent", "Mozilla/5.0").build()
            val raw = http.newCall(request).execute().use { resp ->
                if (!resp.isSuccessful) throw IOException("Photo request failed: HTTP ${resp.code}")
                val bytes = resp.body?.bytes() ?: throw IOException("Empty photo")
                BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                    ?: throw IOException("Photo can not be decoded")
            }
            val circle = circleCrop(raw)
            // forget older photos, keep the new one
            app.filesDir.listFiles { f -> f.name.startsWith(FILE_PREFIX) }?.forEach { it.delete() }
            runCatching {
                fileFor(app, url).outputStream().use { circle.compress(Bitmap.CompressFormat.PNG, 100, it) }
            }
            circle
        }.subscribeOn(Schedulers.io()).observeOn(AndroidSchedulers.mainThread())
    }

    /** Shows the signed-in account photo in [view] (placeholder when signed out). */
    @JvmStatic
    fun loadInto(view: android.widget.ImageView): io.reactivex.rxjava3.disposables.Disposable? {
        val ctx = view.context
        val session = YtAccountStore.load(ctx)
        if (session == null || session.avatar.isBlank()) {
            view.setImageResource(org.schabi.newpipe.R.drawable.placeholder_person)
            return null
        }
        cached(ctx, session.avatar)?.let { view.setImageBitmap(it); return null }
        view.setImageDrawable(InitialAvatarDrawable(session.name.ifBlank { session.email }))
        return fetch(ctx, session.avatar).subscribe({ view.setImageBitmap(it) }, { })
    }

    /** Removes the stored photo, e.g. on sign out. */
    fun clear(context: Context) {
        context.filesDir.listFiles { f -> f.name.startsWith(FILE_PREFIX) }?.forEach { it.delete() }
    }

    private fun circleCrop(src: Bitmap): Bitmap {
        val side = minOf(src.width, src.height)
        val square = Bitmap.createBitmap(
            src, (src.width - side) / 2, (src.height - side) / 2, side, side
        )
        val scaled = Bitmap.createScaledBitmap(square, SIZE_PX, SIZE_PX, true)
        val out = Bitmap.createBitmap(SIZE_PX, SIZE_PX, Bitmap.Config.ARGB_8888)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            shader = BitmapShader(scaled, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP)
        }
        Canvas(out).drawCircle(SIZE_PX / 2f, SIZE_PX / 2f, SIZE_PX / 2f, paint)
        return out
    }
}

/**
 * Round avatar with the first letter of the account name on a soft colour that is the same for
 * the same name every time (like Google's own default avatars).
 */
class InitialAvatarDrawable(label: String) : Drawable() {
    private val letter = label.trim().firstOrNull { it.isLetterOrDigit() }
        ?.uppercaseChar()?.toString() ?: "?"
    private val circle = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = PALETTE[Math.floorMod(label.trim().lowercase().hashCode(), PALETTE.size)]
    }
    private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFFFFFFFF.toInt()
        textAlign = Paint.Align.CENTER
        typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
    }

    override fun draw(canvas: Canvas) {
        val b: Rect = bounds
        val r = minOf(b.width(), b.height()) / 2f
        canvas.drawCircle(b.exactCenterX(), b.exactCenterY(), r, circle)
        text.textSize = r * 0.95f
        val y = b.exactCenterY() - (text.descent() + text.ascent()) / 2f
        canvas.drawText(letter, b.exactCenterX(), y, text)
    }

    override fun setAlpha(alpha: Int) {
        circle.alpha = alpha
        text.alpha = alpha
    }

    override fun setColorFilter(colorFilter: ColorFilter?) {
        circle.colorFilter = colorFilter
        text.colorFilter = colorFilter
    }

    @Deprecated("Deprecated in Java")
    override fun getOpacity() = PixelFormat.TRANSLUCENT

    private companion object {
        val PALETTE = intArrayOf(
            0xFF5C6BC0.toInt(), 0xFF26A69A.toInt(), 0xFFEF5350.toInt(), 0xFFAB47BC.toInt(),
            0xFF42A5F5.toInt(), 0xFFFF7043.toInt(), 0xFF66BB6A.toInt(), 0xFFEC407A.toInt()
        )
    }
}
