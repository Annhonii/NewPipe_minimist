package org.schabi.newpipe.fragments.list.kiosk;

import android.os.Bundle;
import android.os.Parcelable;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
import androidx.fragment.app.FragmentManager;
import androidx.fragment.app.FragmentStatePagerAdapter;

import org.schabi.newpipe.BaseFragment;
import org.schabi.newpipe.R;
import org.schabi.newpipe.databinding.FragmentTrendingBinding;
import org.schabi.newpipe.fragments.BlankFragment;
import org.schabi.newpipe.util.ServiceHelper;

/**
 * YouTube Trending page with tabs inside it: Live, Gaming, Movies and Podcasts.
 */
public class TrendingFragment extends BaseFragment {
    private static final int[] TAB_TITLES = {
            R.string.duration_live,
            R.string.trending_gaming,
            R.string.trending_movies,
            R.string.trending_podcasts
    };
    // null = the default kiosk of the service, the others are search based
    private static final String[] TAB_QUERIES = {
            null,
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
