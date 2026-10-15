package org.schabi.newpipe.util;

import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.Build;
import android.os.PowerManager;
import android.provider.Settings;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AlertDialog;
import androidx.preference.PreferenceManager;

import org.schabi.newpipe.R;

import java.util.Locale;

/**
 * Helps downloads survive aggressive battery managers (MIUI/HyperOS, ColorOS, OneUI...):
 * asks to be exempt from battery optimisation and points to the "autostart" screen of the phone.
 */
public final class KeepAlive {
    private static final String PREF_PROMPTS = "keep_alive_prompt_count";
    private static final int MAX_PROMPTS = 3;
    private static boolean shownThisSession = false;

    private KeepAlive() { }

    public static boolean isIgnoringBatteryOptimizations(@NonNull final Context context) {
        final PowerManager pm = (PowerManager) context.getSystemService(Context.POWER_SERVICE);
        return pm == null || pm.isIgnoringBatteryOptimizations(context.getPackageName());
    }

    private static boolean isXiaomi() {
        final String maker = Build.MANUFACTURER == null ? "" : Build.MANUFACTURER.toLowerCase(Locale.ROOT);
        return maker.contains("xiaomi") || maker.contains("redmi") || maker.contains("poco");
    }

    /**
     * Asks once in a while (at most {@value #MAX_PROMPTS} times, once per app run) to let the app
     * run unrestricted in the background. Does nothing if that is already allowed.
     */
    public static void maybePrompt(@NonNull final Activity activity) {
        if (shownThisSession || activity.isFinishing() || isIgnoringBatteryOptimizations(activity)) {
            return;
        }

        final SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(activity);
        final int count = prefs.getInt(PREF_PROMPTS, 0);
        if (count >= MAX_PROMPTS) {
            return;
        }
        prefs.edit().putInt(PREF_PROMPTS, count + 1).apply();
        shownThisSession = true;

        final AlertDialog.Builder builder = new AlertDialog.Builder(activity)
                .setTitle(R.string.keep_alive_title)
                .setMessage(isXiaomi() ? R.string.keep_alive_message_xiaomi : R.string.keep_alive_message)
                .setPositiveButton(R.string.keep_alive_allow,
                        (d, w) -> requestIgnoreBatteryOptimizations(activity))
                .setNegativeButton(R.string.keep_alive_later, null);

        if (isXiaomi()) {
            builder.setNeutralButton(R.string.keep_alive_autostart, (d, w) -> openAutostart(activity));
        }

        builder.show();
    }

    private static void requestIgnoreBatteryOptimizations(@NonNull final Activity activity) {
        try {
            activity.startActivity(new Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                    Uri.parse("package:" + activity.getPackageName())));
        } catch (final ActivityNotFoundException | SecurityException e) {
            openAppSettings(activity);
        }
    }

    private static void openAutostart(@NonNull final Activity activity) {
        try {
            activity.startActivity(new Intent().setComponent(new ComponentName("com.miui.securitycenter",
                    "com.miui.permcenter.autostart.AutoStartManagementActivity")));
        } catch (final ActivityNotFoundException | SecurityException e) {
            openAppSettings(activity);
        }
    }

    private static void openAppSettings(@NonNull final Activity activity) {
        try {
            activity.startActivity(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                    Uri.parse("package:" + activity.getPackageName())));
        } catch (final ActivityNotFoundException ignored) {
            // nothing else we can do
        }
    }
}
