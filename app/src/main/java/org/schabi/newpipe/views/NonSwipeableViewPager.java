package org.schabi.newpipe.views;

import android.content.Context;
import android.util.AttributeSet;
import android.view.MotionEvent;

import androidx.annotation.NonNull;
import androidx.viewpager.widget.ViewPager;

/**
 * A ViewPager that never reacts to swipes. Pages are changed from code (or by tapping tabs), so
 * horizontal swipes pass through to the outer pager that switches the main tabs.
 */
public class NonSwipeableViewPager extends ViewPager {
    public NonSwipeableViewPager(@NonNull final Context context) {
        super(context);
    }

    public NonSwipeableViewPager(@NonNull final Context context,
                                 final AttributeSet attrs) {
        super(context, attrs);
    }

    @Override
    public boolean onInterceptTouchEvent(final MotionEvent ev) {
        return false;
    }

    @Override
    public boolean onTouchEvent(final MotionEvent ev) {
        return false;
    }

    @Override
    public boolean canScrollHorizontally(final int direction) {
        return false;
    }
}
