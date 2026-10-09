package com.jersnet.wtfview.video;

import android.content.Context;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.os.Message;
import android.util.Log;
import android.view.Surface;
import android.view.SurfaceView;

import androidx.constraintlayout.widget.ConstraintLayout;

import com.google.android.exoplayer2.C;
import com.google.android.exoplayer2.DefaultLoadControl;
import com.google.android.exoplayer2.ExoPlayer;
import com.google.android.exoplayer2.MediaItem;
import com.google.android.exoplayer2.PlaybackException;
import com.google.android.exoplayer2.Player;
import com.google.android.exoplayer2.extractor.Extractor;
import com.google.android.exoplayer2.extractor.ExtractorsFactory;
import com.google.android.exoplayer2.source.MediaSource;
import com.google.android.exoplayer2.source.ProgressiveMediaSource;
import com.google.android.exoplayer2.upstream.DataSource;
import com.google.android.exoplayer2.upstream.DataSpec;
import com.google.android.exoplayer2.video.VideoSize;
import com.jersnet.wtfview.usb.AndroidUSBInputStream;
import com.jersnet.wtfview.usb.UsbMaskConnection;

public class VideoReaderExoplayer {
    private static final String TAG = "VideoReaderExoplayer";
    private final Handler videoReaderEventListener;
    private ExoPlayer mPlayer;
    private final SurfaceView surfaceView;
    private AndroidUSBInputStream inputStream;
    private UsbMaskConnection mUsbMaskConnection;
    private final Context context;

    public VideoReaderExoplayer(SurfaceView videoSurface, Context c, Handler v) {
        this.surfaceView = videoSurface;
        this.context = c;
        this.videoReaderEventListener = v;
    }

    public void setUsbMaskConnection(UsbMaskConnection connection) {
        this.mUsbMaskConnection = connection;
        this.inputStream = mUsbMaskConnection.mInputStream;
    }

    public void start() {

        DefaultLoadControl loadControl = new DefaultLoadControl.Builder()
                .setBufferDurationsMs(32, 100, 0, 0)
                .setPrioritizeTimeOverSizeThresholds(true)
                .build();

        mPlayer = new ExoPlayer.Builder(context)
                .setLoadControl(loadControl)
                .build();

        surfaceView.setKeepScreenOn(true);
        mPlayer.setVideoSurfaceView(surfaceView);
        mPlayer.setVideoScalingMode(C.VIDEO_SCALING_MODE_SCALE_TO_FIT);
        mPlayer.setWakeMode(C.WAKE_MODE_LOCAL);

        DataSpec dataSpec = new DataSpec(Uri.EMPTY, 0, C.LENGTH_UNSET);

        DataSource.Factory dataSourceFactory = () ->
                new InputStreamDataSource(context, dataSpec, inputStream);

        ExtractorsFactory extractorsFactory = () ->
                new Extractor[]{new H264Extractor(131072, 10000)};

        MediaSource mediaSource = new ProgressiveMediaSource.Factory(dataSourceFactory, extractorsFactory)
                .createMediaSource(MediaItem.fromUri(Uri.EMPTY));

        mPlayer.setMediaSource(mediaSource);
        mPlayer.prepare();
        mPlayer.play();

        mPlayer.addListener(new Player.Listener() {
            @Override
            public void onPlayerError(PlaybackException error) {
                Log.e(TAG, "Player Error: " + error.getMessage());
                new Handler(Looper.getMainLooper()).postDelayed(() -> restart(), 1000);
            }

            @Override
            public void onPlaybackStateChanged(int state) {
                if (state == Player.STATE_ENDED) {
                    sendEvent(VideoReaderEventMessageCode.WAITING_FOR_VIDEO);
                    new Handler(Looper.getMainLooper()).postDelayed(() -> restart(), 1000);
                }
            }

            @Override
            public void onRenderedFirstFrame() {
                Log.i(TAG, "Rendered first video frame!");
                sendEvent(VideoReaderEventMessageCode.VIDEO_PLAYING);
            }

            @Override
            public void onVideoSizeChanged(VideoSize videoSize) {
                if (videoSize.width > 0 && videoSize.height > 0) {
                    ConstraintLayout.LayoutParams params = (ConstraintLayout.LayoutParams) surfaceView.getLayoutParams();
                    params.dimensionRatio = videoSize.width + ":" + videoSize.height;
                    surfaceView.setLayoutParams(params);
                }
            }
        });
    }

    private void sendEvent(VideoReaderEventMessageCode eventCode) {
        if (videoReaderEventListener != null) {
            Message msg = new Message();
            msg.obj = eventCode;
            videoReaderEventListener.sendMessage(msg);
        }
    }

    public void restart() {
        if (mPlayer != null) {
            mPlayer.release();
        }
        if (mUsbMaskConnection != null && mUsbMaskConnection.isReady()) {
            mUsbMaskConnection.start();
            start();
        }
    }

    public void stop() {
        if (mPlayer != null) {
            mPlayer.release();
            mPlayer = null;
        }
    }

    public enum VideoReaderEventMessageCode { WAITING_FOR_VIDEO, VIDEO_PLAYING }
}
