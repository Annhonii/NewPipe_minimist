package org.schabi.newpipe.util;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Build;

import androidx.annotation.NonNull;
import androidx.annotation.StyleRes;
import androidx.preference.PreferenceManager;

import org.schabi.newpipe.R;

/**
 * ArchiveTune-style customisation: accent colour (presets or Material You wallpaper colours)
 * and the option to disable animations. Applied as theme overlays on top of the base theme.
 */
public final class AppearanceHelper {
    private AppearanceHelper() { }

    public static boolean isDynamicColorSupported() {
        return Build.VERSION.SDK_INT >= Build.VERSION_CODES.S;
    }

    /** Applies accent + animation overlays and the font overlay to the context's theme. */
    public static void applyOverlays(@NonNull final Context context) {
        final SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(context);
        final Context app = context.getApplicationContext();

        final boolean dynamic = isDynamicColorSupported()
                && prefs.getBoolean(app.getString(R.string.dynamic_color_key), false);
        final int accent = dynamic
                ? R.style.ThemeOverlay_Accent_Dynamic
                : accentStyle(prefs.getString(app.getString(R.string.accent_color_key),
                        app.getString(R.string.default_accent_color_value)));
        context.getTheme().applyStyle(accent, true);

        if (prefs.getBoolean(app.getString(R.string.disable_animations_key), false)) {
            context.getTheme().applyStyle(R.style.ThemeOverlay_App_NoAnimations, true);
        }
    }

    @StyleRes
    private static int accentStyle(final String key) {
        if (key == null) {
            return R.style.ThemeOverlay_Accent_Sage;
        }
        switch (key) {
            case "red":
                return R.style.ThemeOverlay_Accent_Red;
            case "blue":
                return R.style.ThemeOverlay_Accent_Blue;
            case "purple":
                return R.style.ThemeOverlay_Accent_Purple;
            case "pink":
                return R.style.ThemeOverlay_Accent_Pink;
            case "orange":
                return R.style.ThemeOverlay_Accent_Orange;
            case "teal":
                return R.style.ThemeOverlay_Accent_Teal;
            case "amber":
                return R.style.ThemeOverlay_Accent_Amber;
            default:
                return R.style.ThemeOverlay_Accent_Sage;
        }
    }
}
