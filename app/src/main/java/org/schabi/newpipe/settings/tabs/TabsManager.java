package org.schabi.newpipe.settings.tabs;

import android.content.Context;
import android.content.SharedPreferences;
import android.widget.Toast;

import androidx.preference.PreferenceManager;

import org.schabi.newpipe.R;

import java.util.List;

public final class TabsManager {
    /** The floating nav pill holds at most this many tabs. */
    public static final int MAX_TABS = 4;
    private static final String HOME_TAB_MIGRATED_KEY = "home_tab_added_v1";
    private final SharedPreferences sharedPreferences;
    private final String savedTabsKey;
    private final Context context;
    private SavedTabsChangeListener savedTabsChangeListener;
    private SharedPreferences.OnSharedPreferenceChangeListener preferenceChangeListener;

    private TabsManager(final Context context) {
        this.context = context;
        this.sharedPreferences = PreferenceManager.getDefaultSharedPreferences(context);
        this.savedTabsKey = context.getString(R.string.saved_tabs_key);
    }

    public static TabsManager getManager(final Context context) {
        return new TabsManager(context);
    }

    public List<Tab> getTabs() {
        final String savedJson = sharedPreferences.getString(savedTabsKey, null);
        try {
            final List<Tab> tabs = TabsJsonHelper.getTabsFromJson(savedJson);
            return limitTabs(addHomeTabOnce(tabs, savedJson != null));
        } catch (final TabsJsonHelper.InvalidJsonException e) {
            Toast.makeText(context, R.string.saved_tabs_invalid_json, Toast.LENGTH_SHORT).show();
            return getDefaultTabs();
        }
    }

    /**
     * Existing installs already have a saved tab list without the Home tab. Add it once (as the
     * first tab); after that the user is free to remove or move it in Settings.
     */
    private List<Tab> addHomeTabOnce(final List<Tab> tabs, final boolean hadSavedTabs) {
        if (sharedPreferences.getBoolean(HOME_TAB_MIGRATED_KEY, false)) {
            return tabs;
        }
        sharedPreferences.edit().putBoolean(HOME_TAB_MIGRATED_KEY, true).apply();
        if (!hadSavedTabs) {
            return tabs; // fresh install: the defaults already contain Home
        }
        final Tab home = Tab.Type.HOME.getTab();
        if (tabs.contains(home)) {
            return tabs;
        }
        final java.util.ArrayList<Tab> withHome = new java.util.ArrayList<>(tabs.size() + 1);
        withHome.add(home);
        withHome.addAll(tabs);
        final List<Tab> limited = limitTabs(withHome);
        saveTabs(limited);
        return limited;
    }

    /** Keeps only the first {@link #MAX_TABS} tabs (and stores the trimmed list). */
    private List<Tab> limitTabs(final List<Tab> tabs) {
        if (tabs.size() <= MAX_TABS) {
            return tabs;
        }
        final java.util.ArrayList<Tab> limited = new java.util.ArrayList<>(tabs.subList(0, MAX_TABS));
        saveTabs(limited);
        return limited;
    }

    public void saveTabs(final List<Tab> tabList) {
        final String jsonToSave = TabsJsonHelper.getJsonToSave(tabList);
        sharedPreferences.edit().putString(savedTabsKey, jsonToSave).apply();
    }

    public void resetTabs() {
        sharedPreferences.edit().remove(savedTabsKey).apply();
    }

    public List<Tab> getDefaultTabs() {
        return TabsJsonHelper.getDefaultTabs();
    }

    /*//////////////////////////////////////////////////////////////////////////
    // Listener
    //////////////////////////////////////////////////////////////////////////*/

    public void setSavedTabsListener(final SavedTabsChangeListener listener) {
        if (preferenceChangeListener != null) {
            sharedPreferences.unregisterOnSharedPreferenceChangeListener(preferenceChangeListener);
        }
        savedTabsChangeListener = listener;
        preferenceChangeListener = getPreferenceChangeListener();
        sharedPreferences.registerOnSharedPreferenceChangeListener(preferenceChangeListener);
    }

    public void unsetSavedTabsListener() {
        if (preferenceChangeListener != null) {
            sharedPreferences.unregisterOnSharedPreferenceChangeListener(preferenceChangeListener);
        }
        preferenceChangeListener = null;
        savedTabsChangeListener = null;
    }

    private SharedPreferences.OnSharedPreferenceChangeListener getPreferenceChangeListener() {
        return (sp, key) -> {
            if (savedTabsKey.equals(key) && savedTabsChangeListener != null) {
                savedTabsChangeListener.onTabsChanged();
            }
        };
    }

    public interface SavedTabsChangeListener {
        void onTabsChanged();
    }
}
