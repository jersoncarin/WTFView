package com.jersnet.wtfview.osd;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.RectF;
import android.os.SystemClock;
import android.util.AttributeSet;
import android.view.View;

public class OsdView extends View {
    private static final int CANVAS_W = 1920;
    private static final int CANVAS_H = 1080;

    private FontManager fontManager;
    private byte[] lz4Dict;

    private final Object bufferLock = new Object();
    private Bitmap frontBitmap = Bitmap.createBitmap(CANVAS_W, CANVAS_H, Bitmap.Config.ARGB_8888);
    private Bitmap backBitmap = Bitmap.createBitmap(CANVAS_W, CANVAS_H, Bitmap.Config.ARGB_8888);

    private final Rect srcRect = new Rect(0, 0, CANVAS_W, CANVAS_H);
    private final RectF dstRect = new RectF();

    private final Paint bitmapPaint = new Paint();
    private final Paint statusBgPaint = new Paint();
    private final Paint statusStrokePaint = new Paint();
    private final Paint textPaint = new Paint(Paint.ANTI_ALIAS_FLAG);

    private String fcVariant = "BTFL";
    private int auTemp = 0;
    private float auVoltage = 0.0f;
    private int osdPacketCount = 0;
    private boolean showStatusBar = false;
    private long lastErrorLogTime = 0;

    public OsdView(Context context) {
        super(context);
        init();
    }

    public OsdView(Context context, AttributeSet attrs) {
        super(context, attrs);
        init();
    }

    private void init() {

        setLayerType(View.LAYER_TYPE_HARDWARE, null);

        bitmapPaint.setFilterBitmap(true);
        bitmapPaint.setAntiAlias(true);
        bitmapPaint.setDither(true);

        statusBgPaint.setColor(Color.argb(190, 0, 0, 0));
        statusBgPaint.setStyle(Paint.Style.FILL);

        statusStrokePaint.setColor(Color.argb(220, 0, 255, 0));
        statusStrokePaint.setStyle(Paint.Style.STROKE);
        statusStrokePaint.setStrokeWidth(2f);

        textPaint.setColor(Color.YELLOW);
        textPaint.setTextSize(26f);

        synchronized (bufferLock) {
            Lz4Native.clearTargetBitmap(frontBitmap);
            Lz4Native.clearTargetBitmap(backBitmap);
        }

        setOnClickListener(v -> setShowStatusBar(!showStatusBar));
    }

    public void setFontManager(FontManager fm) {
        this.fontManager = fm;
    }

    public void setLz4Dict(byte[] dict) {
        this.lz4Dict = dict;
    }

    public Bitmap getFrontBitmap() {
        return frontBitmap;
    }

    public Object getBufferLock() {
        return bufferLock;
    }

    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);
        updateViewport(w, h);
    }

    private void updateViewport(int viewW, int viewH) {
        if (viewW <= 0 || viewH <= 0) return;

        float targetAspect = 16.0f / 9.0f;
        float currentAspect = (float) viewW / (float) viewH;

        float boxW, boxH, offsetX, offsetY;
        if (currentAspect > targetAspect) {
            boxH = viewH;
            boxW = boxH * targetAspect;
            offsetX = (viewW - boxW) / 2.0f;
            offsetY = 0;
        } else {
            boxW = viewW;
            boxH = boxW / targetAspect;
            offsetX = 0;
            offsetY = (viewH - boxH) / 2.0f;
        }

        dstRect.set(offsetX, offsetY, offsetX + boxW, offsetY + boxH);
    }

    public boolean renderOsdPacket(byte[] payload, int offset, int length, byte[] dictionary) {
        if (payload == null || dictionary == null || length <= 4) return false;

        int res = Lz4Native.renderOsdPacket(payload, offset, length, dictionary, backBitmap);
        if (res == 0) {
            synchronized (bufferLock) {
                Bitmap temp = frontBitmap;
                frontBitmap = backBitmap;
                backBitmap = temp;
                osdPacketCount++;
            }
            postInvalidate();
            return true;
        } else {
            long now = SystemClock.uptimeMillis();
            if (osdPacketCount == 0 || now - lastErrorLogTime > 4000) {
                com.jersnet.wtfview.FileLogger.log("OsdView", "renderOsdPacket failed: code=" + res + ", payloadLen=" + length);
                lastErrorLogTime = now;
            }
            return false;
        }
    }

    public synchronized void updateTelemetry(int temp, float voltage, String fc) {
        this.auTemp = temp;
        this.auVoltage = voltage;
        if (fc != null && !fc.isEmpty()) {
            this.fcVariant = fc;
        }
        if (showStatusBar) {
            postInvalidate();
        }
    }

    public void setShowStatusBar(boolean show) {
        this.showStatusBar = show;
        postInvalidate();
    }

    public void clear() {
        synchronized (bufferLock) {
            Lz4Native.clearTargetBitmap(frontBitmap);
            Lz4Native.clearTargetBitmap(backBitmap);
        }
        postInvalidate();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);

        if (dstRect.isEmpty()) {
            updateViewport(getWidth(), getHeight());
        }

        synchronized (bufferLock) {
            canvas.drawBitmap(frontBitmap, srcRect, dstRect, bitmapPaint);
        }
    }
}
