package org.schabi.newpipe.local.downloads;

import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.view.View;
import android.view.WindowManager;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.view.WindowCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.core.view.WindowInsetsControllerCompat;

import com.google.android.exoplayer2.ExoPlayer;
import com.google.android.exoplayer2.MediaItem;
import com.google.android.exoplayer2.PlaybackException;
import com.google.android.exoplayer2.Player;
import com.google.android.exoplayer2.ui.StyledPlayerView;

import org.schabi.newpipe.R;

/**
 * Plays a downloaded file inside the app with ExoPlayer (no external player needed).
 */
public class LocalPlayerActivity extends AppCompatActivity {
    private static final String EXTRA_TITLE = "local_player_title";
    private static final String STATE_POSITION = "local_player_position";
    private static final String STATE_PLAY_WHEN_READY = "local_player_play_when_ready";

    @Nullable
    private ExoPlayer player;
    private StyledPlayerView playerView;
    private Uri uri;
    private long resumePosition = 0;
    private boolean playWhenReady = true;

    public static void start(@NonNull final Context context, @NonNull final Uri uri,
                             @Nullable final String title) {
        final Intent intent = new Intent(context, LocalPlayerActivity.class);
        intent.setData(uri);
        intent.putExtra(EXTRA_TITLE, title);
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        if (!(context instanceof android.app.Activity)) {
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        }
        context.startActivity(intent);
    }

    @Override
    protected void onCreate(@Nullable final Bundle savedInstanceState) {
        org.schabi.newpipe.util.ThemeHelper.setDayNightMode(this);
        org.schabi.newpipe.util.ThemeHelper.setTheme(this);
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_local_player);
        // same background colour as the online video page (never plain white)
        final int pageColor = org.schabi.newpipe.util.ThemeHelper.resolveColorFromAttr(
                this, R.attr.windowBackground);
        findViewById(android.R.id.content).setBackgroundColor(pageColor);
        getWindow().getDecorView().setBackgroundColor(pageColor);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);

        playerView = findViewById(R.id.local_player_view);
        final android.widget.TextView titleView = findViewById(R.id.local_player_title);
        titleView.setText(getIntent().getStringExtra(EXTRA_TITLE));
        uri = getIntent().getData();
        if (uri == null) {
            finish();
            return;
        }
        if (savedInstanceState != null) {
            resumePosition = savedInstanceState.getLong(STATE_POSITION, 0);
            playWhenReady = savedInstanceState.getBoolean(STATE_PLAY_WHEN_READY, true);
        }
        applySystemUi();
    }

    @Override
    protected void onStart() {
        super.onStart();
        if (uri == null) {
            return;
        }
        player = new ExoPlayer.Builder(this).build();
        playerView.setPlayer(player);
        playerView.setControllerShowTimeoutMs(3000);
        player.addListener(new Player.Listener() {
            @Override
            public void onPlayerError(@NonNull final PlaybackException error) {
                android.widget.Toast.makeText(LocalPlayerActivity.this,
                        R.string.downloads_cannot_open, android.widget.Toast.LENGTH_SHORT).show();
                finish();
            }
        });
        player.setMediaItem(MediaItem.fromUri(uri));
        player.seekTo(resumePosition);
        player.setPlayWhenReady(playWhenReady);
        player.prepare();
    }

    @Override
    protected void onStop() {
        super.onStop();
        releasePlayer();
    }

    @Override
    protected void onSaveInstanceState(@NonNull final Bundle outState) {
        super.onSaveInstanceState(outState);
        if (player != null) {
            resumePosition = player.getCurrentPosition();
            playWhenReady = player.getPlayWhenReady();
        }
        outState.putLong(STATE_POSITION, resumePosition);
        outState.putBoolean(STATE_PLAY_WHEN_READY, playWhenReady);
    }

    @Override
    public void onWindowFocusChanged(final boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus) {
            applySystemUi();
        }
    }

    private void releasePlayer() {
        if (player != null) {
            resumePosition = player.getCurrentPosition();
            playWhenReady = player.getPlayWhenReady();
            playerView.setPlayer(null);
            player.release();
            player = null;
        }
    }

    private boolean isLandscape() {
        return getResources().getConfiguration().orientation
                == android.content.res.Configuration.ORIENTATION_LANDSCAPE;
    }

    /**
     * Portrait: same look as the online player page (status bar visible, video on top).
     * Landscape: bars hidden, the video fills the screen.
     */
    private void applySystemUi() {
        final android.view.Window window = getWindow();
        WindowCompat.setDecorFitsSystemWindows(window, false);
        window.setStatusBarColor(android.graphics.Color.TRANSPARENT);
        window.setNavigationBarColor(android.graphics.Color.TRANSPARENT);
        final WindowInsetsControllerCompat controller =
                WindowCompat.getInsetsController(window, window.getDecorView());
        final View spacer = findViewById(R.id.local_player_status_spacer);

        if (isLandscape()) {
            if (controller != null) {
                controller.setSystemBarsBehavior(
                        WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE);
                controller.hide(WindowInsetsCompat.Type.systemBars());
            }
            return;
        }
        if (controller != null) {
            controller.show(WindowInsetsCompat.Type.systemBars());
        }
        androidx.core.view.ViewCompat.setOnApplyWindowInsetsListener(
                findViewById(android.R.id.content), (v, insets) -> {
                    final int top = insets.getInsets(WindowInsetsCompat.Type.systemBars()).top;
                    final android.view.ViewGroup.LayoutParams lp = spacer.getLayoutParams();
                    if (lp.height != top) {
                        lp.height = top;
                        spacer.setLayoutParams(lp);
                    }
                    return insets;
                });
        androidx.core.view.ViewCompat.requestApplyInsets(findViewById(android.R.id.content));
    }
}
