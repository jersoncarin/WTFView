package com.jersnet.wtfview.osd;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Rect;
import android.util.Log;

import com.jersnet.wtfview.FileLogger;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;

public class FontManager {
    private static final String TAG = "FontManager";

    public static final int HD_GLYPH_WIDTH = 32;
    public static final int HD_GLYPH_HEIGHT = 48;
    public static final int NUM_CHARS = 256;
    public static final String CACHED_FONT_FILENAME = "goggles_font_cache.png";

    private Bitmap fontBitmap;
    private int glyphWidth = HD_GLYPH_WIDTH;
    private int glyphHeight = HD_GLYPH_HEIGHT;
    private int numPages = 4;
    private final Rect[][] glyphRects = new Rect[4][NUM_CHARS];
    private String currentFontName = "default";
    private boolean isCustomCached = false;

    public static File getCachedFontFile(Context context) {
        return new File(context.getFilesDir(), CACHED_FONT_FILENAME);
    }

    public static boolean hasCachedFont(Context context) {
        File file = getCachedFontFile(context);
        return file.exists() && file.length() > 1000;
    }

    public boolean isCustomCached() {
        return isCustomCached;
    }

    public FontManager(Context context) {

        if (hasCachedFont(context)) {
            File cachedFile = getCachedFontFile(context);
            if (loadFontFromFile(cachedFile)) {
                isCustomCached = true;
                FileLogger.log(TAG, "Initialized using cached goggles font: " + cachedFile.getAbsolutePath());
                return;
            }
        }

        if (!loadFontFromAsset(context, "font_btfl.png")) {
            loadFontFromAsset(context, "font_btfl_hd.png");
        }
    }

    public synchronized boolean loadFontFromFile(File file) {
        if (file == null || !file.exists() || file.length() < 1000) return false;
        try {
            BitmapFactory.Options opts = new BitmapFactory.Options();
            opts.inScaled = false;
            opts.inPreferredConfig = Bitmap.Config.ARGB_8888;
            Bitmap bmp = BitmapFactory.decodeFile(file.getAbsolutePath(), opts);
            if (bmp != null) {
                applyBitmap(bmp, "cached:" + file.getName());
                isCustomCached = true;
                return true;
            }
        } catch (Exception e) {
            FileLogger.logError(TAG, "Failed to load font from file: " + file.getAbsolutePath(), e);
        }
        return false;
    }

    public synchronized boolean saveAndApplyFontBytes(Context context, byte[] data, String fontName) {
        if (data == null || data.length < 1000) return false;
        if (!loadFontFromBytes(data, fontName)) return false;
        isCustomCached = true;
        try {
            File cacheFile = getCachedFontFile(context);
            try (FileOutputStream fos = new FileOutputStream(cacheFile)) {
                fos.write(data);
                fos.flush();
            }
            FileLogger.log(TAG, "Saved font to disk cache: " + cacheFile.getAbsolutePath() + " (" + data.length + " bytes)");
            return true;
        } catch (Exception e) {
            FileLogger.logError(TAG, "Failed to write font to cache file", e);
            return false;
        }
    }

    public synchronized boolean loadFontFromAsset(Context context, String assetName) {
        try (InputStream is = context.getAssets().open(assetName)) {
            BitmapFactory.Options opts = new BitmapFactory.Options();
            opts.inScaled = false;
            opts.inPreferredConfig = Bitmap.Config.ARGB_8888;
            Bitmap bmp = BitmapFactory.decodeStream(is, null, opts);
            if (bmp != null) {
                applyBitmap(bmp, assetName);
                return true;
            }
        } catch (Exception e) {
            FileLogger.logError(TAG, "Failed to load asset font: " + assetName, e);
        }
        return false;
    }

    public synchronized boolean loadFontForFc(Context context, String fc) {
        if (isCustomCached) {

            return true;
        }
        if (fc == null || fc.isEmpty()) fc = "BTFL";
        String variant = fc.toLowerCase();
        String assetName;
        switch (variant) {
            case "inav":
                assetName = "font_inav.png";
                break;
            case "ardu":
            case "ardupilot":
                assetName = "font_ardu.png";
                break;
            case "quic":
            case "quicksilver":
                assetName = "font_quic.png";
                break;
            case "ultr":
                assetName = "font_ultr.png";
                break;
            case "btfl":
            default:
                assetName = "font_btfl.png";
                break;
        }

        if (assetName.equals(currentFontName)) {
            return true;
        }
        boolean ok = loadFontFromAsset(context, assetName);
        if (!ok) {
            ok = loadFontFromAsset(context, assetName.replace(".png", "_hd.png"));
        }
        return ok;
    }

    public synchronized boolean loadFontFromBytes(byte[] data, String fontName) {
        if (data == null || data.length == 0) return false;
        try {
            BitmapFactory.Options opts = new BitmapFactory.Options();
            opts.inScaled = false;
            opts.inPreferredConfig = Bitmap.Config.ARGB_8888;
            Bitmap bmp = BitmapFactory.decodeByteArray(data, 0, data.length, opts);
            if (bmp != null) {
                applyBitmap(bmp, fontName);
                return true;
            }
        } catch (Exception e) {
            FileLogger.logError(TAG, "Failed to decode font bytes: " + fontName, e);
        }
        return false;
    }

    private void applyBitmap(Bitmap bmp, String name) {
        if (bmp == null) return;

        int rawW = bmp.getWidth();
        int rawH = bmp.getHeight();

        int rawGlyphH = rawH / NUM_CHARS;
        int rawGlyphW = (rawGlyphH == 54) ? 36 : (rawGlyphH == 36 ? 24 : rawW / 4);
        int detectedPages = Math.min(4, Math.max(1, rawW / Math.max(1, rawGlyphW)));

        int targetW = detectedPages * HD_GLYPH_WIDTH;
        int targetH = NUM_CHARS * HD_GLYPH_HEIGHT;

        Bitmap scaled;
        if (rawW == targetW && rawH == targetH) {
            scaled = bmp;
        } else {

            scaled = Bitmap.createScaledBitmap(bmp, targetW, targetH, true);
        }

        if (fontBitmap != null && !fontBitmap.isRecycled() && fontBitmap != scaled && fontBitmap != bmp) {
            fontBitmap.recycle();
        }
        this.fontBitmap = scaled;
        this.currentFontName = name;
        this.numPages = detectedPages;
        this.glyphWidth = HD_GLYPH_WIDTH;
        this.glyphHeight = HD_GLYPH_HEIGHT;

        for (int p = 0; p < numPages; p++) {
            for (int c = 0; c < NUM_CHARS; c++) {
                int left = p * glyphWidth;
                int top = c * glyphHeight;
                glyphRects[p][c] = new Rect(left, top, left + glyphWidth, top + glyphHeight);
            }
        }

        boolean nativeInit = Lz4Native.initFont(scaled);
        FileLogger.log(TAG, "Loaded font " + name + " (original " + rawW + "x" + rawH + " -> native 1080p: " + targetW + "x" + targetH + "), pages=" + numPages + ", nativeInit=" + nativeInit);
    }

    public synchronized Bitmap getFontBitmap() {
        return fontBitmap;
    }

    public synchronized Rect getGlyphRect(int page, int charCode) {
        if (page < 0 || page >= numPages) page = 0;
        if (charCode < 0 || charCode >= NUM_CHARS) return null;
        return glyphRects[page][charCode];
    }

    public int getGlyphWidth() { return glyphWidth; }
    public int getGlyphHeight() { return glyphHeight; }
    public String getCurrentFontName() { return currentFontName; }
}
