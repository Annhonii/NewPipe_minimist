package org.schabi.newpipe.views;

import android.content.Context;
import android.util.AttributeSet;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;

import androidx.annotation.NonNull;

import com.google.android.material.tabs.TabLayout;

/**
 * A TabLayout that is scrollable when tabs exceed its width.
 * Hides when there are less than 2 tabs.
 */
public class ScrollableTabLayout extends TabLayout {
    private static final String TAG = ScrollableTabLayout.class.getSimpleName();

    private int layoutWidth = 0;
    private int prevVisibility = View.GONE;
    private boolean fixedModeOnly = false;
    /** If greater than zero, the tabs are laid out as in the floating nav pill. */
    private int pillVisibleTabs = 0;

    public ScrollableTabLayout(final Context context) {
        super(context);
    }

    public ScrollableTabLayout(final Context context, final AttributeSet attrs) {
        super(context, attrs);
    }

    public ScrollableTabLayout(final Context context, final AttributeSet attrs,
                               final int defStyleAttr) {
        super(context, attrs, defStyleAttr);
    }

    @Override
    protected void onMeasure(final int widthMeasureSpec, final int heightMeasureSpec) {
        // Done on every measure pass (and not once when tabs get added) because TabLayout resets
        // the size of its tabs by itself, e.g. when its mode changes.
        applyPillTabWidths(View.MeasureSpec.getMode(widthMeasureSpec),
                View.MeasureSpec.getSize(widthMeasureSpec));
        super.onMeasure(widthMeasureSpec, heightMeasureSpec);
    }

    @Override
    protected void onLayout(final boolean changed, final int l, final int t, final int r,
                            final int b) {
        super.onLayout(changed, l, t, r, b);

        remeasureTabs();
    }

    @Override
    protected void onSizeChanged(final int w, final int h, final int oldw, final int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);

        layoutWidth = w;
    }

    @Override
    public void addTab(@NonNull final Tab tab, final int position, final boolean setSelected) {
        super.addTab(tab, position, setSelected);

        hasMultipleTabs();

        // Adding a tab won't decrease total tabs' width so tabMode won't have to change to FIXED
        if (getTabMode() != MODE_SCROLLABLE) {
            remeasureTabs();
        }
    }

    @Override
    public void removeTabAt(final int position) {
        super.removeTabAt(position);

        hasMultipleTabs();

        // Removing a tab won't increase total tabs' width
        // so tabMode won't have to change to SCROLLABLE
        if (getTabMode() != MODE_FIXED) {
            remeasureTabs();
        }
    }

    @Override
    protected void onVisibilityChanged(final View changedView, final int visibility) {
        super.onVisibilityChanged(changedView, visibility);

        // Check width if some tabs have been added/removed while ScrollableTabLayout was invisible
        // We don't have to check if it was GONE because then requestLayout() will be called
        if (changedView == this) {
            if (prevVisibility == View.INVISIBLE) {
                remeasureTabs();
            }
            prevVisibility = visibility;
        }
    }

    /**
     * Keep the tabs equally spread over the whole width and never scroll them. Superseded
     * by {@link #setPillVisibleTabs(int)} for the floating nav pill.
     *
     * @param fixedOnly true to always use {@link TabLayout#MODE_FIXED}
     */
    public void setFixedModeOnly(final boolean fixedOnly) {
        fixedModeOnly = fixedOnly;
        if (fixedOnly) {
            setMode(MODE_FIXED);
        } else {
            remeasureTabs();
        }
    }

    /**
     * Lays the tabs out like the floating nav pill: at most {@code visibleTabs} tabs are shown at
     * once, each of them getting the same share of the width. If there are more tabs, the rest is
     * reached by swiping the tabs sideways. With fewer tabs they share the whole width.
     *
     * <p>This view must not have padding, because {@link TabLayout} ignores it when it measures
     * its tabs and the last tab (and its indicator) would be cut off.</p>
     *
     * @param visibleTabs how many tabs fit on screen at once, or 0 to switch this mode off
     */
    public void setPillVisibleTabs(final int visibleTabs) {
        pillVisibleTabs = Math.max(0, visibleTabs);
        if (pillVisibleTabs > 0) {
            setMode(MODE_SCROLLABLE);
            requestLayout();
        } else {
            remeasureTabs();
        }
    }

    private void applyPillTabWidths(final int widthMode, final int availableWidth) {
        final int count = getTabCount();
        final int viewport = availableWidth - getPaddingLeft() - getPaddingRight();
        if (pillVisibleTabs <= 0 || count == 0 || viewport <= 0
                || widthMode == View.MeasureSpec.UNSPECIFIED) {
            return;
        }

        final int tabWidth = viewport / Math.min(count, pillVisibleTabs);
        for (int i = 0; i < count; i++) {
            final Tab tab = getTabAt(i);
            final ViewGroup.LayoutParams params = tab == null ? null : tab.view.getLayoutParams();
            if (params == null) {
                continue;
            }
            // The params object is changed in place: we are in the middle of a measure pass, so
            // the tab is measured with the new width right after this, without a new layout pass.
            params.width = tabWidth;
            if (params instanceof LinearLayout.LayoutParams) {
                ((LinearLayout.LayoutParams) params).weight = 0;
            }
        }
    }

    private void setMode(final int mode) {
        if (mode == getTabMode()) {
            return;
        }

        setTabMode(mode);
    }

    /**
     * Make ScrollableTabLayout not visible if there are less than two tabs.
     */
    private void hasMultipleTabs() {
        if (getTabCount() > 1) {
            setVisibility(View.VISIBLE);
        } else {
            setVisibility(View.GONE);
        }
    }

    /**
     * Calculate minimal width required by tabs and set tabMode accordingly.
     */
    private void remeasureTabs() {
        if (pillVisibleTabs > 0) {
            setMode(MODE_SCROLLABLE);
            return;
        }
        if (fixedModeOnly) {
            setMode(MODE_FIXED);
            return;
        }
        if (prevVisibility != View.VISIBLE) {
            return;
        }
        if (layoutWidth == 0) {
            return;
        }

        final int count = getTabCount();
        int contentWidth = 0;
        for (int i = 0; i < count; i++) {
            final View child = getTabAt(i).view;
            if (child.getVisibility() == View.VISIBLE) {
                // Use tab's minimum requested width should actual content be too small
                contentWidth += Math.max(child.getMinimumWidth(), child.getMeasuredWidth());
            }
        }

        if (contentWidth > layoutWidth) {
            setMode(TabLayout.MODE_SCROLLABLE);
        } else {
            setMode(TabLayout.MODE_FIXED);
        }
    }
}
