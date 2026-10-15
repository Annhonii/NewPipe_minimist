package org.schabi.newpipe.settings

import android.content.Intent
import android.os.Bundle
import android.text.InputType
import android.view.Gravity
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import io.reactivex.rxjava3.disposables.CompositeDisposable
import org.schabi.newpipe.R
import org.schabi.newpipe.account.YtAccountActions
import org.schabi.newpipe.account.YtAccountStore
import org.schabi.newpipe.account.YtLoginActivity

class GoogleAccountSettingsFragment : BasePreferenceFragment() {
    private val disposables = CompositeDisposable()

    override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
        addPreferencesFromResourceRegistry()

        requirePreference<androidx.preference.Preference>(R.string.yt_login_web_key)
            .setOnPreferenceClickListener {
                startActivity(Intent(requireContext(), YtLoginActivity::class.java))
                true
            }
        requirePreference<androidx.preference.Preference>(R.string.yt_login_token_key)
            .setOnPreferenceClickListener {
                showTokenDialog()
                true
            }
        requirePreference<androidx.preference.Preference>(R.string.yt_copy_token_key)
            .setOnPreferenceClickListener {
                org.schabi.newpipe.account.YtAccountDialogs.copyToken(requireContext())
                true
            }
        requirePreference<androidx.preference.Preference>(R.string.yt_sync_key)
            .setOnPreferenceClickListener {
                runSync()
                true
            }
        requirePreference<androidx.preference.Preference>(R.string.yt_logout_key)
            .setOnPreferenceClickListener {
                AlertDialog.Builder(requireContext())
                    .setTitle(R.string.yt_logout_title)
                    .setMessage(R.string.yt_logout_message)
                    .setPositiveButton(R.string.yt_logout_title) { _, _ ->
                        YtAccountStore.clear(requireContext())
                        refreshState()
                    }
                    .setNegativeButton(android.R.string.cancel, null)
                    .show()
                true
            }
    }

    override fun onResume() {
        super.onResume()
        refreshState()
    }

    override fun onDestroy() {
        disposables.clear()
        super.onDestroy()
    }

    private fun refreshState() {
        val ctx = context ?: return
        val session = YtAccountStore.load(ctx)
        val loggedIn = session != null
        requirePreference<androidx.preference.Preference>(R.string.yt_status_key).summary = when {
            !loggedIn -> getString(R.string.yt_status_signed_out)
            session!!.name.isNotBlank() || session.email.isNotBlank() ->
                getString(
                    R.string.yt_status_signed_in_as,
                    listOf(session.name, session.email).filter { it.isNotBlank() }.joinToString(" • ")
                )
            else -> getString(R.string.yt_status_signed_in)
        }
        requirePreference<androidx.preference.Preference>(R.string.yt_copy_token_key).isEnabled = loggedIn
        requirePreference<androidx.preference.Preference>(R.string.yt_sync_key).isEnabled = loggedIn
        requirePreference<androidx.preference.Preference>(R.string.yt_logout_key).isEnabled = loggedIn
    }

    private fun runSync() {
        Toast.makeText(requireContext(), R.string.yt_syncing, Toast.LENGTH_SHORT).show()
        val app = requireContext().applicationContext
        disposables.add(
            YtAccountActions.syncSubscriptions(app).subscribe(
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
        )
    }

    private fun showTokenDialog() {
        val ctx = requireContext()
        val input = EditText(ctx).apply {
            hint = getString(R.string.yt_token_hint)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE
            minLines = 5
            maxLines = 12
            gravity = Gravity.TOP or Gravity.START
        }
        val pad = (20 * resources.displayMetrics.density).toInt()
        val holder = FrameLayout(ctx).apply {
            setPadding(pad, pad / 2, pad, 0)
            addView(input)
        }
        AlertDialog.Builder(ctx)
            .setTitle(R.string.yt_login_token_title)
            .setMessage(R.string.yt_token_message)
            .setView(holder)
            .setPositiveButton(R.string.yt_login_action) { _, _ ->
                val session = YtAccountStore.parseTokenText(input.text.toString())
                if (session == null) {
                    Toast.makeText(ctx, R.string.yt_token_invalid, Toast.LENGTH_LONG).show()
                } else {
                    YtAccountActions.loginAndSync(
                        ctx, session,
                        onLoggedIn = { refreshState() },
                        onLoginFailed = {
                            Toast.makeText(ctx, it.message ?: "Login failed", Toast.LENGTH_LONG).show()
                        }
                    )
                }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }
}
