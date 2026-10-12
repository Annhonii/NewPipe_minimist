package org.schabi.newpipe.fragments.list.kiosk;

import android.content.Context;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.os.Parcelable;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.graphics.ColorUtils;
import androidx.fragment.app.Fragment;
import androidx.fragment.app.FragmentManager;
import androidx.fragment.app.FragmentStatePagerAdapter;

import com.google.android.material.tabs.TabLayout;

import org.schabi.newpipe.BaseFragment;
import org.schabi.newpipe.R;
import org.schabi.newpipe.databinding.FragmentTrendingBinding;
import org.schabi.newpipe.fragments.BlankFragment;
import org.schabi.newpipe.util.DeviceUtils;
import org.schabi.newpipe.util.ServiceHelper;
import org.schabi.newpipe.util.ThemeHelper;

/**
 * YouTube Trending page with tabs inside it: Live, Gaming, Movies and Podcasts.
 */
public class TrendingFragment extends BaseFragment {
    private static final int[] TAB_TITLES = {
            R.string.trending,
            R.string.duration_live,
            R.string.trending_music,
            R.string.trending_gaming,
            R.string.trending_movies,
            R.string.trending_podcasts
    };
    // null = the default kiosk of the service, the others are search based
    // (YouTube retired its own Trending page in July 2025, so "trending" is a search too)
    private static final String[] TAB_QUERIES = {
            "trending now",
            null,
            "trending music videos",
            "gaming trending",
            "new movie trailers trending",
            "trending podcast"
    };

    private FragmentTrendingBinding binding;

    @Override
    public View onCreateView(@NonNull final LayoutInflater inflater,
                             @Nullable final ViewGroup container,
                             @Nullable final Bundle savedInstanceState) {
        return inflater.inflate(R.layout.fragment_trending, container, false);
    }

    @Override
    protected void initViews(final View rootView, final Bundle savedInstanceState) {
        super.initViews(rootView, savedInstanceState);
        binding = FragmentTrendingBinding.bind(rootView);
        binding.trendingPager.setOffscreenPageLimit(1);
        binding.trendingPager.setAdapter(new TrendingPagerAdapter(this,
                getChildFragmentManager(), ServiceHelper.getSelectedServiceId(requireContext())));
        binding.trendingTabs.setupWithViewPager(binding.trendingPager);
        setupChips(binding.trendingTabs);
    }

    /** Turns the tabs into soft, rounded "bubble" chips (tonal when idle, accent when selected). */
    private void setupChips(final TabLayout tabs) {
        for (int i = 0; i < tabs.getTabCount(); i++) {
            final TabLayout.Tab tab = tabs.getTabAt(i);
            if (tab == null) {
                continue;
            }
            final CharSequence title = tab.getText();
            tab.setCustomView(R.layout.item_trending_chip);
            final View custom = tab.getCustomView();
            if (custom != null) {
                ((TextView) custom.findViewById(R.id.trending_chip_text)).setText(title);
            }
            styleChip(tab, tab.isSelected());
        }
        tabs.addOnTabSelectedListener(new TabLayout.OnTabSelectedListener() {
            @Override
            public void onTabSelected(final TabLayout.Tab tab) {
                styleChip(tab, true);
            }

            @Override
            public void onTabUnselected(final TabLayout.Tab tab) {
                styleChip(tab, false);
            }

            @Override
            public void onTabReselected(final TabLayout.Tab tab) { }
        });
    }

    private void styleChip(final TabLayout.Tab tab, final boolean selected) {
        final View custom = tab.getCustomView();
        if (custom == null) {
            return;
        }
        final TextView text = custom.findViewById(R.id.trending_chip_text);
        final Context context = text.getContext();
        final int primary = ThemeHelper.resolveColorFromAttr(context,
                androidx.appcompat.R.attr.colorPrimary);
        final GradientDrawable background = new GradientDrawable();
        background.setCornerRadius(DeviceUtils.dpToPx(100, context));
        background.setColor(selected ? primary : ColorUtils.setAlphaComponent(primary, 0x2E));
        text.setBackground(background);
        text.setTextColor(selected ? Color.WHITE
                : ThemeHelper.resolveColorFromAttr(context, android.R.attr.textColorPrimary));
    }

    @Override
    public void onResume() {
        super.onResume();
        setTitle(getString(R.string.trending));
    }

    @Override
    public void onDestroyView() {
        super.onDestroyView();
        if (binding != null) {
            binding.trendingPager.setAdapter(null);
            binding = null;
        }
    }

    @SuppressWarnings("deprecation")
    private static final class TrendingPagerAdapter extends FragmentStatePagerAdapter {
        private final Fragment host;
        private final int serviceId;

        private TrendingPagerAdapter(final Fragment host, final FragmentManager fm,
                                     final int serviceId) {
            super(fm, BEHAVIOR_RESUME_ONLY_CURRENT_FRAGMENT);
            this.host = host;
            this.serviceId = serviceId;
        }

        @NonNull
        @Override
        public Fragment getItem(final int position) {
            final BaseFragment fragment;
            try {
                if (TAB_QUERIES[position] == null) {
                    final KioskFragment kiosk = KioskFragment.getInstance(serviceId);
                    // the toolbar of this page always says "Trending"
                    kiosk.setTitleOverride(host.getString(R.string.trending));
                    fragment = kiosk;
                } else {
                    fragment = TrendingSearchFragment.getInstance(serviceId,
                            TAB_QUERIES[position], host.getString(TAB_TITLES[position]));
                }
            } catch (final Exception e) {
                return new BlankFragment();
            }
            // the container is the front page, children must not touch the toolbar title
            fragment.useAsFrontPage(true);
            return fragment;
        }

        @Override
        public int getCount() {
            return TAB_TITLES.length;
        }

        @Override
        public void restoreState(@Nullable final Parcelable state,
                                 @Nullable final ClassLoader loader) {
            try {
                super.restoreState(state, loader);
            } catch (final IllegalStateException e) {
                // a saved page fragment is gone (e.g. after back navigation or process death),
                // the pages are simply recreated by getItem()
            }
        }

        @Nullable
        @Override
        public CharSequence getPageTitle(final int position) {
            return host.getString(TAB_TITLES[position]);
        }
    }
}
