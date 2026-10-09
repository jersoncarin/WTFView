package com.jersnet.wtfview.osd;

import android.graphics.Bitmap;

public class Lz4Native {
    static {
        System.loadLibrary("wtfview-native");
    }

    public static native int decompressWithDict(
        byte[] compressed, int compLen,
        byte[] uncompressed, int uncompLen,
        byte[] dictionary, int dictLen
    );

    public static native boolean initFont(Bitmap fontBitmap);

    public static native int renderOsdPacket(
        byte[] payload, int offset, int length,
        byte[] dictionary, Bitmap targetBitmap
    );

    public static native void clearTargetBitmap(Bitmap targetBitmap);
}
