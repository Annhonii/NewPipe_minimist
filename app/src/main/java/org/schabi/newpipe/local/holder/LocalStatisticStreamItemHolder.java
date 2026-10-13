package org.schabi.newpipe.local.holder;

import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;

import org.schabi.newpipe.R;
import org.schabi.newpipe.database.LocalItem;
import org.schabi.newpipe.database.stream.StreamStatisticsEntry;
import org.schabi.newpipe.ktx.ViewUtils;
import org.schabi.newpipe.local.LocalItemBuilder;
import org.schabi.newpipe.local.history.HistoryRecordManager;
import org.schabi.newpipe.util.DependentPreferenceHelper;
import org.schabi.newpipe.util.Localization;
import org.schabi.newpipe.util.ServiceHelper;
import org.schabi.newpipe.util.ExtractorHelper;
import org.schabi.newpipe.util.image.CoilHelper;
import org.schabi.newpipe.util.image.ImageStrategy;
import org.schabi.newpipe.views.AnimatedProgressBar;

import java.time.format.DateTimeFormatter;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

import io.reactivex.rxjava3.android.schedulers.AndroidSchedulers;
import io.reactivex.rxjava3.schedulers.Schedulers;

/*
 * Created by Christian Schabesberger on 01.08.16.
 * <p>
 * Copyright (C) Christian Schabesberger 2016 <chris.schabesberger@mailbox.org>
 * StreamInfoItemHolder.java is part of NewPipe.
 * <p>
 * NewPipe is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 * <p>
 * NewPipe is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 * <p>
 * You should have received a copy of the GNU General Public License
 * along with NewPipe.  If not, see <http://www.gnu.org/licenses/>.
 */

public class LocalStatisticStreamItemHolder extends LocalItemHolder {
    public final ImageView itemThumbnailView;
    public final TextView itemVideoTitleView;
    public final TextView itemUploaderView;
    public final TextView itemDurationView;
    @Nullable
    public final TextView itemAdditionalDetails;
    private final AnimatedProgressBar itemProgressView;
    /** Only the card layout (same as the Home page) has the channel logo and the three dots. */
    @Nullable
    private final ImageView itemUploaderAvatarView;
    @Nullable
    private final View itemMoreButton;

    /** channel url -> logo url, for channels that are not subscribed (fetched once). */
    private static final Map<String, String> AVATAR_CACHE = new ConcurrentHashMap<>();
    private static final Set<String> AVATAR_PENDING = ConcurrentHashMap.newKeySet();
    private static final Set<String> AVATAR_FAILED = ConcurrentHashMap.newKeySet();
    private static final int MAX_PARALLEL_AVATAR_REQUESTS = 3;

    public LocalStatisticStreamItemHolder(final LocalItemBuilder itemBuilder,
                                          final ViewGroup parent) {
        this(itemBuilder, R.layout.list_stream_item, parent);
    }

    LocalStatisticStreamItemHolder(final LocalItemBuilder infoItemBuilder, final int layoutId,
                                   final ViewGroup parent) {
        super(infoItemBuilder, layoutId, parent);

        itemThumbnailView = itemView.findViewById(R.id.itemThumbnailView);
        itemVideoTitleView = itemView.findViewById(R.id.itemVideoTitleView);
        itemUploaderView = itemView.findViewById(R.id.itemUploaderView);
        itemDurationView = itemView.findViewById(R.id.itemDurationView);
        itemAdditionalDetails = itemView.findViewById(R.id.itemAdditionalDetails);
        itemProgressView = itemView.findViewById(R.id.itemProgressView);
        itemUploaderAvatarView = itemView.findViewById(R.id.itemUploaderAvatarView);
        itemMoreButton = itemView.findViewById(R.id.itemMoreButton);
    }

    /** Same "views • time ago" line as the Home page, falling back to the old history line. */
    private String getCardDetailLine(final StreamStatisticsEntry entry,
                                     final DateTimeFormatter dateTimeFormatter) {
        final var stream = entry.getStreamEntity();
        String views = "";
        if (stream.getViewCount() != null && stream.getViewCount() >= 0) {
            views = Localization.shortViewCount(itemBuilder.getContext(), stream.getViewCount());
        }
        String when = null;
        if (stream.getUploadDate() != null) {
            when = Localization.relativeTime(stream.getUploadDate());
        } else if (stream.getTextualUploadDate() != null
                && !stream.getTextualUploadDate().isEmpty()) {
            when = stream.getTextualUploadDate();
        }
        if (!views.isEmpty() && when != null) {
            return Localization.concatenateStrings(views, when);
        } else if (!views.isEmpty()) {
            return views;
        } else if (when != null) {
            return when;
        }
        return getStreamInfoDetailLine(entry, dateTimeFormatter);
    }

    private void bindAvatar(final int serviceId, @Nullable final String uploaderUrl) {
        final ImageView avatar = itemUploaderAvatarView;
        if (avatar == null) {
            return;
        }
        avatar.setTag(R.id.itemUploaderAvatarView, uploaderUrl);
        avatar.setVisibility(View.VISIBLE);
        // tapping the logo opens the channel directly
        if (uploaderUrl == null || uploaderUrl.isEmpty()) {
            avatar.setOnClickListener(null);
            avatar.setClickable(false);
        } else {
            avatar.setOnClickListener(view -> org.schabi.newpipe.util.NavigationHelper
                    .openChannelFromLogo(view.getContext(), serviceId, uploaderUrl, ""));
        }
        if (avatar.getLayoutParams() instanceof ViewGroup.MarginLayoutParams) {
            // logo starts exactly under the left edge of the thumbnail (16dp), not at the screen edge
            final ViewGroup.MarginLayoutParams lp =
                    (ViewGroup.MarginLayoutParams) avatar.getLayoutParams();
            final int start = Math.round(16 * avatar.getResources().getDisplayMetrics().density);
            if (lp.getMarginStart() != start) {
                lp.setMarginStart(start);
                avatar.setLayoutParams(lp);
            }
        }
        if (uploaderUrl == null || uploaderUrl.isEmpty()) {
            CoilHelper.INSTANCE.loadAvatar(avatar, (String) null);
            return;
        }

        String url = null;
        final var lookup = itemBuilder.getAvatarLookup();
        if (lookup != null) {
            url = lookup.apply(uploaderUrl);
        }
        if (url == null || url.isEmpty()) {
            url = AVATAR_CACHE.get(uploaderUrl);
        }
        if (url != null && !url.isEmpty()) {
            CoilHelper.INSTANCE.loadAvatar(avatar, url);
            return;
        }

        // unknown logo: show the placeholder and fetch the channel once
        CoilHelper.INSTANCE.loadAvatar(avatar, (String) null);
        if (AVATAR_FAILED.contains(uploaderUrl) || AVATAR_PENDING.contains(uploaderUrl)
                || AVATAR_PENDING.size() >= MAX_PARALLEL_AVATAR_REQUESTS) {
            return;
        }
        AVATAR_PENDING.add(uploaderUrl);
        ExtractorHelper.getChannelInfo(serviceId, uploaderUrl, false)
                .subscribeOn(Schedulers.io())
                .observeOn(AndroidSchedulers.mainThread())
                .subscribe(info -> {
                    AVATAR_PENDING.remove(uploaderUrl);
                    final String found = ImageStrategy.INSTANCE.imageListToDbUrl(info.getAvatars());
                    if (found == null || found.isEmpty()) {
                        AVATAR_FAILED.add(uploaderUrl);
                        return;
                    }
                    AVATAR_CACHE.put(uploaderUrl, found);
                    if (uploaderUrl.equals(avatar.getTag(R.id.itemUploaderAvatarView))) {
                        CoilHelper.INSTANCE.loadAvatar(avatar, found);
                    }
                }, throwable -> {
                    AVATAR_PENDING.remove(uploaderUrl);
                    AVATAR_FAILED.add(uploaderUrl);
                });
    }

    private String getStreamInfoDetailLine(final StreamStatisticsEntry entry,
                                           final DateTimeFormatter dateTimeFormatter) {
        return Localization.concatenateStrings(
                // watchCount
                Localization.shortViewCount(itemBuilder.getContext(), entry.getWatchCount()),
                dateTimeFormatter.format(entry.getLatestAccessDate()),
                // serviceName
                ServiceHelper.getNameOfServiceById(entry.getStreamEntity().getServiceId()));
    }

    @Override
    public void updateFromItem(final LocalItem localItem,
                               final HistoryRecordManager historyRecordManager,
                               final DateTimeFormatter dateTimeFormatter) {
        if (!(localItem instanceof StreamStatisticsEntry)) {
            return;
        }
        final StreamStatisticsEntry item = (StreamStatisticsEntry) localItem;

        itemVideoTitleView.setText(item.getStreamEntity().getTitle());
        itemUploaderView.setText(item.getStreamEntity().getUploader());

        if (item.getStreamEntity().getDuration() > 0) {
            itemDurationView.
                    setText(Localization.getDurationString(item.getStreamEntity().getDuration()));
            if (itemMoreButton == null) {
                itemDurationView.setBackgroundColor(ContextCompat.getColor(
                        itemBuilder.getContext(), R.color.duration_background_color));
            }
            itemDurationView.setVisibility(View.VISIBLE);

            if (DependentPreferenceHelper.getPositionsInListsEnabled(itemProgressView.getContext())
                    && item.getProgressMillis() > 0) {
                itemProgressView.setVisibility(View.VISIBLE);
                itemProgressView.setMax((int) item.getStreamEntity().getDuration());
                itemProgressView.setProgress((int) TimeUnit.MILLISECONDS
                        .toSeconds(item.getProgressMillis()));
            } else {
                itemProgressView.setVisibility(View.GONE);
            }
        } else {
            itemDurationView.setVisibility(View.GONE);
            itemProgressView.setVisibility(View.GONE);
        }

        if (itemAdditionalDetails != null) {
            itemAdditionalDetails.setText(itemMoreButton != null
                    ? getCardDetailLine(item, dateTimeFormatter)
                    : getStreamInfoDetailLine(item, dateTimeFormatter));
        }

        bindAvatar(item.getStreamEntity().getServiceId(), item.getStreamEntity().getUploaderUrl());

        // Default thumbnail is shown on error, while loading and if the url is empty
        CoilHelper.INSTANCE.loadThumbnail(itemThumbnailView,
                item.getStreamEntity().getThumbnailUrl());

        itemView.setOnClickListener(view -> {
            if (itemBuilder.getOnItemSelectedListener() != null) {
                itemBuilder.getOnItemSelectedListener().selected(item);
            }
        });

        if (itemMoreButton != null) {
            // card layout: the three dots replace the long press
            itemView.setLongClickable(false);
            itemView.setOnLongClickListener(null);
            itemMoreButton.setVisibility(View.VISIBLE);
            itemMoreButton.setOnClickListener(view -> {
                if (itemBuilder.getOnItemSelectedListener() != null) {
                    itemBuilder.getOnItemSelectedListener().held(item);
                }
            });
            return;
        }

        itemView.setLongClickable(true);
        itemView.setOnLongClickListener(view -> {
            if (itemBuilder.getOnItemSelectedListener() != null) {
                itemBuilder.getOnItemSelectedListener().held(item);
            }
            return true;
        });
    }

    @Override
    public void updateState(final LocalItem localItem,
                            final HistoryRecordManager historyRecordManager) {
        if (!(localItem instanceof StreamStatisticsEntry)) {
            return;
        }
        final StreamStatisticsEntry item = (StreamStatisticsEntry) localItem;

        if (DependentPreferenceHelper.getPositionsInListsEnabled(itemProgressView.getContext())
                && item.getProgressMillis() > 0 && item.getStreamEntity().getDuration() > 0) {
            itemProgressView.setMax((int) item.getStreamEntity().getDuration());
            if (itemProgressView.getVisibility() == View.VISIBLE) {
                itemProgressView.setProgressAnimated((int) TimeUnit.MILLISECONDS
                        .toSeconds(item.getProgressMillis()));
            } else {
                itemProgressView.setProgress((int) TimeUnit.MILLISECONDS
                        .toSeconds(item.getProgressMillis()));
                ViewUtils.animate(itemProgressView, true, 500);
            }
        } else if (itemProgressView.getVisibility() == View.VISIBLE) {
            ViewUtils.animate(itemProgressView, false, 500);
        }
    }
}
