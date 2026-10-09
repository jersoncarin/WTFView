package com.jersnet.wtfview.dvr;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.media.projection.MediaProjection;
import android.media.projection.MediaProjectionManager;
import android.os.Build;
import android.os.IBinder;

import androidx.core.app.NotificationCompat;

import com.jersnet.wtfview.FileLogger;
import com.jersnet.wtfview.MainActivity;

public class DvrService extends Service {
    private static final String TAG = "DvrService";
    private static final String CHANNEL_ID = "wtfview_dvr_channel";
    private static final int NOTIFICATION_ID = 7655;

    public static final String ACTION_START = "com.jersnet.wtfview.dvr.ACTION_START";
    public static final String ACTION_STOP = "com.jersnet.wtfview.dvr.ACTION_STOP";
    public static final String EXTRA_RESULT_CODE = "extra_result_code";
    public static final String EXTRA_RESULT_DATA = "extra_result_data";
    public static final String EXTRA_WIDTH = "extra_width";
    public static final String EXTRA_HEIGHT = "extra_height";
    public static final String EXTRA_DPI = "extra_dpi";
    public static final String EXTRA_ENABLE_MIC = "extra_enable_mic";

    private static DvrRecorder dvrRecorder;
    private static DvrRecorder.DvrListener globalListener;

    public static void setGlobalListener(DvrRecorder.DvrListener listener) {
        globalListener = listener;
        if (dvrRecorder != null) {
            dvrRecorder.setListener(listener);
        }
    }

    public static boolean isRecording() {
        return dvrRecorder != null && dvrRecorder.isRecording();
    }

    public static long getRecordingDurationMs() {
        return (dvrRecorder != null) ? dvrRecorder.getRecordingDurationMs() : 0;
    }

    public static void stop(Context context) {
        Intent intent = new Intent(context, DvrService.class);
        intent.setAction(ACTION_STOP);
        context.startService(intent);
    }

    @Override
    public void onCreate() {
        super.onCreate();
        createNotificationChannel();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent == null) return START_NOT_STICKY;

        String action = intent.getAction();
        if (ACTION_START.equals(action)) {
            int resultCode = intent.getIntExtra(EXTRA_RESULT_CODE, 0);
            Intent resultData = intent.getParcelableExtra(EXTRA_RESULT_DATA);
            int width = intent.getIntExtra(EXTRA_WIDTH, 1920);
            int height = intent.getIntExtra(EXTRA_HEIGHT, 1080);
            int dpi = intent.getIntExtra(EXTRA_DPI, 400);
            boolean enableMic = intent.getBooleanExtra(EXTRA_ENABLE_MIC, false);

            Notification notification = buildNotification();
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                int type = ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION;
                if (enableMic && Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    type |= ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE;
                }
                startForeground(NOTIFICATION_ID, notification, type);
            } else {
                startForeground(NOTIFICATION_ID, notification);
            }

            MediaProjectionManager mpm = (MediaProjectionManager) getSystemService(Context.MEDIA_PROJECTION_SERVICE);
            if (mpm != null && resultData != null) {
                MediaProjection projection = mpm.getMediaProjection(resultCode, resultData);
                if (projection != null) {
                    if (dvrRecorder != null && dvrRecorder.isRecording()) {
                        dvrRecorder.stop();
                    }
                    dvrRecorder = new DvrRecorder(this);
                    if (globalListener != null) {
                        dvrRecorder.setListener(globalListener);
                    }
                    dvrRecorder.start(projection, width, height, dpi, enableMic);
                } else {
                    FileLogger.log(TAG, "Failed to get MediaProjection instance");
                    stopSelf();
                }
            } else {
                FileLogger.log(TAG, "MediaProjectionManager or resultData is null");
                stopSelf();
            }

        } else if (ACTION_STOP.equals(action)) {
            if (dvrRecorder != null) {
                dvrRecorder.stop();
                dvrRecorder = null;
            }
            stopForeground(true);
            stopSelf();
        }

        return START_NOT_STICKY;
    }

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(
                    CHANNEL_ID,
                    "WTFView DVR",
                    NotificationManager.IMPORTANCE_LOW
            );
            channel.setDescription("Shows notification when WTFView DVR is recording");
            NotificationManager nm = getSystemService(NotificationManager.class);
            if (nm != null) {
                nm.createNotificationChannel(channel);
            }
        }
    }

    private Notification buildNotification() {
        Intent stopIntent = new Intent(this, DvrService.class);
        stopIntent.setAction(ACTION_STOP);
        PendingIntent stopPendingIntent = PendingIntent.getService(
                this, 1, stopIntent,
                PendingIntent.FLAG_UPDATE_CURRENT | (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M ? PendingIntent.FLAG_IMMUTABLE : 0)
        );

        Intent openIntent = new Intent(this, MainActivity.class);
        PendingIntent openPendingIntent = PendingIntent.getActivity(
                this, 0, openIntent,
                PendingIntent.FLAG_UPDATE_CURRENT | (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M ? PendingIntent.FLAG_IMMUTABLE : 0)
        );

        return new NotificationCompat.Builder(this, CHANNEL_ID)
                .setContentTitle("WTFView DVR Recording")
                .setContentText("Recording FPV video + Betaflight OSD...")
                .setSmallIcon(android.R.drawable.presence_video_online)
                .setContentIntent(openPendingIntent)
                .addAction(android.R.drawable.ic_media_pause, "Stop", stopPendingIntent)
                .setOngoing(true)
                .build();
    }

    @Override
    public void onDestroy() {
        if (dvrRecorder != null) {
            dvrRecorder.stop();
            dvrRecorder = null;
        }
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}
