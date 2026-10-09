package com.jersnet.wtfview.dvr;

import android.content.Context;
import android.content.pm.PackageManager;
import android.hardware.display.DisplayManager;
import android.hardware.display.VirtualDisplay;
import android.media.AudioFormat;
import android.media.AudioRecord;
import android.media.MediaCodec;
import android.media.MediaCodecInfo;
import android.media.MediaFormat;
import android.media.MediaMuxer;
import android.media.MediaRecorder;
import android.media.MediaScannerConnection;
import android.media.projection.MediaProjection;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.view.Surface;

import androidx.core.content.ContextCompat;

import com.jersnet.wtfview.FileLogger;

import java.io.File;
import java.nio.ByteBuffer;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicBoolean;

public class DvrRecorder {
    private static final String TAG = "DvrRecorder";

    public interface DvrListener {
        void onRecordingStarted(String filePath);
        void onRecordingStopped(String filePath, long durationMs);
        void onError(String message);
    }

    private final Context context;
    private DvrListener listener;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    private final AtomicBoolean isRecording = new AtomicBoolean(false);
    private long startTimeMs = 0;
    private String currentFilePath;

    private MediaProjection mediaProjection;
    private VirtualDisplay virtualDisplay;

    private MediaCodec videoEncoder;
    private Surface videoInputSurface;
    private int videoTrackIndex = -1;

    private boolean recordAudio = false;
    private MediaCodec audioEncoder;
    private AudioRecord audioRecord;
    private int audioTrackIndex = -1;
    private Thread audioRecordThread;

    private MediaMuxer mediaMuxer;
    private boolean muxerStarted = false;
    private final Object muxerLock = new Object();
    private Thread drainThread;

    public DvrRecorder(Context context) {
        this.context = context.getApplicationContext();
    }

    public void setListener(DvrListener listener) {
        this.listener = listener;
    }

    public boolean isRecording() {
        return isRecording.get();
    }

    public long getRecordingDurationMs() {
        if (!isRecording.get() || startTimeMs == 0) return 0;
        return System.currentTimeMillis() - startTimeMs;
    }

    public synchronized boolean start(MediaProjection projection, int width, int height, int dpi, boolean enableMic) {
        if (isRecording.get() || projection == null) return false;

        this.mediaProjection = projection;
        this.recordAudio = enableMic && (ContextCompat.checkSelfPermission(context, android.Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED);

        int encWidth = width & ~1;
        int encHeight = height & ~1;

        File outputFile = createOutputFile();
        if (outputFile == null) {
            notifyError("Failed to create output file");
            return false;
        }
        currentFilePath = outputFile.getAbsolutePath();
        FileLogger.log(TAG, "Starting Native DVR to: " + currentFilePath + " (" + encWidth + "x" + encHeight + "), mic=" + recordAudio);

        try {

            mediaMuxer = new MediaMuxer(currentFilePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4);
            muxerStarted = false;
            videoTrackIndex = -1;
            audioTrackIndex = -1;

            MediaFormat videoFormat = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, encWidth, encHeight);
            videoFormat.setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface);
            videoFormat.setInteger(MediaFormat.KEY_BIT_RATE, 15_000_000);
            videoFormat.setInteger(MediaFormat.KEY_BITRATE_MODE, MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_VBR);
            try {
                videoFormat.setInteger(MediaFormat.KEY_PROFILE, MediaCodecInfo.CodecProfileLevel.AVCProfileHigh);
                videoFormat.setInteger(MediaFormat.KEY_LEVEL, MediaCodecInfo.CodecProfileLevel.AVCLevel41);
            } catch (Exception ignored) {}
            videoFormat.setInteger(MediaFormat.KEY_FRAME_RATE, 60);
            videoFormat.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1);

            videoEncoder = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC);
            videoEncoder.configure(videoFormat, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);
            videoInputSurface = videoEncoder.createInputSurface();
            videoEncoder.start();

            virtualDisplay = mediaProjection.createVirtualDisplay(
                    "WTFViewDVR",
                    encWidth,
                    encHeight,
                    dpi,
                    DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                    videoInputSurface,
                    null,
                    null
            );

            if (recordAudio) {
                initAudioEncoder();
            }

            isRecording.set(true);
            startTimeMs = System.currentTimeMillis();

            startDrainThread();

            if (recordAudio && audioRecord != null) {
                startAudioRecordThread();
            }

            notifyStarted(currentFilePath);
            FileLogger.log(TAG, "Native DVR started successfully!");
            return true;

        } catch (Exception e) {
            FileLogger.logError(TAG, "Failed to start DVR recorder", e);
            cleanup();
            notifyError("DVR recorder failed: " + e.getMessage());
            return false;
        }
    }

    private void initAudioEncoder() {
        try {
            int sampleRate = 44100;
            int channelConfig = AudioFormat.CHANNEL_IN_MONO;
            int audioFormat = AudioFormat.ENCODING_PCM_16BIT;
            int minBufferSize = AudioRecord.getMinBufferSize(sampleRate, channelConfig, audioFormat);
            int bufferSize = Math.max(minBufferSize, 4096 * 2);

            audioRecord = new AudioRecord(
                    MediaRecorder.AudioSource.MIC,
                    sampleRate,
                    channelConfig,
                    audioFormat,
                    bufferSize
            );

            if (audioRecord.getState() != AudioRecord.STATE_INITIALIZED) {
                recordAudio = false;
                audioRecord = null;
                return;
            }

            MediaFormat audioFormatEnc = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, sampleRate, 1);
            audioFormatEnc.setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC);
            audioFormatEnc.setInteger(MediaFormat.KEY_BIT_RATE, 128_000);
            audioFormatEnc.setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, bufferSize);

            audioEncoder = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC);
            audioEncoder.configure(audioFormatEnc, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);
            audioEncoder.start();
            audioRecord.startRecording();
        } catch (Exception e) {
            FileLogger.logError(TAG, "Audio encoder init error", e);
            recordAudio = false;
        }
    }

    private void startDrainThread() {
        drainThread = new Thread(() -> {
            MediaCodec.BufferInfo videoBufferInfo = new MediaCodec.BufferInfo();
            MediaCodec.BufferInfo audioBufferInfo = new MediaCodec.BufferInfo();
            boolean eosReached = false;
            long drainStartTime = 0;

            long videoFormatTime = 0;

            while (true) {
                boolean isRec = isRecording.get();
                if (!isRec) {
                    if (drainStartTime == 0) drainStartTime = System.currentTimeMillis();
                    if (eosReached || (System.currentTimeMillis() - drainStartTime > 1500)) {
                        break;
                    }
                }

                if (!muxerStarted && videoTrackIndex >= 0 && videoFormatTime > 0 && (System.currentTimeMillis() - videoFormatTime > 300)) {
                    synchronized (muxerLock) {
                        if (!muxerStarted && mediaMuxer != null) {
                            try {
                                mediaMuxer.start();
                                muxerStarted = true;
                                recordAudio = false;
                                FileLogger.log(TAG, "Audio format timed out; started MediaMuxer with video only!");
                            } catch (Exception e) {
                                FileLogger.logError(TAG, "Failed to start MediaMuxer with video", e);
                            }
                        }
                    }
                }

                boolean hadActivity = false;

                try {
                    int outputBufferId = videoEncoder != null ? videoEncoder.dequeueOutputBuffer(videoBufferInfo, 10_000) : -1;
                    if (outputBufferId == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                        synchronized (muxerLock) {
                            if (!muxerStarted && mediaMuxer != null) {
                                MediaFormat newFormat = videoEncoder.getOutputFormat();
                                videoTrackIndex = mediaMuxer.addTrack(newFormat);
                                videoFormatTime = System.currentTimeMillis();
                                if (!recordAudio || audioTrackIndex >= 0) {
                                    mediaMuxer.start();
                                    muxerStarted = true;
                                    FileLogger.log(TAG, "MediaMuxer started with video!");
                                }
                            }
                        }
                        hadActivity = true;
                    } else if (outputBufferId >= 0) {
                        ByteBuffer encodedData = videoEncoder.getOutputBuffer(outputBufferId);
                        if (encodedData != null && (videoBufferInfo.flags & MediaCodec.BUFFER_FLAG_CODEC_CONFIG) == 0 && videoBufferInfo.size != 0) {
                            synchronized (muxerLock) {
                                if (muxerStarted && videoTrackIndex >= 0 && mediaMuxer != null) {
                                    encodedData.position(videoBufferInfo.offset);
                                    encodedData.limit(videoBufferInfo.offset + videoBufferInfo.size);
                                    mediaMuxer.writeSampleData(videoTrackIndex, encodedData, videoBufferInfo);
                                }
                            }
                        }
                        if ((videoBufferInfo.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                            eosReached = true;
                            FileLogger.log(TAG, "Video encoder EOS reached");
                        }
                        videoEncoder.releaseOutputBuffer(outputBufferId, false);
                        hadActivity = true;
                    }
                } catch (Exception ignored) {}

                if (recordAudio && audioEncoder != null) {
                    try {
                        int audioBufferId = audioEncoder.dequeueOutputBuffer(audioBufferInfo, 10_000);
                        if (audioBufferId == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                            synchronized (muxerLock) {
                                if (!muxerStarted && mediaMuxer != null) {
                                    MediaFormat newFormat = audioEncoder.getOutputFormat();
                                    audioTrackIndex = mediaMuxer.addTrack(newFormat);
                                    if (videoTrackIndex >= 0) {
                                        mediaMuxer.start();
                                        muxerStarted = true;
                                        FileLogger.log(TAG, "MediaMuxer started with video and audio!");
                                    }
                                }
                            }
                            hadActivity = true;
                        } else if (audioBufferId >= 0) {
                            ByteBuffer encodedData = audioEncoder.getOutputBuffer(audioBufferId);
                            if (encodedData != null && (audioBufferInfo.flags & MediaCodec.BUFFER_FLAG_CODEC_CONFIG) == 0 && audioBufferInfo.size != 0) {
                                synchronized (muxerLock) {
                                    if (muxerStarted && audioTrackIndex >= 0 && mediaMuxer != null) {
                                        encodedData.position(audioBufferInfo.offset);
                                        encodedData.limit(audioBufferInfo.offset + audioBufferInfo.size);
                                        mediaMuxer.writeSampleData(audioTrackIndex, encodedData, audioBufferInfo);
                                    }
                                }
                            }
                            audioEncoder.releaseOutputBuffer(audioBufferId, false);
                            hadActivity = true;
                        }
                    } catch (Exception ignored) {}
                }

                if (!hadActivity) {
                    try {
                        Thread.sleep(5);
                    } catch (InterruptedException ignored) {}
                }
            }
        }, "DvrRecorderDrainThread");
        drainThread.start();
    }

    private void startAudioRecordThread() {
        audioRecordThread = new Thread(() -> {
            byte[] audioBuf = new byte[2048];
            long samplesRecorded = 0;
            final long sampleRate = 44100;
            while (isRecording.get() && audioRecord != null) {
                int read = audioRecord.read(audioBuf, 0, audioBuf.length);
                if (read > 0 && audioEncoder != null) {
                    try {
                        int inputBufferIndex = audioEncoder.dequeueInputBuffer(10_000);
                        if (inputBufferIndex >= 0) {
                            ByteBuffer inputBuffer = audioEncoder.getInputBuffer(inputBufferIndex);
                            if (inputBuffer != null) {
                                inputBuffer.clear();
                                inputBuffer.put(audioBuf, 0, read);

                                long pts = (samplesRecorded * 1_000_000L) / sampleRate;
                                samplesRecorded += (read / 2);
                                audioEncoder.queueInputBuffer(inputBufferIndex, 0, read, pts, 0);
                            }
                        }
                    } catch (Exception ignored) {}
                }
            }
        }, "DvrAudioRecordThread");
        audioRecordThread.start();
    }

    public synchronized void stop() {
        if (!isRecording.get()) return;
        FileLogger.log(TAG, "Stopping Native DVR...");
        isRecording.set(false);
        long duration = System.currentTimeMillis() - startTimeMs;

        if (audioRecord != null) {
            try { audioRecord.stop(); } catch (Exception ignored) {}
        }
        if (audioRecordThread != null) {
            try { audioRecordThread.join(300); } catch (Exception ignored) {}
        }

        if (videoEncoder != null) {
            try {
                videoEncoder.signalEndOfInputStream();
            } catch (Exception ignored) {}
        }

        synchronized (muxerLock) {
            if (!muxerStarted && videoTrackIndex >= 0 && mediaMuxer != null) {
                try {
                    mediaMuxer.start();
                    muxerStarted = true;
                    recordAudio = false;
                } catch (Exception ignored) {}
            }
        }

        if (drainThread != null) {
            try {
                drainThread.join(2000);
            } catch (InterruptedException ignored) {}
        }

        if (virtualDisplay != null) {
            try { virtualDisplay.release(); } catch (Exception ignored) {}
            virtualDisplay = null;
        }

        boolean recordedSuccessfully = muxerStarted;

        cleanup();

        if (currentFilePath != null) {
            File file = new File(currentFilePath);
            if (file.exists() && file.length() > 1024) {
                MediaScannerConnection.scanFile(context, new String[]{currentFilePath}, new String[]{"video/mp4"}, (path, uri) ->
                    FileLogger.log(TAG, "Scanned DVR file into gallery: " + path)
                );
                notifyStopped(currentFilePath, duration);
            } else {
                if (file.exists()) {
                    file.delete();
                }
                notifyError("DVR recording stopped with no video data.");
            }
        }
        FileLogger.log(TAG, "Native DVR recording finished! Duration: " + (duration / 1000) + "s");
    }

    private synchronized void cleanup() {
        if (virtualDisplay != null) {
            try { virtualDisplay.release(); } catch (Exception ignored) {}
            virtualDisplay = null;
        }

        if (mediaProjection != null) {
            try { mediaProjection.stop(); } catch (Exception ignored) {}
            mediaProjection = null;
        }

        if (videoInputSurface != null) {
            try { videoInputSurface.release(); } catch (Exception ignored) {}
            videoInputSurface = null;
        }

        if (videoEncoder != null) {
            try {
                videoEncoder.stop();
                videoEncoder.release();
            } catch (Exception ignored) {}
            videoEncoder = null;
        }

        if (audioRecord != null) {
            try {
                audioRecord.stop();
                audioRecord.release();
            } catch (Exception ignored) {}
            audioRecord = null;
        }

        if (audioEncoder != null) {
            try {
                audioEncoder.stop();
                audioEncoder.release();
            } catch (Exception ignored) {}
            audioEncoder = null;
        }

        synchronized (muxerLock) {
            if (mediaMuxer != null) {
                try {
                    if (muxerStarted) {
                        mediaMuxer.stop();
                    }
                } catch (Exception e) {
                    FileLogger.logError(TAG, "mediaMuxer.stop error", e);
                }
                try {
                    mediaMuxer.release();
                } catch (Exception e) {
                    FileLogger.logError(TAG, "mediaMuxer.release error", e);
                }
                mediaMuxer = null;
                muxerStarted = false;
            }
        }
    }

    private File createOutputFile() {
        File dir = new File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MOVIES), "WTFView");
        if (!dir.exists() && !dir.mkdirs()) {
            dir = context.getExternalFilesDir(Environment.DIRECTORY_MOVIES);
        }
        if (dir == null) return null;

        String timestamp = new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(new Date());
        return new File(dir, "DVR_" + timestamp + ".mp4");
    }

    private void notifyStarted(String path) {
        if (listener != null) {
            mainHandler.post(() -> listener.onRecordingStarted(path));
        }
    }

    private void notifyStopped(String path, long durationMs) {
        if (listener != null) {
            mainHandler.post(() -> listener.onRecordingStopped(path, durationMs));
        }
    }

    private void notifyError(String msg) {
        if (listener != null) {
            mainHandler.post(() -> listener.onError(msg));
        }
    }
}
