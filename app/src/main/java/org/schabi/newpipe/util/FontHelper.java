package org.schabi.newpipe.util;

import android.content.Context;
import android.graphics.Typeface;
import android.view.View;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.widget.Toolbar;

/** Optional Ntype82 font for screen titles (the app otherwise uses the device font). */
public final class FontHelper {
    private static final String[] TITLE_FONT_FILES = {"fonts/none"};
    @Nullable
    private static Typeface titleTypeface;
    private static boolean titleTypefaceLoaded = false;

    private static final boolean USE_TITLE_FONT = false;

    private FontHelper() { }

    @Nullable
    public static synchronized Typeface getTitleTypeface(@NonNull final Context context) {
        if (!titleTypefaceLoaded) {
            titleTypefaceLoaded = true;
            for (final String file : TITLE_FONT_FILES) {
                try {
                    titleTypeface = Typeface.createFromAsset(
                            context.getApplicationContext().getAssets(), file);
                    break;
                } catch (final Exception ignored) {
                    // font file not present
                }
            }
        }
        return titleTypeface;
    }

    /** Makes the toolbar title (on every screen) use Ntype82 whenever it changes. */
    public static void installToolbarTitleFont(@NonNull final Toolbar toolbar) {
        if (!USE_TITLE_FONT) {
            return; // ArchiveTune look: titles use the normal app font
        }
        final Typeface face = getTitleTypeface(toolbar.getContext());
        if (face == null) {
            return;
        }
        final Runnable apply = () -> {
            for (int i = 0; i < toolbar.getChildCount(); i++) {
                final View child = toolbar.getChildAt(i);
                if (child instanceof TextView && toolbar.getTitle() != null
                        && toolbar.getTitle().toString()
                        .contentEquals(((TextView) child).getText())) {
                    final TextView title = (TextView) child;
                    if (title.getTypeface() != face) {
                        title.setTypeface(face);
                    }
                }
            }
        };
        toolbar.addOnLayoutChangeListener((v, l, t, r, b, ol, ot, or, ob) -> apply.run());
        toolbar.post(apply);
    }
}
