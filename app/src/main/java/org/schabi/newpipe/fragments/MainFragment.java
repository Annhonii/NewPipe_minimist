package org.schabi.newpipe.fragments;

import static android.widget.RelativeLayout.ABOVE;
import static android.widget.RelativeLayout.ALIGN_PARENT_BOTTOM;
import static android.widget.RelativeLayout.ALIGN_PARENT_TOP;
import static android.widget.RelativeLayout.BELOW;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.util.Log;
import android.view.HapticFeedbackConstants;
import android.view.LayoutInflater;
import android.view.Menu;
import android.view.MenuInflater;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
import android.widget.RelativeLayout;
import android.widget.ScrollView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.ActionBar;
import androidx.core.widget.NestedScrollView;
import androidx.fragment.app.Fragment;
import androidx.fragment.app.FragmentManager;
import androidx.fragment.app.FragmentStatePagerAdapterMenuWorkaround;
import androidx.preference.PreferenceManager;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import androidx.viewpager.widget.ViewPager;

import org.schabi.newpipe.BaseFragment;
import org.schabi.newpipe.R;
import org.schabi.newpipe.databinding.FragmentMainBinding;
import org.schabi.newpipe.error.ErrorInfo;
import org.schabi.newpipe.error.ErrorUtil;
import org.schabi.newpipe.error.UserAction;
import org.schabi.newpipe.local.playlist.LocalPlaylistFragment;
import org.schabi.newpipe.settings.tabs.Tab;
import org.schabi.newpipe.settings.tabs.TabsManager;
import org.schabi.newpipe.util.NavigationHelper;
import org.schabi.newpipe.util.ServiceHelper;
import org.schabi.newpipe.views.FloatingNavBar;
import org.schabi.newpipe.views.MorphingNavPill;

import java.util.ArrayList;
import java.util.List;

public class MainFragment extends BaseFragment
        implements FloatingNavBar.OnItemSelectedListener {
    private FragmentMainBinding binding;
    private SelectedTabsPagerAdapter pagerAdapter;

    private final List<Tab> tabsList = new ArrayList<>();
    private TabsManager tabsManager;

    private static final int MAX_TABS_FOR_TWO_ACTIONS = 4;

    private boolean hasTabsChanged = false;

    /** Extra bottom space (px) for the mini player, on top of the nav pill's own space. */
    private int miniPlayerInset = 0;
    /**
     * The pages run behind the floating nav pill, so every vertical list that is created inside
     * them gets a bottom inset, which lets the last item scroll up above the pill.
     */
    private final FragmentManager.FragmentLifecycleCallbacks pageInsetCallbacks =
            new FragmentManager.FragmentLifecycleCallbacks() {
                @Override
                public void onFragmentViewCreated(@NonNull final FragmentManager fm,
                                                  @NonNull final Fragment f,
                                                  @NonNull final View v,
                                                  @Nullable final Bundle savedInstanceState) {
                    // posted, so that the page has set up its layout managers by then
                    v.post(() -> applyBottomInset(v));
                }
            };

    private SharedPreferences prefs;
    private boolean youtubeRestrictedModeEnabled;
    private String youtubeRestrictedModeEnabledKey;
    private boolean mainTabsPositionBottom;
    private String mainTabsPositionKey;

    /*//////////////////////////////////////////////////////////////////////////
    // Fragment's LifeCycle
    //////////////////////////////////////////////////////////////////////////*/

    @Override
    public void onCreate(final Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setHasOptionsMenu(true);
        getChildFragmentManager().registerFragmentLifecycleCallbacks(pageInsetCallbacks, true);
        tabsManager = TabsManager.getManager(activity);
        tabsManager.setSavedTabsListener(() -> {
            if (DEBUG) {
                Log.d(TAG, "TabsManager.SavedTabsChangeListener: "
                        + "onTabsChanged called, isResumed = " + isResumed());
            }
            if (isResumed()) {
                setupTabs();
            } else {
                hasTabsChanged = true;
            }
        });

        prefs = PreferenceManager.getDefaultSharedPreferences(requireContext());
        youtubeRestrictedModeEnabledKey = getString(R.string.youtube_restricted_mode_enabled);
        youtubeRestrictedModeEnabled = prefs.getBoolean(youtubeRestrictedModeEnabledKey, false);
        mainTabsPositionKey = getString(R.string.main_tabs_position_key);
        mainTabsPositionBottom = prefs.getBoolean(mainTabsPositionKey, false);
    }

    @Override
    public View onCreateView(@NonNull final LayoutInflater inflater,
                             @Nullable final ViewGroup container,
                             @Nullable final Bundle savedInstanceState) {
        return inflater.inflate(R.layout.fragment_main, container, false);
    }

    @Override
    protected void initViews(final View rootView, final Bundle savedInstanceState) {
        super.initViews(rootView, savedInstanceState);

        binding = FragmentMainBinding.bind(rootView);

        // floating pill: the selected tab morphs into an accent chip (icon + name), the pages
        // slide in sync with it
        binding.mainNavBar.attachToViewPager(binding.pager);
        binding.mainNavBar.setOnItemSelectedListener(this);

        setupTabs();
        updateTabLayoutPosition();
    }

    private int lastAccountSignature = 0;

    @Override
    public void onResume() {
        super.onResume();

        // the account button shows the Google photo: rebuild the top bar after (un)login
        final org.schabi.newpipe.account.YtAccountStore.Session account =
                org.schabi.newpipe.account.YtAccountStore.INSTANCE.load(requireContext());
        final int signature = account == null ? 0 : account.hashCode();
        if (signature != lastAccountSignature) {
            lastAccountSignature = signature;
            requireActivity().invalidateOptionsMenu();
        }

        // re-apply the mini player space (this page may have just come back to the front)
        final Fragment playerFragment = requireActivity().getSupportFragmentManager()
                .findFragmentById(R.id.fragment_player_holder);
        if (playerFragment instanceof org.schabi.newpipe.fragments.detail.VideoDetailFragment) {
            ((org.schabi.newpipe.fragments.detail.VideoDetailFragment) playerFragment)
                    .refreshMiniPlayerSpace();
        }

        final boolean newYoutubeRestrictedModeEnabled =
                prefs.getBoolean(youtubeRestrictedModeEnabledKey, false);
        if (youtubeRestrictedModeEnabled != newYoutubeRestrictedModeEnabled || hasTabsChanged) {
            youtubeRestrictedModeEnabled = newYoutubeRestrictedModeEnabled;
            setupTabs();
        }

        final boolean newMainTabsPosition = prefs.getBoolean(mainTabsPositionKey, false);
        if (mainTabsPositionBottom != newMainTabsPosition) {
            mainTabsPositionBottom = newMainTabsPosition;
            updateTabLayoutPosition();
        }
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        getChildFragmentManager().unregisterFragmentLifecycleCallbacks(pageInsetCallbacks);
        tabsManager.unsetSavedTabsListener();
        if (binding != null) {
            binding.pager.setAdapter(null);
            binding = null;
        }
    }

    @Override
    public void onDestroyView() {
        super.onDestroyView();
        binding = null;
    }

    /*//////////////////////////////////////////////////////////////////////////
    // Menu
    //////////////////////////////////////////////////////////////////////////*/

    @Override
    public void onCreateOptionsMenu(@NonNull final Menu menu,
                                    @NonNull final MenuInflater inflater) {
        super.onCreateOptionsMenu(menu, inflater);
        if (DEBUG) {
            Log.d(TAG, "onCreateOptionsMenu() called with: "
                    + "menu = [" + menu + "], inflater = [" + inflater + "]");
        }
        inflater.inflate(R.menu.menu_main_fragment, menu);
        org.schabi.newpipe.account.YtToolbarButtons.bind(activity, menu, this::onOptionsItemSelected);

        // settings are opened from the profile picture, so the separate button stays hidden
        final MenuItem settingsItem = menu.findItem(R.id.action_settings);
        if (settingsItem != null) {
            settingsItem.setVisible(false);
        }

        final ActionBar supportActionBar = activity.getSupportActionBar();
        if (supportActionBar != null) {
            supportActionBar.setDisplayHomeAsUpEnabled(false);
            supportActionBar.setDisplayShowHomeEnabled(false);
            supportActionBar.setDisplayUseLogoEnabled(false);
        }
    }

    @Override
    public boolean onOptionsItemSelected(final MenuItem item) {
        if (item.getItemId() == R.id.action_search) {
            try {
                NavigationHelper.openSearchFragment(getFM(),
                        ServiceHelper.getSelectedServiceId(activity), "");
            } catch (final Exception e) {
                ErrorUtil.showUiErrorSnackbar(this, "Opening search fragment", e);
            }
            return true;
        } else if (item.getItemId() == R.id.action_settings) {
            NavigationHelper.openSettings(activity);
            return true;
        }
        return super.onOptionsItemSelected(item);
    }

    /*//////////////////////////////////////////////////////////////////////////
    // Tabs
    //////////////////////////////////////////////////////////////////////////*/

    private void setupTabs() {
        tabsList.clear();
        tabsList.addAll(tabsManager.getTabs());

        if (pagerAdapter == null || !pagerAdapter.sameTabs(tabsList)) {
            pagerAdapter = new SelectedTabsPagerAdapter(requireContext(),
                    getChildFragmentManager(), tabsList);
        }

        // keep every tab alive: pages used to be destroyed when two tabs away, so coming back to
        // Home reloaded (buffered) the feed and lost the scroll position
        binding.pager.setOffscreenPageLimit(Math.max(1, tabsList.size() - 1));
        binding.pager.setAdapter(null);
        binding.pager.setAdapter(pagerAdapter);

        updateNavItems();
        updateNavActions();
        updateTitleForTab(binding.pager.getCurrentItem());

        hasTabsChanged = false;
    }

    private void updateNavItems() {
        final List<MorphingNavPill.Item> items = new ArrayList<>();
        for (final Tab tab : tabsList) {
            items.add(new MorphingNavPill.Item(tab.getTabIconRes(requireContext()),
                    tab.getTabName(requireContext())));
        }
        binding.mainNavBar.setItems(items);
        binding.mainNavBar.setSelectedIndex(binding.pager.getCurrentItem(), false);
    }

    /**
     * Round buttons beside the pill for the places that are not a tab (yet): downloads and
     * history. They are left out when they already are a tab, and only one is shown when there
     * are many tabs, so that the pill stays roomy.
     */
    private void updateNavActions() {
        final List<FloatingNavBar.Action> actions = new ArrayList<>();
        if (!hasTab(Tab.DownloadsTab.ID)) {
            actions.add(new FloatingNavBar.Action(R.drawable.ic_file_download,
                    getString(R.string.downloads),
                    () -> NavigationHelper.openDownloads(activity)));
        }
        if (!hasTab(Tab.HistoryTab.ID)) {
            actions.add(new FloatingNavBar.Action(R.drawable.ic_history,
                    getString(R.string.title_activity_history),
                    () -> NavigationHelper.openStatisticFragment(getFM())));
        }
        final int maxActions = tabsList.size() <= MAX_TABS_FOR_TWO_ACTIONS ? 2 : 1;
        while (actions.size() > maxActions) {
            actions.remove(actions.size() - 1);
        }
        binding.mainNavBar.setActions(actions);
    }

    private boolean hasTab(final int tabId) {
        for (final Tab tab : tabsList) {
            if (tab.getTabId() == tabId) {
                return true;
            }
        }
        return false;
    }

    private void updateTitleForTab(final int tabPosition) {
        // the toolbar title is the same for every main tab
        setTitle(getString(R.string.main_toolbar_title));
    }

    public void commitPlaylistTabs() {
        pagerAdapter.getLocalPlaylistFragments()
                .stream()
                .forEach(LocalPlaylistFragment::saveImmediate);
    }

    /**
     * Keeps the page content clear of the mini player that floats above the nav pill.
     *
     * @param bottomPx extra space (px) at the bottom of the page content
     */
    public void setMiniPlayerInset(final int bottomPx) {
        if (binding == null) {
            return;
        }
        if (miniPlayerInset != bottomPx) {
            miniPlayerInset = bottomPx;
            applyBottomInset(binding.pager);
        }
    }

    /** Bottom space the page lists keep free: the nav pill, plus the mini player if shown. */
    private int totalBottomInset() {
        return getResources().getDimensionPixelSize(R.dimen.nav_pill_content_inset)
                + miniPlayerInset;
    }

    /**
     * Gives every vertical scrolling list below {@code root} a bottom padding that is not clipped,
     * so the content scrolls behind the nav pill but can still be pulled up above it.
     * The padding the list had before is remembered in a tag, so this can safely run again.
     */
    private void applyBottomInset(@Nullable final View root) {
        if (root == null || binding == null || !isAdded()) {
            return;
        }
        // the Shorts pager manages its own bottom space; padding its inner list would shrink
        // every short and let the next one peek in
        if (root instanceof androidx.viewpager2.widget.ViewPager2) {
            return;
        }
        final boolean scrolling = root instanceof RecyclerView
                || root instanceof ScrollView || root instanceof NestedScrollView;
        if (scrolling && isVerticalScroller(root)) {
            final ViewGroup list = (ViewGroup) root;
            Object base = list.getTag(R.id.main_inset_base);
            if (base == null) {
                base = list.getPaddingBottom();
                list.setTag(R.id.main_inset_base, base);
            }
            final int bottom = (Integer) base + totalBottomInset();
            list.setClipToPadding(false);
            if (list.getPaddingBottom() != bottom) {
                list.setPadding(list.getPaddingLeft(), list.getPaddingTop(),
                        list.getPaddingRight(), bottom);
            }
            return;
        }
        if (root instanceof ViewGroup) {
            final ViewGroup group = (ViewGroup) root;
            for (int i = 0; i < group.getChildCount(); i++) {
                applyBottomInset(group.getChildAt(i));
            }
        }
    }

    private static boolean isVerticalScroller(final View view) {
        if (view instanceof RecyclerView) {
            final RecyclerView.LayoutManager manager = ((RecyclerView) view).getLayoutManager();
            // horizontal rows (e.g. the channel logos) must keep their size
            return !(manager instanceof LinearLayoutManager)
                    || ((LinearLayoutManager) manager).getOrientation()
                            == LinearLayoutManager.VERTICAL;
        }
        return true;
    }

    private void updateTabLayoutPosition() {
        // The tabs always float at the bottom as a rounded pill (see fragment_main.xml).
        // The "tabs position" setting no longer moves them.
        final FloatingNavBar tabLayout = binding.mainNavBar;
        final ViewPager viewPager = binding.pager;

        final var tabParams = (RelativeLayout.LayoutParams) tabLayout.getLayoutParams();
        final var pagerParams = (RelativeLayout.LayoutParams) viewPager.getLayoutParams();

        tabParams.removeRule(ALIGN_PARENT_TOP);
        tabParams.addRule(ALIGN_PARENT_BOTTOM);
        // the pager fills the screen and runs behind the pill (see applyBottomInset)
        pagerParams.removeRule(BELOW);
        pagerParams.removeRule(ABOVE);

        tabLayout.setLayoutParams(tabParams);
        viewPager.setLayoutParams(pagerParams);
    }

    private boolean isHistoryTabSelected() {
        if (binding == null) {
            return false;
        }
        int position = binding.mainNavBar.getSelectedIndex();
        if (position < 0) {
            position = binding.pager.getCurrentItem();
        }
        return position >= 0 && position < tabsList.size()
                && tabsList.get(position).getTabId() == Tab.HistoryTab.ID;
    }

    @Override
    public void onItemSelected(final int position) {
        if (DEBUG) {
            Log.d(TAG, "onItemSelected() called with: position = [" + position + "]");
        }
        updateTitleForTab(position);
        if (binding != null && binding.mainNavBar.isLaidOut()) {
            binding.mainNavBar.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK);
        }
        // show / hide the settings button of the top bar
        if (activity != null) {
            activity.invalidateOptionsMenu();
        }
    }

    @Override
    public void onItemReselected(final int position) {
        if (DEBUG) {
            Log.d(TAG, "onItemReselected() called with: position = [" + position + "]");
        }
        updateTitleForTab(position);
    }

    public static final class SelectedTabsPagerAdapter
            extends FragmentStatePagerAdapterMenuWorkaround {
        private final Context context;
        private final List<Tab> internalTabsList;
        /**
         * Keep reference to LocalPlaylistFragments, because their data can be modified by the user
         * during runtime and changes are not committed immediately. However, in some cases,
         * the changes need to be committed immediately by calling
         * {@link LocalPlaylistFragment#saveImmediate()}.
         * The fragments are removed when {@link LocalPlaylistFragment#onDestroy()} is called.
         */
        private final List<LocalPlaylistFragment> localPlaylistFragments = new ArrayList<>();

        private SelectedTabsPagerAdapter(final Context context,
                                         final FragmentManager fragmentManager,
                                         final List<Tab> tabsList) {
            super(fragmentManager, BEHAVIOR_RESUME_ONLY_CURRENT_FRAGMENT);
            this.context = context;
            this.internalTabsList = new ArrayList<>(tabsList);
        }

        @NonNull
        @Override
        public Fragment getItem(final int position) {
            final Tab tab = internalTabsList.get(position);

            final Fragment fragment;
            try {
                fragment = tab.getFragment(context);
            } catch (final Throwable t) {
                return new BlankFragment(new ErrorInfo(t, UserAction.GETTING_MAIN_SCREEN_TAB,
                        "Tab " + tab.getClass().getSimpleName() + ":" + tab.getTabName(context)));
            }

            if (fragment instanceof BaseFragment) {
                ((BaseFragment) fragment).useAsFrontPage(true);
            }

            if (fragment instanceof LocalPlaylistFragment) {
                localPlaylistFragments.add((LocalPlaylistFragment) fragment);
            }

            return fragment;
        }

        public List<LocalPlaylistFragment> getLocalPlaylistFragments() {
            return localPlaylistFragments;
        }

        @Override
        public int getItemPosition(@NonNull final Object object) {
            // Causes adapter to reload all Fragments when
            // notifyDataSetChanged is called
            return POSITION_NONE;
        }

        @Override
        public int getCount() {
            return internalTabsList.size();
        }

        public boolean sameTabs(final List<Tab> tabsToCompare) {
            return internalTabsList.equals(tabsToCompare);
        }
    }
}
