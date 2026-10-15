package org.schabi.newpipe.player.helper;

import com.google.android.exoplayer2.C;
import com.google.android.exoplayer2.DefaultLoadControl;
import com.google.android.exoplayer2.upstream.DefaultAllocator;

public class LoadController extends DefaultLoadControl {

    public static final String TAG = "LoadController";
    private boolean preloadingEnabled = true;

    // Start playing as soon as a fraction of a second is buffered (ExoPlayer default: 2.5 s at
    // start and 5 s after a stall). The buffer is still filled up to MAX_BUFFER_MS afterwards.
    private static final int MIN_BUFFER_MS = 15000;
    private static final int MAX_BUFFER_MS = 50000;
    private static final int BUFFER_FOR_PLAYBACK_MS = 250;
    private static final int BUFFER_FOR_PLAYBACK_AFTER_REBUFFER_MS = 1000;

    public LoadController() {
        super(new DefaultAllocator(true, C.DEFAULT_BUFFER_SEGMENT_SIZE),
                MIN_BUFFER_MS,
                MAX_BUFFER_MS,
                BUFFER_FOR_PLAYBACK_MS,
                BUFFER_FOR_PLAYBACK_AFTER_REBUFFER_MS,
                DEFAULT_TARGET_BUFFER_BYTES,
                true,
                DEFAULT_BACK_BUFFER_DURATION_MS,
                DEFAULT_RETAIN_BACK_BUFFER_FROM_KEYFRAME);
    }

    @Override
    public void onPrepared() {
        preloadingEnabled = true;
        super.onPrepared();
    }

    @Override
    public void onStopped() {
        preloadingEnabled = true;
        super.onStopped();
    }

    @Override
    public void onReleased() {
        preloadingEnabled = true;
        super.onReleased();
    }

    @Override
    public boolean shouldContinueLoading(final long playbackPositionUs,
                                         final long bufferedDurationUs,
                                         final float playbackSpeed) {
        if (!preloadingEnabled) {
            return false;
        }
        return super.shouldContinueLoading(
                playbackPositionUs, bufferedDurationUs, playbackSpeed);
    }

    public void disablePreloadingOfCurrentTrack() {
        preloadingEnabled = false;
    }
}
