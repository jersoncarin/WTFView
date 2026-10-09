package com.jersnet.wtfview.osd;

import android.content.Context;
import android.util.Log;

import com.jersnet.wtfview.FileLogger;
import com.jersnet.wtfview.adb.AdbUsbClient;
import com.jersnet.wtfview.adb.PcapParser;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;

public class OsdManager implements PcapParser.PacketListener {
    private static final String TAG = "OsdManager";
    public static final int UNCOMPRESSED_SIZE = 2640;
    public static final int TOTAL_CHARS = 60 * 22;

    private final Context context;
    private final OsdView osdView;
    private final FontManager fontManager;
    private final PcapParser pcapParser;

    private byte[] lz4Dict;
    private String currentFc = "BTFL";
    private boolean hasCustomSyncedFont = false;

    public OsdManager(Context context, OsdView osdView, FontManager fontManager) {
        this.context = context;
        this.osdView = osdView;
        this.fontManager = fontManager;
        this.pcapParser = new PcapParser(this);

        loadDictionary();
    }

    private void loadDictionary() {
        try (InputStream is = context.getAssets().open("dictionary_1.bin")) {
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            byte[] buf = new byte[4096];
            int r;
            while ((r = is.read(buf)) != -1) {
                baos.write(buf, 0, r);
            }
            lz4Dict = baos.toByteArray();
            osdView.setLz4Dict(lz4Dict);
            FileLogger.log(TAG, "Loaded LZ4 dictionary: " + lz4Dict.length + " bytes");
        } catch (Exception e) {
            FileLogger.logError(TAG, "Failed to load dictionary_1.bin from assets", e);
        }
    }

    public PcapParser getPcapParser() {
        return pcapParser;
    }

    public boolean syncGogglesFont(AdbUsbClient adbClient) {
        if (adbClient == null) return false;
        FileLogger.log(TAG, "Syncing OSD font from goggles (/blackbox/fonts, /opt/fonts)...");
        String fc = (currentFc != null && !currentFc.isEmpty()) ? currentFc.toLowerCase() : "btfl";

        java.util.List<String> pathsToTry = new java.util.ArrayList<>();

        byte[] lsBytes = adbClient.executeCommandGetOutput("exec:ls -1 /blackbox/fonts/*.png /opt/fonts/*.png /blackbox/*.png 2>/dev/null", 2000);
        if (lsBytes != null && lsBytes.length > 0) {
            String lsOutput = new String(lsBytes).trim();
            FileLogger.log(TAG, "Goggles font files found:\n" + lsOutput);
            String[] lines = lsOutput.split("\n");
            for (String l : lines) {
                String path = l.trim();
                if (path.isEmpty()) continue;
                if (path.contains(fc) && path.contains("_hd")) {
                    pathsToTry.add(0, path);
                } else if (path.contains("_hd")) {
                    pathsToTry.add(path);
                } else {
                    pathsToTry.add(path);
                }
            }
        }

        String[] fallbackPaths = new String[]{
            "/blackbox/fonts/font_" + fc + "_hd.png",
            "/blackbox/fonts/font_btfl_hd.png",
            "/blackbox/fonts/font_hd.png",
            "/blackbox/fonts/font_" + fc + ".png",
            "/blackbox/fonts/font_btfl.png",
            "/blackbox/fonts/font.png",
            "/opt/fonts/font_" + fc + "_hd.png",
            "/opt/fonts/font_btfl_hd.png",
            "/opt/fonts/font.png"
        };
        for (String p : fallbackPaths) {
            if (!pathsToTry.contains(p)) {
                pathsToTry.add(p);
            }
        }

        for (String path : pathsToTry) {
            FileLogger.log(TAG, "Attempting to pull font from goggles: " + path);
            byte[] fontBytes = adbClient.executeCommandGetOutput("exec:cat " + path + " 2>/dev/null", 4000);
            if (fontBytes != null && fontBytes.length > 1000) {

                if (fontBytes[0] == (byte) 0x89 && fontBytes[1] == 0x50 && fontBytes[2] == 0x4E && fontBytes[3] == 0x47) {
                    FileLogger.log(TAG, "Downloaded active font from goggles: " + path + " (" + fontBytes.length + " bytes)");
                    if (fontManager.saveAndApplyFontBytes(context, fontBytes, path)) {
                        hasCustomSyncedFont = true;
                        osdView.postInvalidate();
                        return true;
                    }
                } else {
                    FileLogger.log(TAG, "File at " + path + " is not a valid PNG (" + fontBytes.length + " bytes)");
                }
            }
        }

        FileLogger.log(TAG, "No valid custom font found on goggles");
        return false;
    }

    private long lastTelemLogTime = 0;
    private long lastOsdLogTime = 0;
    private long totalOsdFrames = 0;

    public interface TelemetryListener {
        void onTelemetryUpdate(int temp, float voltage, String fcVariant);
    }

    private TelemetryListener telemetryListener;

    public void setTelemetryListener(TelemetryListener listener) {
        this.telemetryListener = listener;
    }

    @Override
    public void onTelemetryPacket(int temp, float voltage, String fcVariant) {
        if (fcVariant != null && !fcVariant.isEmpty() && !fcVariant.equals(currentFc)) {
            currentFc = fcVariant;
            if (!hasCustomSyncedFont) {
                fontManager.loadFontForFc(context, currentFc);
                osdView.postInvalidate();
            }
        }
        long now = System.currentTimeMillis();
        if (now - lastTelemLogTime > 5000) {
            FileLogger.log(TAG, String.format("Telemetry update: temp=%d°C, voltage=%.2fV, fc=%s", temp, voltage, currentFc));
            lastTelemLogTime = now;
        }
        osdView.updateTelemetry(temp, voltage, currentFc);
        if (telemetryListener != null) {
            telemetryListener.onTelemetryUpdate(temp, voltage, currentFc);
        }
    }

    @Override
    public void onCompressedOsdPacket(byte[] payload, int offset, int length) {
        if (lz4Dict == null) {
            FileLogger.log(TAG, "Cannot render OSD: lz4Dict is null");
            return;
        }
        if (length <= 4) return;

        boolean rendered = osdView.renderOsdPacket(payload, offset, length, lz4Dict);
        if (rendered) {
            totalOsdFrames++;
            long now = System.currentTimeMillis();
            if (totalOsdFrames == 1 || now - lastOsdLogTime > 4000) {
                FileLogger.log(TAG, "OSD rendered frame #" + totalOsdFrames + " (len=" + length + ")");
                lastOsdLogTime = now;
            }
        }
    }
}
