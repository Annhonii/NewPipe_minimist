package org.schabi.newpipe.account

import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.PersistableBundle
import android.text.InputType
import android.view.Gravity
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import org.schabi.newpipe.R

/** Account popups opened from the account button in the top bar. */
object YtAccountDialogs {

    @JvmStatic
    fun show(activity: Activity) {
        val session = YtAccountStore.load(activity)
        if (session == null) showSignedOut(activity) else showSignedIn(activity, session)
    }

    private fun showSignedOut(activity: Activity) {
        val items = arrayOf(
            activity.getString(R.string.yt_login_web_title),
            activity.getString(R.string.yt_login_token_title)
        )
        AlertDialog.Builder(activity)
            .setTitle(R.string.yt_account_title)
            .setItems(items) { _, which ->
                when (which) {
                    0 -> activity.startActivity(Intent(activity, YtLoginActivity::class.java))
                    else -> showTokenDialog(activity) { activity.invalidateOptionsMenu() }
                }
            }
            .show()
    }

    private fun showSignedIn(activity: Activity, session: YtAccountStore.Session) {
        val title = listOf(session.name, session.email).filter { it.isNotBlank() }
            .joinToString("\n").ifBlank { activity.getString(R.string.yt_status_signed_in) }
        val items = arrayOf(
            activity.getString(R.string.yt_copy_token_title),
            activity.getString(R.string.yt_sync_title),
            activity.getString(R.string.yt_logout_title)
        )
        AlertDialog.Builder(activity)
            .setTitle(title)
            .setItems(items) { _, which ->
                when (which) {
                    0 -> copyToken(activity)
                    1 -> YtAccountActions.syncWithToasts(activity)
                    else -> confirmSignOut(activity)
                }
            }
            .show()
    }

    private fun confirmSignOut(activity: Activity) {
        AlertDialog.Builder(activity)
            .setTitle(R.string.yt_logout_title)
            .setMessage(R.string.yt_logout_message)
            .setPositiveButton(R.string.yt_logout_title) { _, _ ->
                YtAccountStore.clear(activity)
                YtAvatar.clear(activity)
                activity.invalidateOptionsMenu()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    /** Paste-a-token login. [onLoggedIn] runs once the session has been saved. */
    @JvmStatic
    fun showTokenDialog(context: Context, onLoggedIn: Runnable?) {
        val input = EditText(context).apply {
            hint = context.getString(R.string.yt_token_hint)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE
            minLines = 5
            maxLines = 12
            gravity = Gravity.TOP or Gravity.START
        }
        val pad = (20 * context.resources.displayMetrics.density).toInt()
        val holder = FrameLayout(context).apply {
            setPadding(pad, pad / 2, pad, 0)
            addView(input)
        }
        AlertDialog.Builder(context)
            .setTitle(R.string.yt_login_token_title)
            .setMessage(R.string.yt_token_message)
            .setView(holder)
            .setPositiveButton(R.string.yt_login_action) { _, _ ->
                val session = YtAccountStore.parseTokenText(input.text.toString())
                if (session == null) {
                    Toast.makeText(context, R.string.yt_token_invalid, Toast.LENGTH_LONG).show()
                } else {
                    YtAccountActions.loginAndSync(
                        context, session,
                        onLoggedIn = { onLoggedIn?.run() },
                        onLoginFailed = {
                            Toast.makeText(context, it.message ?: "Login failed", Toast.LENGTH_LONG).show()
                        }
                    )
                }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    /**
     * Copies the signed-in session as a token text that the "Sign in with token" option (and
     * Metrolist) understands. It gives full access to the account, so it is flagged sensitive.
     */
    @JvmStatic
    fun copyToken(context: Context) {
        val s = YtAccountStore.load(context)
        if (s == null) {
            Toast.makeText(context, R.string.yt_sign_in_required, Toast.LENGTH_LONG).show()
            return
        }
        val text = buildString {
            append("***INNERTUBE COOKIE*** =").append(s.cookie).append('\n')
            append("***VISITOR DATA*** =").append(s.visitorData).append('\n')
            append("***DATASYNC ID*** =").append(s.dataSyncId).append('\n')
            append("***AUTH USER*** =").append(s.authUser).append('\n')
            append("***ACCOUNT NAME*** =").append(s.name).append('\n')
            append("***ACCOUNT EMAIL*** =").append(s.email)
        }
        val clip = ClipData.newPlainText("YouTube token", text)
        if (Build.VERSION.SDK_INT >= 33) {
            clip.description.extras = PersistableBundle().apply {
                putBoolean("android.content.extra.IS_SENSITIVE", true)
            }
        }
        (context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(clip)
        Toast.makeText(context, R.string.yt_token_copied, Toast.LENGTH_LONG).show()
    }
}
