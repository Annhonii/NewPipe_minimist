package org.schabi.newpipe.account

import android.app.Activity
import android.view.Menu
import android.view.MenuItem
import android.widget.ImageButton
import android.widget.ImageView
import androidx.core.util.Consumer
import org.schabi.newpipe.R
import org.schabi.newpipe.util.image.CoilHelper

/**
 * Round tonal buttons in the top bar: search, the account button (Google photo once signed in)
 * and settings on the far right.
 */
object YtToolbarButtons {
    private var profileRefreshTried = false

    @JvmStatic
    fun bind(activity: Activity, menu: Menu, onSelect: Consumer<MenuItem>) {
        listOf(R.id.action_search, R.id.action_settings).forEach { id ->
            val item = menu.findItem(id) ?: return@forEach
            val view = item.actionView ?: return@forEach
            view.findViewById<ImageButton>(R.id.menu_action_button)?.apply {
                setImageDrawable(item.icon)
                contentDescription = item.title
                setOnClickListener { onSelect.accept(item) }
            }
        }

        val account = menu.findItem(R.id.action_account) ?: return
        val view = account.actionView ?: return
        val image = view.findViewById<ImageView>(R.id.account_avatar) ?: return
        val open = android.view.View.OnClickListener { YtAccountDialogs.show(activity) }
        view.setOnClickListener(open)
        image.setOnClickListener(open)

        val session = YtAccountStore.load(activity)
        if (session != null && session.avatar.isNotBlank()) {
            image.setPadding(0, 0, 0, 0)
            image.scaleType = ImageView.ScaleType.CENTER_CROP
            CoilHelper.loadAvatar(image, session.avatar)
        } else {
            val pad = (11 * activity.resources.displayMetrics.density).toInt()
            image.setPadding(pad, pad, pad, pad)
            image.scaleType = ImageView.ScaleType.CENTER_INSIDE
            image.setImageResource(R.drawable.ic_person)
            if (session != null && !profileRefreshTried) {
                // signed in before photos were stored: fetch it once
                profileRefreshTried = true
                YtAccountActions.refreshProfile(activity).subscribe(
                    { changed -> if (changed) activity.invalidateOptionsMenu() },
                    { }
                )
            }
        }
    }
}
