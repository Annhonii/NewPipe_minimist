package org.schabi.newpipe.views;

import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Typeface;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.animation.Interpolator;
import android.view.animation.PathInterpolator;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.annotation.DrawableRes;
import androidx.annotation.NonNull;
import androidx.appcompat.content.res.AppCompatResources;
import androidx.core.content.res.ResourcesCompat;

import org.schabi.newpipe.R;

/**
 * Custom tab content for the floating nav pill: an icon that lifts up and a small label that
 * rises in underneath it when the tab becomes selected (and sinks back when it is left).
 * The tab keeps its size the whole time, so the sliding indicator of the TabLayout is
 * never disturbed by the animation.
 */
public class NavTabView extends FrameLayout {
    private static final float ICON_SIZE_DP = 22f;
    private static final float ICON_LIFT_DP = 7f;
    private static final float LABEL_DROP_DP = 13f;
    private static final float LABEL_SINK_DP = 4f;
    private static final long DURATION_MS = 300L;
    private static final Interpolator EMPHASIZED =
            new PathInterpolator(0.05f, 0.7f, 0.1f, 1f);

    private final ImageView icon;
    private final TextView label;
    private final float density;
    private boolean selectedState;

    public NavTabView(@NonNull final Context context) {
        super(context);
        density = context.getResources().getDisplayMetrics().density;
        // fill the whole tab, and let the lifted icon / dropped label move outside our bounds
        setLayoutParams(new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT));
        setClipChildren(false);
        setClipToPadding(false);

        final ColorStateList tint = AppCompatResources.getColorStateList(context,
                R.color.tab_icon_tint);

        icon = new ImageView(context);
        icon.setScaleType(ImageView.ScaleType.FIT_CENTER);
        icon.setImageTintList(tint);
        icon.setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
        final int iconPx = Math.round(ICON_SIZE_DP * density);
        final LayoutParams iconParams = new LayoutParams(iconPx, iconPx, Gravity.CENTER);
        addView(icon, iconParams);

        label = new TextView(context);
        label.setTextSize(TypedValue.COMPLEX_UNIT_SP, 10f);
        label.setTextColor(tint);
        label.setMaxLines(1);
        label.setEllipsize(TextUtils.TruncateAt.END);
        label.setGravity(Gravity.CENTER);
        label.setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
        try {
            final Typeface medium = ResourcesCompat.getFont(context, R.font.geist_medium);
            if (medium != null) {
                label.setTypeface(medium);
            }
        } catch (final Exception ignored) {
            // keep the theme font
        }
        final LayoutParams labelParams = new LayoutParams(LayoutParams.WRAP_CONTENT,
                LayoutParams.WRAP_CONTENT, Gravity.CENTER);
        labelParams.leftMargin = Math.round(4 * density);
        labelParams.rightMargin = Math.round(4 * density);
        addView(label, labelParams);

        applyState(false, false);
    }

    public void bind(@DrawableRes final int iconRes, @NonNull final CharSequence title) {
        icon.setImageResource(iconRes);
        label.setText(title);
    }

    @Override
    public void setSelected(final boolean selected) {
        super.setSelected(selected);
        // TabLayout reports the selection of the very first layout before the view is shown:
        // jump straight to the end state then, and animate only afterwards
        applyState(selected, isLaidOut());
    }

    /** Jumps to the given state without an animation. */
    public void snapTo(final boolean selected) {
        super.setSelected(selected);
        applyState(selected, false);
    }

    private void applyState(final boolean selected, final boolean animate) {
        if (animate && selected == selectedState) {
            return;
        }
        selectedState = selected;

        final float iconY = selected ? -ICON_LIFT_DP * density : 0f;
        final float labelY = (selected ? LABEL_DROP_DP : LABEL_DROP_DP + LABEL_SINK_DP) * density;
        final float labelAlpha = selected ? 1f : 0f;

        icon.animate().cancel();
        label.animate().cancel();
        if (!animate) {
            icon.setTranslationY(iconY);
            label.setTranslationY(labelY);
            label.setAlpha(labelAlpha);
            return;
        }
        icon.animate().translationY(iconY).setDuration(DURATION_MS)
                .setInterpolator(EMPHASIZED).start();
        label.animate().translationY(labelY).alpha(labelAlpha)
                .setDuration(selected ? DURATION_MS : DURATION_MS / 2)
                .setStartDelay(selected ? 40L : 0L)
                .setInterpolator(EMPHASIZED).start();
    }

    @Override
    protected void onDetachedFromWindow() {
        super.onDetachedFromWindow();
        icon.animate().cancel();
        label.animate().cancel();
    }
}
