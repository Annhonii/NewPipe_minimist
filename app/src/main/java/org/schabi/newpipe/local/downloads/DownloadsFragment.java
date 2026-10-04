package org.schabi.newpipe.local.downloads;

import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.media.MediaMetadataRetriever;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.format.Formatter;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.webkit.MimeTypeMap;
import android.widget.ImageView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.content.FileProvider;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import org.schabi.newpipe.BaseFragment;
import org.schabi.newpipe.BuildConfig;
import org.schabi.newpipe.R;

import java.io.File;
import java.net.URI;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import us.shandian.giga.get.FinishedMission;
import us.shandian.giga.get.sqlite.FinishedMissionStore;

/**
 * "Downloads" tab: every finished download as a row with thumbnail and title.
 */
public class DownloadsFragment extends BaseFragment {
    private final ExecutorService executor = Executors.newFixedThreadPool(2);
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final List<FinishedMission> missions = new ArrayList<>();
    private final android.util.LruCache<String, Bitmap> thumbCache =
            new android.util.LruCache<>(60);

    private RecyclerView list;
    private View emptyView;
    private DownloadsAdapter adapter;
    private volatile boolean destroyed = false;

    @Override
    public View onCreateView(@NonNull final LayoutInflater inflater,
                             @Nullable final ViewGroup container,
                             @Nullable final Bundle savedInstanceState) {
        return inflater.inflate(R.layout.fragment_downloads, container, false);
    }

    @Override
    protected void initViews(final View rootView, final Bundle savedInstanceState) {
        super.initViews(rootView, savedInstanceState);
        list = rootView.findViewById(R.id.downloads_list);
        emptyView = rootView.findViewById(R.id.downloads_empty);
        adapter = new DownloadsAdapter();
        list.setLayoutManager(new LinearLayoutManager(requireContext()));
        list.setAdapter(adapter);
    }

    @Override
    public void onResume() {
        super.onResume();
        setTitle(getString(R.string.downloads));
        reload();
    }

    @Override
    public void onDestroyView() {
        super.onDestroyView();
        list = null;
        emptyView = null;
        adapter = null;
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        destroyed = true;
        executor.shutdownNow();
    }

    private void reload() {
        final Context appContext = requireContext().getApplicationContext();
        executor.execute(() -> {
            final List<FinishedMission> loaded = new ArrayList<>();
            try {
                final FinishedMissionStore store = new FinishedMissionStore(appContext);
                for (final FinishedMission m : store.loadFinishedMissions()) {
                    if (m.storage != null && m.storage.existsAsFile()) {
                        loaded.add(m);
                    }
                }
            } catch (final Exception ignored) {
                // show an empty list
            }
            // newest first
            Collections.sort(loaded, (a, b) -> Long.compare(b.timestamp, a.timestamp));
            mainHandler.post(() -> {
                if (destroyed || adapter == null) {
                    return;
                }
                missions.clear();
                missions.addAll(loaded);
                adapter.notifyDataSetChanged();
                emptyView.setVisibility(missions.isEmpty() ? View.VISIBLE : View.GONE);
            });
        });
    }

    private static String titleOf(@NonNull final FinishedMission mission) {
        final String name = mission.storage.getName();
        if (name == null) {
            return "";
        }
        final int dot = name.lastIndexOf('.');
        return dot > 0 ? name.substring(0, dot) : name;
    }

    private static boolean isAudio(@NonNull final FinishedMission mission) {
        return mission.kind == 'a';
    }

    private void open(@NonNull final FinishedMission mission) {
        try {
            final Uri uri;
            if (mission.storage.isDirect()) {
                uri = FileProvider.getUriForFile(requireContext(),
                        BuildConfig.APPLICATION_ID + ".provider",
                        new File(URI.create(mission.storage.getUri().toString())));
            } else {
                uri = mission.storage.getUri();
            }
            String mime = mission.storage.getType();
            if (mime == null || mime.isEmpty() || mime.equals("application/octet-stream")) {
                final String name = mission.storage.getName();
                final int dot = name == null ? -1 : name.lastIndexOf('.');
                mime = dot < 0 ? null : MimeTypeMap.getSingleton()
                        .getMimeTypeFromExtension(name.substring(dot + 1));
            }
            final Intent view = new Intent(Intent.ACTION_VIEW);
            view.setDataAndType(uri, mime == null ? "video/*" : mime);
            view.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            startActivity(Intent.createChooser(view, null));
        } catch (final Exception e) {
            Toast.makeText(requireContext(), R.string.downloads_cannot_open,
                    Toast.LENGTH_SHORT).show();
        }
    }

    private final class DownloadsAdapter extends RecyclerView.Adapter<DownloadViewHolder> {
        @NonNull
        @Override
        public DownloadViewHolder onCreateViewHolder(@NonNull final ViewGroup parent,
                                                     final int viewType) {
            return new DownloadViewHolder(LayoutInflater.from(parent.getContext())
                    .inflate(R.layout.item_download, parent, false));
        }

        @Override
        public void onBindViewHolder(@NonNull final DownloadViewHolder holder,
                                     final int position) {
            final FinishedMission mission = missions.get(position);
            holder.title.setText(titleOf(mission));
            holder.info.setText(Formatter.formatFileSize(holder.itemView.getContext(),
                    mission.length));
            holder.itemView.setOnClickListener(v -> open(mission));

            final String key = String.valueOf(mission.storage.getUri());
            holder.boundKey = key;
            if (isAudio(mission)) {
                holder.thumb.setImageResource(R.drawable.ic_headset);
                holder.thumb.setScaleType(ImageView.ScaleType.CENTER);
                return;
            }
            holder.thumb.setScaleType(ImageView.ScaleType.CENTER_CROP);
            final Bitmap cached = thumbCache.get(key);
            if (cached != null) {
                holder.thumb.setImageBitmap(cached);
                return;
            }
            holder.thumb.setImageResource(R.drawable.placeholder_thumbnail_video);
            final Context ctx = holder.itemView.getContext().getApplicationContext();
            final Uri uri = mission.storage.getUri();
            executor.execute(() -> {
                final Bitmap bmp = extractFrame(ctx, uri);
                if (bmp == null) {
                    return;
                }
                thumbCache.put(key, bmp);
                mainHandler.post(() -> {
                    if (!destroyed && key.equals(holder.boundKey)) {
                        holder.thumb.setImageBitmap(bmp);
                    }
                });
            });
        }

        @Override
        public int getItemCount() {
            return missions.size();
        }
    }

    @Nullable
    private static Bitmap extractFrame(final Context ctx, final Uri uri) {
        final MediaMetadataRetriever retriever = new MediaMetadataRetriever();
        try {
            retriever.setDataSource(ctx, uri);
            final Bitmap frame = retriever.getFrameAtTime(1_000_000,
                    MediaMetadataRetriever.OPTION_CLOSEST_SYNC);
            if (frame == null) {
                return null;
            }
            final int targetWidth = 360;
            if (frame.getWidth() <= targetWidth) {
                return frame;
            }
            final int targetHeight = frame.getHeight() * targetWidth / frame.getWidth();
            return Bitmap.createScaledBitmap(frame, targetWidth, targetHeight, true);
        } catch (final Exception e) {
            return null;
        } finally {
            try {
                retriever.release();
            } catch (final Exception ignored) {
                // nothing to do
            }
        }
    }

    private static final class DownloadViewHolder extends RecyclerView.ViewHolder {
        final ImageView thumb;
        final TextView title;
        final TextView info;
        String boundKey;

        DownloadViewHolder(@NonNull final View itemView) {
            super(itemView);
            thumb = itemView.findViewById(R.id.download_thumbnail);
            title = itemView.findViewById(R.id.download_title);
            info = itemView.findViewById(R.id.download_info);
        }
    }
}
