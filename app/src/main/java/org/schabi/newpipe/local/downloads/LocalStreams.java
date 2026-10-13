package org.schabi.newpipe.local.downloads;

import android.content.Context;
import android.media.MediaMetadataRetriever;
import android.net.Uri;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import org.schabi.newpipe.R;
import org.schabi.newpipe.extractor.ServiceList;
import org.schabi.newpipe.extractor.MediaFormat;
import org.schabi.newpipe.extractor.stream.AudioStream;
import org.schabi.newpipe.extractor.stream.DeliveryMethod;
import org.schabi.newpipe.extractor.stream.Description;
import org.schabi.newpipe.extractor.stream.Stream;
import org.schabi.newpipe.extractor.stream.StreamInfo;
import org.schabi.newpipe.extractor.stream.StreamType;
import org.schabi.newpipe.extractor.stream.VideoStream;
import org.schabi.newpipe.player.playqueue.SinglePlayQueue;
import org.schabi.newpipe.extractor.NewPipe;
import org.schabi.newpipe.util.ExtractorHelper;
import org.schabi.newpipe.util.NavigationHelper;

import io.reactivex.rxjava3.schedulers.Schedulers;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

/**
 * Lets downloaded (local) audio / video files play in the same player as online streams.
 *
 * <p>A local file is described by a {@link StreamInfo} whose only stream is the file's
 * {@code content://} / {@code file://} uri. {@link org.schabi.newpipe.util.ExtractorHelper}
 * returns that info instead of asking an extractor, so the video page and the player treat it
 * like any other stream.</p>
 */
public final class LocalStreams {
    private static final class Entry {
        final String fileName;
        final boolean audioOnly;
        /** Page of the original online video (null/empty if unknown). */
        final String sourceUrl;

        Entry(final String fileName, final boolean audioOnly, final String sourceUrl) {
            this.fileName = fileName;
            this.audioOnly = audioOnly;
            this.sourceUrl = sourceUrl;
        }
    }

    private static final Map<String, Entry> ENTRIES = new ConcurrentHashMap<>();

    private LocalStreams() {
    }

    /** True for urls of local files (the only urls with these schemes come from here). */
    public static boolean isLocalUrl(@Nullable final String url) {
        return url != null && (url.startsWith("content://") || url.startsWith("file://"));
    }

    /** Opens a downloaded file in the main (online-style) player. */
    public static void open(@NonNull final Context context, @NonNull final Uri uri,
                            @Nullable final String fileName, final boolean audioOnly,
                            @Nullable final String sourceUrl) {
        final String url = uri.toString();
        final Entry entry = new Entry(fileName, audioOnly, sourceUrl);
        ENTRIES.put(url, entry);
        NavigationHelper.playOnMainPlayer(context,
                new SinglePlayQueue(baseInfo(context, url, entry, serviceIdOf(sourceUrl))),
                false);
    }

    /** Service of the original video, so the channel opens in the right service. */
    private static int serviceIdOf(@Nullable final String sourceUrl) {
        if (sourceUrl != null && !sourceUrl.isEmpty()) {
            try {
                return NewPipe.getServiceByUrl(sourceUrl).getServiceId();
            } catch (final Exception ignored) {
                // not a known service: use the default below
            }
        }
        return ServiceList.YouTube.getServiceId();
    }

    private static String titleOf(@Nullable final String fileName) {
        if (fileName == null) {
            return "";
        }
        final int dot = fileName.lastIndexOf('.');
        return dot > 0 ? fileName.substring(0, dot) : fileName;
    }

    private static StreamInfo baseInfo(@NonNull final Context context, @NonNull final String url,
                                       @NonNull final Entry entry, final int serviceId) {
        final StreamInfo info = new StreamInfo(serviceId, url, url,
                entry.audioOnly ? StreamType.AUDIO_STREAM : StreamType.VIDEO_STREAM,
                url, titleOf(entry.fileName), 0);
        info.setUploaderName(context.getString(R.string.downloads));
        info.setDescription(Description.EMPTY_DESCRIPTION);
        return info;
    }

    /**
     * Builds the full info of a local file (reads duration and resolution from the file).
     * Called on a background thread by {@link org.schabi.newpipe.util.ExtractorHelper}.
     */
    @NonNull
    public static StreamInfo buildInfo(@NonNull final Context context, @NonNull final String url) {
        Entry entry = ENTRIES.get(url);
        if (entry == null) {
            // e.g. opened again from the watch history after the app was restarted
            final String last = Uri.parse(url).getLastPathSegment();
            entry = new Entry(last == null ? "" : last.substring(last.lastIndexOf('/') + 1),
                    false, null);
        }
        final StreamInfo info = baseInfo(context, url, entry, serviceIdOf(entry.sourceUrl));
        copyChannelInfo(info, entry.sourceUrl);

        long durationMs = 0;
        int height = 0;
        boolean hasVideo = !entry.audioOnly;
        final MediaMetadataRetriever retriever = new MediaMetadataRetriever();
        try {
            retriever.setDataSource(context, Uri.parse(url));
            durationMs = parseLong(retriever.extractMetadata(
                    MediaMetadataRetriever.METADATA_KEY_DURATION));
            if (retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_HAS_VIDEO) != null) {
                hasVideo = true;
                final int w = (int) parseLong(retriever.extractMetadata(
                        MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH));
                final int h = (int) parseLong(retriever.extractMetadata(
                        MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT));
                final long rotation = parseLong(retriever.extractMetadata(
                        MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION));
                height = rotation == 90 || rotation == 270 ? w : h;
            } else {
                hasVideo = false;
            }
        } catch (final Exception ignored) {
            // unreadable metadata: still try to play the file with what is known
        } finally {
            try {
                retriever.release();
            } catch (final Exception ignored) {
                // nothing to do if releasing fails
            }
        }
        if (entry.audioOnly) {
            hasVideo = false;
        }

        info.setDuration(Math.max(0, durationMs / 1000));
        final String fileName = entry.fileName == null ? "" : entry.fileName;
        final int dot = fileName.lastIndexOf('.');
        final MediaFormat format = dot >= 0 && dot < fileName.length() - 1
                ? MediaFormat.getFromSuffix(fileName.substring(dot + 1).toLowerCase(Locale.ROOT))
                : null;

        if (hasVideo) {
            info.setStreamType(StreamType.VIDEO_STREAM);
            final VideoStream video = new VideoStream.Builder()
                    .setId(Stream.ID_UNKNOWN)
                    .setContent(url, true)
                    .setMediaFormat(format)
                    .setDeliveryMethod(DeliveryMethod.PROGRESSIVE_HTTP)
                    .setIsVideoOnly(false)
                    .setResolution(height > 0 ? height + "p" : "")
                    .build();
            info.setVideoStreams(List.of(video));
        } else {
            info.setStreamType(StreamType.AUDIO_STREAM);
            final AudioStream audio = new AudioStream.Builder()
                    .setId(Stream.ID_UNKNOWN)
                    .setContent(url, true)
                    .setMediaFormat(format)
                    .setDeliveryMethod(DeliveryMethod.PROGRESSIVE_HTTP)
                    .setAverageBitrate(AudioStream.UNKNOWN_BITRATE)
                    .build();
            info.setAudioStreams(List.of(audio));
        }
        return info;
    }

    /**
     * Takes the channel (name, logo, link), thumbnail and description from the original online
     * video, so tapping the channel works like online. Quietly skipped when offline.
     */
    private static void copyChannelInfo(@NonNull final StreamInfo info,
                                        @Nullable final String sourceUrl) {
        if (sourceUrl == null || sourceUrl.isEmpty() || isLocalUrl(sourceUrl)) {
            return;
        }
        try {
            final StreamInfo origin = ExtractorHelper
                    .getStreamInfo(info.getServiceId(), sourceUrl, false)
                    .subscribeOn(Schedulers.io())
                    .timeout(3, TimeUnit.SECONDS)
                    .blockingGet();
            info.setUploaderName(origin.getUploaderName());
            info.setUploaderUrl(origin.getUploaderUrl());
            info.setUploaderAvatars(origin.getUploaderAvatars());
            info.setUploaderVerified(origin.isUploaderVerified());
            info.setUploaderSubscriberCount(origin.getUploaderSubscriberCount());
            info.setSubChannelName(origin.getSubChannelName());
            info.setSubChannelUrl(origin.getSubChannelUrl());
            info.setSubChannelAvatars(origin.getSubChannelAvatars());
            info.setThumbnails(origin.getThumbnails());
            if (origin.getDescription() != null) {
                info.setDescription(origin.getDescription());
            }
        } catch (final Exception ignored) {
            // offline or the video is gone: keep the plain "Downloads" info
        }
    }

    private static long parseLong(@Nullable final String value) {
        if (value == null) {
            return 0;
        }
        try {
            return Long.parseLong(value);
        } catch (final NumberFormatException e) {
            return 0;
        }
    }
}
