package org.schabi.newpipe.settings.tabs;

import android.content.Context;

import androidx.annotation.NonNull;
import androidx.fragment.app.FragmentActivity;
import androidx.fragment.app.FragmentManager;

import org.schabi.newpipe.R;
import org.schabi.newpipe.settings.SelectChannelFragment;
import org.schabi.newpipe.settings.SelectFeedGroupFragment;
import org.schabi.newpipe.settings.SelectKioskFragment;
import org.schabi.newpipe.settings.SelectPlaylistFragment;
import org.schabi.newpipe.settings.tabs.AddTabDialog.ChooseTabListItem;

import java.util.ArrayList;
import java.util.List;

/**
 * Lets the user add a tab to the floating navbar straight from the main screen
 * (same options as Settings > Content > Main page tabs).
 */
public final class TabQuickAdd {
    private TabQuickAdd() { }

    public static void show(@NonNull final FragmentActivity activity) {
        final TabsManager manager = TabsManager.getManager(activity);
        final List<Tab> current = new ArrayList<>(manager.getTabs());
        final ChooseTabListItem[] available = getAvailableTabs(activity, current);
        if (available.length == 0) {
            return;
        }
        new AddTabDialog(activity, available, (dialog, which) ->
                addTab(activity, manager, current, available[which].tabId)).show();
    }

    private static void save(final TabsManager manager, final List<Tab> current,
                             final Tab tab) {
        final List<Tab> updated = new ArrayList<>(current);
        updated.add(tab);
        manager.saveTabs(updated);
    }

    private static void addTab(final FragmentActivity activity, final TabsManager manager,
                               final List<Tab> current, final int tabId) {
        final Tab.Type type = Tab.typeFrom(tabId);
        if (type == null) {
            return;
        }
        final FragmentManager fm = activity.getSupportFragmentManager();
        switch (type) {
            case KIOSK:
                final SelectKioskFragment kiosk = new SelectKioskFragment();
                kiosk.setOnSelectedListener((serviceId, kioskId, kioskName) ->
                        save(manager, current, new Tab.KioskTab(serviceId, kioskId)));
                kiosk.show(fm, "select_kiosk");
                return;
            case CHANNEL:
                final SelectChannelFragment channel = new SelectChannelFragment();
                channel.setOnSelectedListener((serviceId, url, name) ->
                        save(manager, current, new Tab.ChannelTab(serviceId, url, name)));
                channel.show(fm, "select_channel");
                return;
            case PLAYLIST:
                final SelectPlaylistFragment playlist = new SelectPlaylistFragment();
                playlist.setOnSelectedListener(new SelectPlaylistFragment.OnSelectedListener() {
                    @Override
                    public void onLocalPlaylistSelected(final long id, final String name) {
                        save(manager, current, new Tab.PlaylistTab(id, name));
                    }

                    @Override
                    public void onRemotePlaylistSelected(final int serviceId, final String url,
                                                         final String name) {
                        save(manager, current, new Tab.PlaylistTab(serviceId, url, name));
                    }
                });
                playlist.show(fm, "select_playlist");
                return;
            case FEEDGROUP:
                final SelectFeedGroupFragment group = new SelectFeedGroupFragment();
                group.setOnSelectedListener((groupId, name, iconId) ->
                        save(manager, current, new Tab.FeedGroupTab(groupId, name, iconId)));
                group.show(fm, "select_feed_group");
                return;
            default:
                save(manager, current, type.getTab());
                break;
        }
    }

    private static ChooseTabListItem[] getAvailableTabs(final Context context,
                                                         final List<Tab> current) {
        final ArrayList<ChooseTabListItem> list = new ArrayList<>();
        for (final Tab.Type type : Tab.Type.values()) {
            final Tab tab = type.getTab();
            switch (type) {
                case BLANK:
                    // not useful in a quick-add flow
                    break;
                case KIOSK:
                    list.add(new ChooseTabListItem(tab.getTabId(),
                            context.getString(R.string.kiosk_page_summary),
                            R.drawable.ic_whatshot));
                    break;
                case CHANNEL:
                    list.add(new ChooseTabListItem(tab.getTabId(),
                            context.getString(R.string.channel_page_summary),
                            tab.getTabIconRes(context)));
                    break;
                case PLAYLIST:
                    list.add(new ChooseTabListItem(tab.getTabId(),
                            context.getString(R.string.playlist_page_summary),
                            tab.getTabIconRes(context)));
                    break;
                case FEEDGROUP:
                    list.add(new ChooseTabListItem(tab.getTabId(),
                            context.getString(R.string.feed_group_page_summary),
                            tab.getTabIconRes(context)));
                    break;
                case DEFAULT_KIOSK:
                    if (!current.contains(tab)) {
                        list.add(new ChooseTabListItem(tab.getTabId(),
                                context.getString(R.string.default_kiosk_page_summary),
                                R.drawable.ic_whatshot));
                    }
                    break;
                default:
                    if (!current.contains(tab)) {
                        list.add(new ChooseTabListItem(context, tab));
                    }
                    break;
            }
        }
        return list.toArray(new ChooseTabListItem[0]);
    }
}
