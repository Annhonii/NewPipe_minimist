package org.schabi.newpipe.views;

import android.animation.AnimatorInflater;
import android.content.Context;
import android.content.res.ColorStateList;
import android.util.AttributeSet;
import android.view.Gravity;
import android.view.View;
import android.widget.ImageView;
import android.widget.LinearLayout;

import androidx.annotation.DrawableRes;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.content.res.AppCompatResources;
import androidx.viewpager.widget.ViewPager;

import org.schabi.newpipe.R;
import org.schabi.newpipe.util.ThemeHelper;

import java.util.List;

/**
 * Floating bottom navigation: a capsule with morphing tabs ({@link MorphingNavPill}) and,
 * next to it, optional round action buttons (downloads, history, ...).
 *
 * <p>Typical use:</p>
 * <pre>
 * bar.setItems(items);
 * bar.setActions(actions);
 * bar.attachToViewPager(pager);   // the pages slide and the bar morphs in sync
 * </pre>
 */
public class FloatingNavBar extends LinearLayout {
    /** A round button beside the pill. */
    public static final class Action {
        @DrawableRes
        final int iconRes;
        @NonNull
        final CharSequence description;
        @NonNull
        final Runnable onClick;

        public Action(@DrawableRes final int iconRes, @NonNull final CharSequence description,
                      @NonNull final Runnable onClick) {
            this.iconRes = iconRes;
            this.description = description;
            this.onClick = onClick;
        }
    }

    /** Reports the selected tab, whether it was chosen by tap, swipe or code. */
    public interface OnItemSelectedListener {
        void onItemSelected(int index);

        void onItemReselected(int index);
    }

    private static final float ACTION_SIZE_DP = 56f;
    private static final float ACTION_ICON_DP = 24f;
    private static final float ACTION_GAP_DP = 8f;
    private static final float PILL_HEIGHT_DP = 60f;

    private MorphingNavPill pill;
    private final float density;

    @Nullable
    private ViewPager pager;
    private boolean pagerDriving = false;
    private int lastReported = -1;
    @Nullable
    private OnItemSelectedListener listener;

    private final ViewPager.OnPageChangeListener pagerListener =
            new ViewPager.SimpleOnPageChangeListener() {
                @Override
                public void onPageScrolled(final int position, final float positionOffset,
                                           final int positionOffsetPixels) {
                    // only follow the pager while the user drags it (or it flings on): a tap
                    // runs its own spring animation
                    if (pagerDriving) {
                        pill.setPagerProgress(position, positionOffset);
                    }
                }

                @Override
                public void onPageSelected(final int position) {
                    if (!pagerDriving) {
                        pill.setSelectedIndex(position, true);
                    }
                    report(position);
                }

                @Override
                public void onPageScrollStateChanged(final int state) {
                    if (state == ViewPager.SCROLL_STATE_DRAGGING) {
                        pagerDriving = true;
                    } else if (state == ViewPager.SCROLL_STATE_IDLE && pagerDriving) {
                        pagerDriving = false;
                        if (pager != null) {
                            pill.settleAt(pager.getCurrentItem());
                        }
                    }
                }
            };

    public FloatingNavBar(@NonNull final Context context) {
        this(context, null);
    }

    public FloatingNavBar(@NonNull final Context context, @Nullable final AttributeSet attrs) {
        super(context, attrs);
        density = context.getResources().getDisplayMetrics().density;

        setOrientation(HORIZONTAL);
        setGravity(Gravity.CENTER_VERTICAL);
        // the elevation shadows of the pill and the buttons are drawn outside of their bounds
        setClipChildren(false);
        setClipToPadding(false);

        pill = new MorphingNavPill(context);
        pill.setOnItemClickListener(new MorphingNavPill.OnItemClickListener() {
            @Override
            public void onItemClicked(final int index) {
                // slide the pages right away, the pill is morphing to the tab already
                pagerDriving = false;
                if (pager != null) {
                    pager.setCurrentItem(index, true);
                }
                report(index);
            }

            @Override
            public void onItemReselected(final int index) {
                if (listener != null) {
                    listener.onItemReselected(index);
                }
            }
        });
        addView(pill, new LayoutParams(0, Math.round(PILL_HEIGHT_DP * density), 1f));
    }

    /*//////////////////////////////////////////////////////////////////////////
    // Public API
    //////////////////////////////////////////////////////////////////////////*/

    public void setOnItemSelectedListener(@Nullable final OnItemSelectedListener l) {
        listener = l;
    }

    public void setItems(@NonNull final List<MorphingNavPill.Item> items) {
        pill.setItems(items);
        lastReported = pill.getSelectedIndex();
        pill.setVisibility(items.size() > 1 ? VISIBLE : GONE);
        updateVisibility();
    }

    public int getSelectedIndex() {
        return pill.getSelectedIndex();
    }

    public void setSelectedIndex(final int index, final boolean animate) {
        pill.setSelectedIndex(index, animate);
        lastReported = index;
    }

    /** Accent colour of the selected chip. */
    public void setChipColor(final int color) {
        pill.setChipColor(color);
    }

    /** Replaces the round buttons that sit to the right of the pill. */
    public void setActions(@NonNull final List<Action> actions) {
        // everything after the pill is an action button
        removeViews(1, getChildCount() - 1);

        final int size = Math.round(ACTION_SIZE_DP * density);
        final int padding = Math.round((ACTION_SIZE_DP - ACTION_ICON_DP) / 2f * density);
        for (final Action action : actions) {
            final ImageView button = new ImageView(getContext());
            button.setImageDrawable(AppCompatResources.getDrawable(getContext(), action.iconRes));
            button.setImageTintList(ColorStateList.valueOf(
                    ThemeHelper.resolveColorFromAttr(getContext(),
                            android.R.attr.textColorPrimary)));
            button.setScaleType(ImageView.ScaleType.FIT_CENTER);
            button.setPadding(padding, padding, padding, padding);
            button.setBackgroundResource(R.drawable.bg_nav_action);
            button.setClipToOutline(true);
            button.setElevation(10f * density);
            button.setContentDescription(action.description);
            button.setClickable(true);
            button.setFocusable(true);
            button.setStateListAnimator(AnimatorInflater.loadStateListAnimator(getContext(),
                    R.animator.press_scale));
            button.setOnClickListener((View v) -> action.onClick.run());

            final LayoutParams params = new LayoutParams(size, size);
            params.setMarginStart(Math.round(ACTION_GAP_DP * density));
            addView(button, params);
        }
        updateVisibility();
    }

    /**
     * Makes the pages slide and the bar morph together: a tap on a tab slides the pages (and
     * morphs the pill with a spring), and dragging the pages morphs the pill under the finger.
     */
    public void attachToViewPager(@NonNull final ViewPager viewPager) {
        if (pager != null) {
            pager.removeOnPageChangeListener(pagerListener);
        }
        pager = viewPager;
        pagerDriving = false;
        viewPager.addOnPageChangeListener(pagerListener);
    }

    public void detachFromViewPager() {
        if (pager != null) {
            pager.removeOnPageChangeListener(pagerListener);
            pager = null;
        }
    }

    /*//////////////////////////////////////////////////////////////////////////
    // Internals
    //////////////////////////////////////////////////////////////////////////*/

    private void report(final int index) {
        if (index != lastReported) {
            lastReported = index;
            if (listener != null) {
                listener.onItemSelected(index);
            }
        }
    }

    /** The whole bar is hidden when there is neither a pill (less than 2 tabs) nor a button. */
    private void updateVisibility() {
        final boolean any = pill.getVisibility() == VISIBLE || getChildCount() > 1;
        setVisibility(any ? VISIBLE : GONE);
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        if (pager != null) {
            pager.removeOnPageChangeListener(pagerListener);
            pager.addOnPageChangeListener(pagerListener);
        }
    }

    @Override
    protected void onDetachedFromWindow() {
        super.onDetachedFromWindow();
        if (pager != null) {
            pager.removeOnPageChangeListener(pagerListener);
        }
        pagerDriving = false;
    }
}
