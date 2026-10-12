package org.schabi.newpipe.account

import android.app.Activity
import android.view.Menu
import android.view.MenuItem
import android.widget.ImageButton
import android.widget.ImageView
import androidx.core.util.Consumer
import org.schabi.newpipe.R

/**
 * Tonal buttons in the top bar: a wide search pill, settings, and the account button
 * (Google photo once signed in) on the far right.
 */
object YtToolbarButtons {
    private var profileRefreshTried = false

    @JvmStatic
    fun bind(activity: Activity, menu: Menu, onSelect: Consumer<MenuItem>) {
        listOf(R.id.action_search, R.id.action_settings).forEach { id ->
            val item = menu.findItem(id) ?: return@forEach
            val view = item.actionView ?: return@forEach
            // the settings button is an ImageButton, the search one is a pill (text + icon)
            val button = view.findViewById<android.view.View>(R.id.menu_action_button)
            button?.apply {
                (this as? ImageButton)?.setImageDrawable(item.icon)
                contentDescription = item.title
                setOnClickListener { onSelect.accept(item) }
            }
            // the empty space around the button should work as a tap target too
            view.setOnClickListener { onSelect.accept(item) }
        }

        val account = menu.findItem(R.id.action_account) ?: return
        val view = account.actionView ?: return
        val image = view.findViewById<ImageView>(R.id.account_avatar) ?: return
        val open = android.view.View.OnClickListener { YtAccountDialogs.show(activity) }
        view.setOnClickListener(open)
        view.findViewById<android.view.View>(R.id.account_button)?.setOnClickListener(open)
        image.setOnClickListener(open)

        val session = YtAccountStore.load(activity)
        if (session == null) {
            // signed out: the same tonal circle as the settings button, with a person icon
            val pad = (11 * activity.resources.displayMetrics.density).toInt()
            image.setPadding(pad, pad, pad, pad)
            image.scaleType = ImageView.ScaleType.CENTER_INSIDE
            image.setImageResource(R.drawable.ic_person)
            return
        }

        // signed in: the Google photo fills the whole 44dp circle (same size as settings)
        image.setPadding(0, 0, 0, 0)
        image.scaleType = ImageView.ScaleType.CENTER_CROP
        val label = session.name.ifBlank { session.email }
        val cached = if (session.avatar.isBlank()) null else YtAvatar.cached(activity, session.avatar)
        if (cached != null) {
            image.setImageBitmap(cached)
        } else {
            // until the photo is there: the first letter of the name, never a grey box
            image.setImageDrawable(InitialAvatarDrawable(label))
            if (session.avatar.isNotBlank()) {
                YtAvatar.fetch(activity, session.avatar).subscribe(
                    { bitmap -> image.setImageBitmap(bitmap) },
                    { }
                )
            }
        }

        if (session.avatar.isBlank() && !profileRefreshTried) {
            // signed in before photos were stored (or the lookup failed): fetch it once
            profileRefreshTried = true
            YtAccountActions.refreshProfile(activity).subscribe(
                { changed -> if (changed) activity.invalidateOptionsMenu() },
                { }
            )
        }
    }
}
