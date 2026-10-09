package com.jersnet.wtfview;

import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbDeviceConnection;
import android.hardware.usb.UsbInterface;
import android.hardware.usb.UsbManager;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.SurfaceView;
import android.view.View;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.ActionBar;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.SwitchCompat;
import androidx.constraintlayout.widget.ConstraintLayout;

import android.Manifest;
import android.content.pm.PackageManager;
import android.media.projection.MediaProjectionManager;
import android.util.DisplayMetrics;
import android.view.SurfaceHolder;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import com.jersnet.wtfview.adb.AdbUsbClient;
import com.jersnet.wtfview.dvr.DvrRecorder;
import com.jersnet.wtfview.dvr.DvrService;
import com.jersnet.wtfview.osd.FontManager;
import com.jersnet.wtfview.osd.OsdManager;
import com.jersnet.wtfview.osd.OsdView;
import com.jersnet.wtfview.usb.UsbMaskConnection;
import com.jersnet.wtfview.video.VideoReaderExoplayer;

import java.io.ByteArrayOutputStream;
import java.util.HashMap;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicBoolean;

public class MainActivity extends AppCompatActivity {
    private static final String TAG = "MainActivity";
    private static final String ACTION_USB_PERMISSION = "com.jersnet.wtfview.USB_PERMISSION";
    private static final int DJI_VENDOR_ID = 11427;
    private static final int DJI_PRODUCT_ID = 31;

    private static final String PREFS_NAME = "wtfview_prefs";
    private static final String PREF_OSD_ENABLED = "pref_osd_enabled";
    private static final String PREF_AU_HUD_ENABLED = "pref_au_hud_enabled";
    private static final String PREF_STRETCH_ENABLED = "pref_stretch_enabled";
    private static final String PREF_DEBUG_LOG_ENABLED = "pref_debug_log_enabled";
    private static final String PREF_RECORD_MIC = "pref_record_mic";

    private View btnRecord;
    private ImageView ivRecDot;
    private TextView tvRecStatus;
    private ActivityResultLauncher<Intent> mediaProjectionLauncher;
    private final Handler dvrTimerHandler = new Handler(Looper.getMainLooper());
    private final Runnable dvrTimerRunnable = new Runnable() {
        @Override
        public void run() {
            if (DvrService.isRecording()) {
                long durationSec = DvrService.getRecordingDurationMs() / 1000;
                long mins = durationSec / 60;
                long secs = durationSec % 60;
                if (tvRecStatus != null) {
                    tvRecStatus.setText(String.format(Locale.US, "%02d:%02d", mins, secs));
                }
                dvrTimerHandler.postDelayed(this, 1000);
            }
        }
    };

    private SharedPreferences prefs;

    private UsbManager usbManager;
    private UsbDevice currentDevice;
    private UsbDeviceConnection videoConnection;
    private UsbDeviceConnection adbConnection;

    private UsbMaskConnection usbMaskConnection;
    private VideoReaderExoplayer videoReader;
    private AdbUsbClient adbClient;

    private FontManager fontManager;
    private OsdManager osdManager;
    private OsdView osdView;
    private SurfaceView fpvView;
    private View mainLayout;

    private View waitingContainer;
    private TextView statusText;
    private ImageView waitingDot;

    private View auHudContainer;
    private TextView tvTemp;
    private TextView tvVoltage;

    private View debugLogContainer;
    private TextView tvDebugLogs;
    private ScrollView debugScrollView;
    private TextView btnClearDebugLogs;
    private final StringBuilder logBuffer = new StringBuilder();
    private final Handler logHandler = new Handler(Looper.getMainLooper());
    private boolean logUpdatePending = false;

    private float lastVoltage = 0f;
    private int lastTemp = 0;
    private String lastFc = "BTFL";

    private ImageButton btnSettings;
    private final Runnable dimSettingsRunnable = () -> {
        if (btnSettings != null) {
            btnSettings.animate().alpha(0.35f).setDuration(1000).start();
        }
    };

    private volatile boolean isConnected = false;
    private final AtomicBoolean isConnecting = new AtomicBoolean(false);
    private long lastConnectAttemptTime = 0;

    private final BroadcastReceiver usbReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            String action = intent.getAction();
            FileLogger.log(TAG, "Broadcast received: " + action);
            if (ACTION_USB_PERMISSION.equals(action)) {
                synchronized (this) {
                    UsbDevice device = intent.getParcelableExtra(UsbManager.EXTRA_DEVICE);
                    if (intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)) {
                        FileLogger.log(TAG, "USB permission granted for " + device);
                        if (device != null) {
                            connectDevice(device);
                        }
                    } else {
                        FileLogger.log(TAG, "USB permission denied by user");
                        setStatus("USB permission denied.");
                    }
                }
            } else if (UsbManager.ACTION_USB_DEVICE_ATTACHED.equals(action)) {
                FileLogger.log(TAG, "USB device attached, connecting in 300ms...");
                watchdogHandler.postDelayed(() -> checkAndConnect(), 300);
            } else if (UsbManager.ACTION_USB_DEVICE_DETACHED.equals(action)) {
                FileLogger.log(TAG, "USB device detached");
                disconnectDevice();
                setStatus("Waiting for DJI Goggles...");
            }
        }
    };

    private final Handler watchdogHandler = new Handler(Looper.getMainLooper());
    private final Runnable watchdogRunnable = new Runnable() {
        @Override
        public void run() {
            if (!isConnected && !isConnecting.get()) {
                checkAndConnect();
            }
            watchdogHandler.postDelayed(this, 1500);
        }
    };

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        FileLogger.log(TAG, "onNewIntent called - reusing existing activity instance");
        checkAndConnect();
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        FileLogger.init(this);
        FileLogger.log(TAG, "onCreate called");
        setContentView(R.layout.activity_main);

        prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);

        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        View decorView = getWindow().getDecorView();
        decorView.setSystemUiVisibility(
                View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                | View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                | View.SYSTEM_UI_FLAG_FULLSCREEN);

        ActionBar actionBar = getSupportActionBar();
        if (actionBar != null) actionBar.hide();

        mainLayout = findViewById(R.id.mainLayout);
        fpvView = findViewById(R.id.fpvView);
        osdView = findViewById(R.id.osdView);

        boolean osdEnabled = prefs.getBoolean(PREF_OSD_ENABLED, true);
        osdView.setVisibility(osdEnabled ? View.VISIBLE : View.GONE);

        waitingContainer = findViewById(R.id.waitingContainer);
        statusText = findViewById(R.id.statusText);
        waitingDot = findViewById(R.id.waitingDot);

        auHudContainer = findViewById(R.id.auHudContainer);
        tvTemp = findViewById(R.id.tvTemp);
        tvVoltage = findViewById(R.id.tvVoltage);

        debugLogContainer = findViewById(R.id.debugLogContainer);
        tvDebugLogs = findViewById(R.id.tvDebugLogs);
        debugScrollView = findViewById(R.id.debugScrollView);
        btnClearDebugLogs = findViewById(R.id.btnClearDebugLogs);

        boolean debugLogEnabled = prefs.getBoolean(PREF_DEBUG_LOG_ENABLED, false);
        debugLogContainer.setVisibility(debugLogEnabled ? View.VISIBLE : View.GONE);
        FileLogger.setDebugEnabled(debugLogEnabled);

        if (btnClearDebugLogs != null) {
            btnClearDebugLogs.setOnClickListener(v -> {
                FileLogger.clearLogs();
                synchronized (logBuffer) {
                    logBuffer.setLength(0);
                }
                tvDebugLogs.setText("");
            });
        }

        FileLogger.setLogListener(this::onLogReceived);

        btnRecord = findViewById(R.id.btnRecord);
        ivRecDot = findViewById(R.id.ivRecDot);
        tvRecStatus = findViewById(R.id.tvRecStatus);

        mediaProjectionLauncher = registerForActivityResult(
                new ActivityResultContracts.StartActivityForResult(),
                result -> {
                    if (result.getResultCode() == RESULT_OK && result.getData() != null) {
                        startDvrService(result.getResultCode(), result.getData());
                    } else {
                        Toast.makeText(this, "Screen capture permission denied", Toast.LENGTH_SHORT).show();
                    }
                }
        );

        DvrService.setGlobalListener(new DvrRecorder.DvrListener() {
            @Override
            public void onRecordingStarted(String filePath) {
                runOnUiThread(() -> {
                    updateDvrUi(true);
                    Toast.makeText(MainActivity.this, "DVR Recording Started", Toast.LENGTH_SHORT).show();
                });
            }

            @Override
            public void onRecordingStopped(String filePath, long durationMs) {
                runOnUiThread(() -> {
                    updateDvrUi(false);
                    Toast.makeText(MainActivity.this, "DVR Saved to Movies/WTFView (" + (durationMs / 1000) + "s)", Toast.LENGTH_LONG).show();
                });
            }

            @Override
            public void onError(String message) {
                runOnUiThread(() -> {
                    updateDvrUi(false);
                    Toast.makeText(MainActivity.this, "DVR Error: " + message, Toast.LENGTH_SHORT).show();
                });
            }
        });

        if (btnRecord != null) {
            btnRecord.setOnClickListener(v -> toggleDvrRecording());
        }

        btnSettings = findViewById(R.id.btnSettings);
        btnSettings.setOnClickListener(v -> showSettingsDialog());

        mainLayout.setOnClickListener(v -> {
            btnSettings.animate().alpha(1.0f).setDuration(200).start();
            btnSettings.removeCallbacks(dimSettingsRunnable);
            btnSettings.postDelayed(dimSettingsRunnable, 3500);

            if (btnRecord != null) {
                btnRecord.animate().alpha(1.0f).setDuration(200).start();
            }
        });

        applyAspectRatio(prefs.getBoolean(PREF_STRETCH_ENABLED, false));

        fontManager = new FontManager(this);
        osdView.setFontManager(fontManager);
        osdManager = new OsdManager(this, osdView, fontManager);
        osdManager.setTelemetryListener(this::onTelemetryReceived);

        usbManager = (UsbManager) getSystemService(Context.USB_SERVICE);
        usbMaskConnection = new UsbMaskConnection();

        Handler videoHandler = new Handler(Looper.getMainLooper(), msg -> {
            if (VideoReaderExoplayer.VideoReaderEventMessageCode.VIDEO_PLAYING.equals(msg.obj)) {
                FileLogger.log(TAG, "Video playback started, hiding waiting container");
                waitingContainer.setVisibility(View.GONE);
                if (prefs.getBoolean(PREF_AU_HUD_ENABLED, true)) {
                    auHudContainer.setVisibility(View.VISIBLE);
                }
                btnSettings.postDelayed(dimSettingsRunnable, 2000);
            }
            return false;
        });

        videoReader = new VideoReaderExoplayer(fpvView, this, videoHandler);

        IntentFilter filter = new IntentFilter(ACTION_USB_PERMISSION);
        filter.addAction(UsbManager.ACTION_USB_DEVICE_ATTACHED);
        filter.addAction(UsbManager.ACTION_USB_DEVICE_DETACHED);
        registerReceiver(usbReceiver, filter);

        checkAndConnect();
    }

    private void onLogReceived(String line) {
        synchronized (logBuffer) {
            logBuffer.append(line).append("\n");
            if (logBuffer.length() > 20000) {
                logBuffer.delete(0, 6000);
            }
        }
        if (!logUpdatePending) {
            logUpdatePending = true;
            logHandler.postDelayed(() -> {
                logUpdatePending = false;
                if (tvDebugLogs != null) {
                    synchronized (logBuffer) {
                        tvDebugLogs.setText(logBuffer.toString());
                    }
                    if (debugScrollView != null) {
                        debugScrollView.post(() -> debugScrollView.fullScroll(View.FOCUS_DOWN));
                    }
                }
            }, 100);
        }
    }

    private void applyAspectRatio(boolean stretch) {
        ConstraintLayout.LayoutParams lp = (ConstraintLayout.LayoutParams) fpvView.getLayoutParams();
        if (stretch) {
            lp.dimensionRatio = null;
            lp.width = ConstraintLayout.LayoutParams.MATCH_PARENT;
            lp.height = ConstraintLayout.LayoutParams.MATCH_PARENT;
        } else {
            lp.dimensionRatio = "16:9";
            lp.width = 0;
            lp.height = 0;
        }
        fpvView.setLayoutParams(lp);
    }

    private void onTelemetryReceived(int temp, float voltage, String fc) {
        this.lastTemp = temp;
        this.lastVoltage = voltage;
        if (fc != null && !fc.isEmpty()) {
            this.lastFc = fc;
        }
        runOnUiThread(this::renderTelemetryHud);
    }

    private void renderTelemetryHud() {
        if (!prefs.getBoolean(PREF_AU_HUD_ENABLED, true)) {
            auHudContainer.setVisibility(View.GONE);
            return;
        }
        if (isConnected) {
            auHudContainer.setVisibility(View.VISIBLE);
        }

        if (lastTemp > 0) {
            tvTemp.setText(String.format(Locale.US, "%d°C", lastTemp));
            if (lastTemp >= 80) {
                tvTemp.setTextColor(Color.parseColor("#EF4444"));
            } else {
                tvTemp.setTextColor(Color.WHITE);
            }
        }

        if (lastVoltage > 0) {
            tvVoltage.setText(String.format(Locale.US, "%.1fV", lastVoltage));
            tvVoltage.setTextColor(Color.WHITE);
        }
    }

    private void showSettingsDialog() {
        AlertDialog.Builder builder = new AlertDialog.Builder(this);
        View dialogView = getLayoutInflater().inflate(R.layout.dialog_settings, null);
        builder.setView(dialogView);
        AlertDialog dialog = builder.create();
        if (dialog.getWindow() != null) {
            dialog.getWindow().setBackgroundDrawableResource(android.R.color.transparent);
        }

        SwitchCompat switchOsd = dialogView.findViewById(R.id.switchOsd);
        SwitchCompat switchAuHud = dialogView.findViewById(R.id.switchAuHud);
        SwitchCompat switchStretch = dialogView.findViewById(R.id.switchStretch);
        SwitchCompat switchDebugLog = dialogView.findViewById(R.id.switchDebugLog);
        ImageButton btnClose = dialogView.findViewById(R.id.btnCloseSettings);

        boolean osdEnabled = prefs.getBoolean(PREF_OSD_ENABLED, true);
        boolean auHudEnabled = prefs.getBoolean(PREF_AU_HUD_ENABLED, true);
        boolean stretchEnabled = prefs.getBoolean(PREF_STRETCH_ENABLED, false);
        boolean debugLogEnabled = prefs.getBoolean(PREF_DEBUG_LOG_ENABLED, false);

        switchOsd.setChecked(osdEnabled);
        switchAuHud.setChecked(auHudEnabled);
        switchStretch.setChecked(stretchEnabled);
        switchDebugLog.setChecked(debugLogEnabled);

        switchOsd.setOnCheckedChangeListener((btn, isChecked) -> {
            prefs.edit().putBoolean(PREF_OSD_ENABLED, isChecked).apply();
            osdView.setVisibility(isChecked ? View.VISIBLE : View.GONE);
        });

        switchAuHud.setOnCheckedChangeListener((btn, isChecked) -> {
            prefs.edit().putBoolean(PREF_AU_HUD_ENABLED, isChecked).apply();
            if (isChecked && isConnected) {
                auHudContainer.setVisibility(View.VISIBLE);
                renderTelemetryHud();
            } else {
                auHudContainer.setVisibility(View.GONE);
            }
        });

        switchStretch.setOnCheckedChangeListener((btn, isChecked) -> {
            prefs.edit().putBoolean(PREF_STRETCH_ENABLED, isChecked).apply();
            applyAspectRatio(isChecked);
        });

        switchDebugLog.setOnCheckedChangeListener((btn, isChecked) -> {
            prefs.edit().putBoolean(PREF_DEBUG_LOG_ENABLED, isChecked).apply();
            debugLogContainer.setVisibility(isChecked ? View.VISIBLE : View.GONE);
            FileLogger.setDebugEnabled(isChecked);
            if (isChecked && debugScrollView != null) {
                debugScrollView.post(() -> debugScrollView.fullScroll(View.FOCUS_DOWN));
            }
        });

        SwitchCompat switchRecordMic = dialogView.findViewById(R.id.switchRecordMic);
        boolean recordMicEnabled = prefs.getBoolean(PREF_RECORD_MIC, false);
        if (switchRecordMic != null) {
            switchRecordMic.setChecked(recordMicEnabled);
            switchRecordMic.setOnCheckedChangeListener((btn, isChecked) -> {
                prefs.edit().putBoolean(PREF_RECORD_MIC, isChecked).apply();
                if (isChecked && ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
                    ActivityCompat.requestPermissions(this, new String[]{Manifest.permission.RECORD_AUDIO}, 101);
                }
            });
        }

        Button btnSyncFont = dialogView.findViewById(R.id.btnSyncFont);
        TextView tvFontStatus = dialogView.findViewById(R.id.tvFontStatus);
        updateFontStatusText(tvFontStatus);

        if (btnSyncFont != null) {
            btnSyncFont.setOnClickListener(v -> {
                if (!isConnected || adbClient == null) {
                    Toast.makeText(this, "Connect DJI Goggles first to sync font", Toast.LENGTH_SHORT).show();
                    return;
                }

                btnSyncFont.setEnabled(false);
                btnSyncFont.setText("Syncing...");
                if (tvFontStatus != null) {
                    tvFontStatus.setText("Status: Pulling font from goggles...");
                    tvFontStatus.setTextColor(Color.parseColor("#38BDF8"));
                }

                new Thread(() -> {

                    if (adbClient != null) {
                        adbClient.stopStream();
                    }

                    boolean success = osdManager.syncGogglesFont(adbClient);

                    startAdbStream();

                    runOnUiThread(() -> {
                        btnSyncFont.setEnabled(true);
                        btnSyncFont.setText("Sync Font");
                        updateFontStatusText(tvFontStatus);
                        if (success) {
                            Toast.makeText(this, "Font synced from goggles and cached!", Toast.LENGTH_SHORT).show();
                        } else {
                            Toast.makeText(this, "No custom font found on goggles (using bundled font)", Toast.LENGTH_LONG).show();
                        }
                    });
                }, "FontSyncManualThread").start();
            });
        }

        TextView tvAppVersion = dialogView.findViewById(R.id.tvAppVersion);
        if (tvAppVersion != null) {
            tvAppVersion.setText("Version " + BuildConfig.VERSION_NAME);
        }

        View layoutDevLink = dialogView.findViewById(R.id.layoutDevLink);
        if (layoutDevLink != null) {
            layoutDevLink.setOnClickListener(v -> openUrl("https://github.com/jersoncarin"));
        }

        View layoutFpvWtfLink = dialogView.findViewById(R.id.layoutFpvWtfLink);
        if (layoutFpvWtfLink != null) {
            layoutFpvWtfLink.setOnClickListener(v -> openUrl("https://github.com/fpv-wtf/"));
        }

        View layoutDigiViewLink = dialogView.findViewById(R.id.layoutDigiViewLink);
        if (layoutDigiViewLink != null) {
            layoutDigiViewLink.setOnClickListener(v -> openUrl("https://github.com/fpvout/DigiView-Android"));
        }

        btnClose.setOnClickListener(v -> dialog.dismiss());
        dialog.show();

        if (dialog.getWindow() != null) {
            dialog.getWindow().setBackgroundDrawableResource(android.R.color.transparent);
            DisplayMetrics dm = getResources().getDisplayMetrics();
            int width = Math.min((int) (dm.widthPixels * 0.88f), (int) (520 * dm.density));
            int height = (int) (dm.heightPixels * 0.85f);
            dialog.getWindow().setLayout(width, height);
            dialog.getWindow().setGravity(android.view.Gravity.CENTER);
        }
    }

    private void openUrl(String url) {
        try {
            Intent intent = new Intent(Intent.ACTION_VIEW, Uri.parse(url));
            startActivity(intent);
        } catch (Exception e) {
            Toast.makeText(this, "Could not open link: " + url, Toast.LENGTH_SHORT).show();
        }
    }

    private void updateFontStatusText(TextView tv) {
        if (tv == null) return;
        if (FontManager.hasCachedFont(this)) {
            tv.setText("Status: Cached Goggles Font");
            tv.setTextColor(Color.parseColor("#4ADE80"));
        } else {
            tv.setText("Status: Bundled Default Font");
            tv.setTextColor(Color.parseColor("#94A3B8"));
        }
    }

    private void setStatus(String msg) {
        FileLogger.log(TAG, "Status: " + msg);
        runOnUiThread(() -> {
            waitingContainer.setVisibility(View.VISIBLE);
            statusText.setText(msg);
            if (msg.contains("connected") || msg.contains("Starting")) {
                waitingDot.setImageResource(R.drawable.dot_indicator_green);
            } else {
                waitingDot.setImageResource(R.drawable.dot_indicator_amber);
            }
        });
    }

    private synchronized void checkAndConnect() {
        if (isConnected || isConnecting.get()) return;
        long now = System.currentTimeMillis();
        if (now - lastConnectAttemptTime < 400) return;
        lastConnectAttemptTime = now;

        HashMap<String, UsbDevice> devices = usbManager.getDeviceList();
        FileLogger.log(TAG, "Scanning USB devices. Found count: " + devices.size());
        for (UsbDevice device : devices.values()) {
            if (device.getVendorId() == DJI_VENDOR_ID && device.getProductId() == DJI_PRODUCT_ID) {
                if (usbManager.hasPermission(device)) {
                    FileLogger.log(TAG, "Has permission for DJI device. Connecting...");
                    connectDevice(device);
                } else {
                    FileLogger.log(TAG, "Requesting permission for DJI device...");
                    PendingIntent pi = PendingIntent.getBroadcast(this, 0, new Intent(ACTION_USB_PERMISSION), PendingIntent.FLAG_IMMUTABLE);
                    usbManager.requestPermission(device, pi);
                    setStatus("Requesting USB permission...");
                }
                return;
            }
        }
        setStatus("Waiting for DJI Goggles...");
    }

    private synchronized void connectDevice(UsbDevice device) {
        if (isConnected || !isConnecting.compareAndSet(false, true)) return;
        currentDevice = device;

        FileLogger.log(TAG, "Connecting to DJI Goggles. Total interfaces: " + device.getInterfaceCount());

        videoConnection = usbManager.openDevice(device);
        if (videoConnection == null) {
            FileLogger.logError(TAG, "Failed to open video UsbDeviceConnection!", null);
            setStatus("Failed to open USB video.");
            isConnecting.set(false);
            return;
        }

        setStatus("DJI Goggles connected! Starting video...");

        if (usbMaskConnection.setUsbDevice(videoConnection, device)) {
            videoReader.setUsbMaskConnection(usbMaskConnection);
            usbMaskConnection.start();
            videoReader.start();
            FileLogger.log(TAG, "Video pipeline started");
        } else {
            FileLogger.logError(TAG, "Failed to configure video interface 3", null);
        }

        isConnected = true;
        isConnecting.set(false);

        adbConnection = usbManager.openDevice(device);
        if (adbConnection != null) {
            adbClient = AdbUsbClient.findAndCreate(adbConnection, device);
            if (adbClient != null && adbClient.connect()) {
                FileLogger.log(TAG, "Connected to Goggles ADB!");
                startOsdStream();
            } else {
                FileLogger.log(TAG, "ADB handshake failed or interface not found");
            }
        } else {
            FileLogger.log(TAG, "Failed to open UsbDeviceConnection for ADB");
        }
    }

    private synchronized void startOsdStream() {
        if (adbClient == null) {
            FileLogger.log(TAG, "startOsdStream: adbClient is null");
            return;
        }

        new Thread(() -> {

            try (java.io.InputStream is = getAssets().open("wtf_forwarder")) {
                ByteArrayOutputStream baos = new ByteArrayOutputStream();
                byte[] buf = new byte[4096];
                int r;
                while ((r = is.read(buf)) != -1) {
                    baos.write(buf, 0, r);
                }
                byte[] bin = baos.toByteArray();
                boolean pushed = adbClient.pushFile("/tmp/wtf_forwarder", bin, 2000);
                FileLogger.log(TAG, "Pushed wtf_forwarder: " + pushed + " (" + bin.length + " bytes)");
            } catch (Exception e) {
                FileLogger.log(TAG, "wtf_forwarder push notice: " + e.getMessage());
            }

            if (!FontManager.hasCachedFont(this)) {
                FileLogger.log(TAG, "No cached goggles font found. Pulling from goggles on initialize...");
                boolean pulled = osdManager.syncGogglesFont(adbClient);
                FileLogger.log(TAG, "Initialize font sync result: " + pulled);
            } else {
                FileLogger.log(TAG, "Goggles font already cached, skipping pull on initialize.");
            }

            osdManager.getPcapParser().reset();

            boolean osdEnabled = prefs.getBoolean(PREF_OSD_ENABLED, true);
            runOnUiThread(() -> osdView.setVisibility(osdEnabled ? View.VISIBLE : View.GONE));

            startAdbStream();
        }, "AdbInitThread").start();
    }

    private synchronized void startAdbStream() {
        if (adbClient == null) return;
        String streamCmd = "exec:chmod 755 /tmp/wtf_forwarder 2>/dev/null; killall wtf_forwarder wtf_fwd tcpdump 2>/dev/null; nice -n 19 /tmp/wtf_forwarder 2>/dev/null";
        FileLogger.log(TAG, "Starting ADB stream: " + streamCmd);
        adbClient.startCommandStream(streamCmd, new AdbUsbClient.StreamCallback() {
            @Override
            public void onData(byte[] data, int offset, int length) {
                osdManager.getPcapParser().feedData(data, offset, length);
            }

            @Override
            public void onClose() {
                FileLogger.log(TAG, "OSD stream closed");
            }
        });
    }

    private synchronized void disconnectDevice() {
        if (!isConnected && videoConnection == null && adbConnection == null) return;
        FileLogger.log(TAG, "disconnectDevice called");
        isConnected = false;
        isConnecting.set(false);

        if (DvrService.isRecording()) {
            FileLogger.log(TAG, "Goggles disconnected during recording: auto-finalizing DVR cleanly");
            DvrService.stop(this);
            runOnUiThread(() -> Toast.makeText(this, "DVR Auto-Saved (USB Disconnected)", Toast.LENGTH_SHORT).show());
        }

        runOnUiThread(() -> {
            auHudContainer.setVisibility(View.GONE);
            osdView.clear();
        });

        if (adbClient != null) {
            adbClient.stop();
            adbClient = null;
        }
        if (adbConnection != null) {
            try {
                adbConnection.close();
            } catch (Exception ignored) {}
            adbConnection = null;
        }

        if (videoReader != null) {
            videoReader.stop();
        }
        if (usbMaskConnection != null) {
            usbMaskConnection.stop();
        }
        if (videoConnection != null) {
            try {
                videoConnection.close();
            } catch (Exception ignored) {}
            videoConnection = null;
        }
        currentDevice = null;
    }

    private void toggleDvrRecording() {
        if (DvrService.isRecording()) {
            DvrService.stop(this);
        } else {
            boolean recordMic = prefs.getBoolean(PREF_RECORD_MIC, false);
            if (recordMic && ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
                ActivityCompat.requestPermissions(this, new String[]{Manifest.permission.RECORD_AUDIO}, 101);
                return;
            }
            startScreenCapture();
        }
    }

    private void startScreenCapture() {
        MediaProjectionManager mpm = (MediaProjectionManager) getSystemService(Context.MEDIA_PROJECTION_SERVICE);
        if (mpm != null) {
            mediaProjectionLauncher.launch(mpm.createScreenCaptureIntent());
        }
    }

    private void startDvrService(int resultCode, Intent data) {
        DisplayMetrics metrics = new DisplayMetrics();
        getWindowManager().getDefaultDisplay().getRealMetrics(metrics);
        int width = metrics.widthPixels;
        int height = metrics.heightPixels;
        int dpi = metrics.densityDpi;
        boolean enableMic = prefs.getBoolean(PREF_RECORD_MIC, false);

        Intent serviceIntent = new Intent(this, DvrService.class);
        serviceIntent.setAction(DvrService.ACTION_START);
        serviceIntent.putExtra(DvrService.EXTRA_RESULT_CODE, resultCode);
        serviceIntent.putExtra(DvrService.EXTRA_RESULT_DATA, data);
        serviceIntent.putExtra(DvrService.EXTRA_WIDTH, width);
        serviceIntent.putExtra(DvrService.EXTRA_HEIGHT, height);
        serviceIntent.putExtra(DvrService.EXTRA_DPI, dpi);
        serviceIntent.putExtra(DvrService.EXTRA_ENABLE_MIC, enableMic);

        ContextCompat.startForegroundService(this, serviceIntent);
    }

    private void updateDvrUi(boolean isRecording) {
        if (isRecording) {

            auHudContainer.setVisibility(View.GONE);
            btnSettings.setVisibility(View.GONE);
            if (debugLogContainer != null) {
                debugLogContainer.setVisibility(View.GONE);
            }
            if (tvRecStatus != null) {
                tvRecStatus.setText("00:00");
                tvRecStatus.setTextColor(Color.parseColor("#EF4444"));
            }
            dvrTimerHandler.post(dvrTimerRunnable);
            if (ivRecDot != null) {
                ivRecDot.animate().alpha(0.2f).setDuration(500).withEndAction(() ->
                    ivRecDot.animate().alpha(1.0f).setDuration(500).start()
                ).start();
            }
            if (btnRecord != null) {
                btnRecord.animate().alpha(0.35f).setDuration(1200).setStartDelay(3000).start();
            }
        } else {
            dvrTimerHandler.removeCallbacks(dvrTimerRunnable);
            if (tvRecStatus != null) {
                tvRecStatus.setText("REC");
                tvRecStatus.setTextColor(Color.WHITE);
            }
            if (ivRecDot != null) {
                ivRecDot.animate().cancel();
                ivRecDot.setAlpha(1.0f);
            }
            if (btnRecord != null) {
                btnRecord.animate().cancel();
                btnRecord.setAlpha(1.0f);
            }

            if (prefs.getBoolean(PREF_AU_HUD_ENABLED, true) && isConnected) {
                auHudContainer.setVisibility(View.VISIBLE);
                renderTelemetryHud();
            }
            btnSettings.setVisibility(View.VISIBLE);
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == 101) {
            if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                startScreenCapture();
            } else {
                Toast.makeText(this, "Audio permission denied. Recording video only.", Toast.LENGTH_SHORT).show();
                startScreenCapture();
            }
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        watchdogHandler.post(watchdogRunnable);
        if (!DvrService.isRecording()) {
            updateDvrUi(false);
        }
    }

    @Override
    protected void onPause() {
        super.onPause();
        watchdogHandler.removeCallbacks(watchdogRunnable);
    }

    @Override
    protected void onStop() {
        super.onStop();

        if (DvrService.isRecording()) {
            FileLogger.log(TAG, "App minimized / inactive: auto-stopping DVR cleanly");
            DvrService.stop(this);
            runOnUiThread(() -> Toast.makeText(this, "DVR Auto-saved (App minimized)", Toast.LENGTH_SHORT).show());
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (DvrService.isRecording()) {
            DvrService.stop(this);
        }
        try {
            unregisterReceiver(usbReceiver);
        } catch (Exception ignored) {}
        disconnectDevice();
    }
}
