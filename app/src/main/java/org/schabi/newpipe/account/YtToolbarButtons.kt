package org.schabi.newpipe.account

import android.app.Activity
import android.view.Menu
import android.view.MenuItem
import android.widget.ImageButton
import androidx.core.util.Consumer
import org.schabi.newpipe.R

/**
 * Tonal buttons in the top bar: a wide search pill and the settings button
 * (the settings button is only visible on the History tab, see MainFragment).
 */
object YtToolbarButtons {

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
    }
}
