#include <jni.h>
#include <android/bitmap.h>
#include <android/log.h>
#include <string.h>
#include <stdlib.h>
#include <stdint.h>
#include "lz4.h"

#define TAG "WTFViewNative"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)

#define COLS 60
#define ROWS 22
#define GLYPH_W 32
#define GLYPH_H 48
#define CANVAS_W 1920
#define CANVAS_H 1080
#define TOP_PAD 12
#define UNCOMPRESSED_SIZE (COLS * ROWS * 2)

static uint32_t s_font_pixels[128 * 12288];
static int s_font_w = 128;
static int s_font_h = 12288;
static int s_font_loaded = 0;

static uint8_t s_decomp_buffer[UNCOMPRESSED_SIZE];

JNIEXPORT jint JNICALL
Java_com_jersnet_wtfview_osd_Lz4Native_decompressWithDict(
    JNIEnv *env,
    jclass clazz,
    jbyteArray compressed,
    jint compLen,
    jbyteArray uncompressed,
    jint uncompLen,
    jbyteArray dictionary,
    jint dictLen
) {
    if (compressed == NULL || uncompressed == NULL || dictionary == NULL) {
        return -1;
    }

    jbyte *c = (jbyte *)(*env)->GetPrimitiveArrayCritical(env, compressed, NULL);
    jbyte *u = (jbyte *)(*env)->GetPrimitiveArrayCritical(env, uncompressed, NULL);
    jbyte *d = (jbyte *)(*env)->GetPrimitiveArrayCritical(env, dictionary, NULL);

    int res = LZ4_decompress_safe_usingDict(
        (const char *)c,
        (char *)u,
        compLen,
        uncompLen,
        (const char *)d,
        dictLen
    );

    (*env)->ReleasePrimitiveArrayCritical(env, dictionary, d, JNI_ABORT);
    (*env)->ReleasePrimitiveArrayCritical(env, uncompressed, u, 0);
    (*env)->ReleasePrimitiveArrayCritical(env, compressed, c, JNI_ABORT);

    return res;
}

JNIEXPORT jboolean JNICALL
Java_com_jersnet_wtfview_osd_Lz4Native_initFont(
    JNIEnv *env,
    jclass clazz,
    jobject fontBitmap
) {
    if (fontBitmap == NULL) return JNI_FALSE;

    AndroidBitmapInfo info;
    if (AndroidBitmap_getInfo(env, fontBitmap, &info) < 0) {
        LOGE("Failed to get font bitmap info");
        return JNI_FALSE;
    }

    if (info.format != ANDROID_BITMAP_FORMAT_RGBA_8888) {
        LOGE("Unsupported font bitmap format: %d", info.format);
        return JNI_FALSE;
    }

    void *pixels = NULL;
    if (AndroidBitmap_lockPixels(env, fontBitmap, &pixels) < 0 || pixels == NULL) {
        LOGE("Failed to lock font bitmap pixels");
        return JNI_FALSE;
    }

    int copy_w = (info.width <= 128) ? info.width : 128;
    int copy_h = (info.height <= 12288) ? info.height : 12288;
    s_font_w = copy_w;
    s_font_h = copy_h;

    const uint32_t *src = (const uint32_t *)pixels;
    for (int y = 0; y < copy_h; y++) {
        memcpy(s_font_pixels + y * s_font_w, src + y * (info.stride / 4), copy_w * sizeof(uint32_t));
    }

    AndroidBitmap_unlockPixels(env, fontBitmap);
    s_font_loaded = 1;
    LOGI("Native font initialized successfully: %dx%d (glyph %dx%d)", copy_w, copy_h, GLYPH_W, GLYPH_H);
    return JNI_TRUE;
}

JNIEXPORT jint JNICALL
Java_com_jersnet_wtfview_osd_Lz4Native_renderOsdPacket(
    JNIEnv *env,
    jclass clazz,
    jbyteArray payload,
    jint offset,
    jint length,
    jbyteArray dictionary,
    jobject targetBitmap
) {
    if (payload == NULL || dictionary == NULL || targetBitmap == NULL) return -1;
    if (length <= 4) return -2;
    if (!s_font_loaded) return -3;

    int compOffset = offset + 4;
    int compLen = length - 4;

    jsize dictLen = (*env)->GetArrayLength(env, dictionary);
    jbyte *compBytes = (jbyte *)(*env)->GetPrimitiveArrayCritical(env, payload, NULL);
    jbyte *dictBytes = (jbyte *)(*env)->GetPrimitiveArrayCritical(env, dictionary, NULL);

    int decompRes = LZ4_decompress_safe_usingDict(
        (const char *)(compBytes + compOffset),
        (char *)s_decomp_buffer,
        compLen,
        UNCOMPRESSED_SIZE,
        (const char *)dictBytes,
        dictLen
    );

    (*env)->ReleasePrimitiveArrayCritical(env, dictionary, dictBytes, JNI_ABORT);
    (*env)->ReleasePrimitiveArrayCritical(env, payload, compBytes, JNI_ABORT);

    if (decompRes != UNCOMPRESSED_SIZE) {
        return decompRes;
    }

    AndroidBitmapInfo targetInfo;
    if (AndroidBitmap_getInfo(env, targetBitmap, &targetInfo) < 0) return -4;
    if (targetInfo.width != CANVAS_W || targetInfo.height != CANVAS_H) return -5;

    void *targetPixels = NULL;
    if (AndroidBitmap_lockPixels(env, targetBitmap, &targetPixels) < 0 || targetPixels == NULL) return -6;

    uint32_t *dst = (uint32_t *)targetPixels;
    const uint16_t *chars = (const uint16_t *)s_decomp_buffer;

    for (int x = 0; x < COLS; x++) {
        for (int y = 0; y < ROWS; y++) {
            uint16_t c = chars[x * ROWS + y];
            int dst_x = x * GLYPH_W;
            int dst_y = TOP_PAD + y * GLYPH_H;

            if (c == 0) {

                for (int r = 0; r < GLYPH_H; r++) {
                    uint32_t *dst_row = dst + (dst_y + r) * CANVAS_W + dst_x;
                    memset(dst_row, 0, GLYPH_W * sizeof(uint32_t));
                }
            } else {
                int page = (c & 0x300) >> 8;
                if (page >= 4) page = 0;
                int char_code = c & 0xFF;

                int src_x = page * GLYPH_W;
                int src_y = char_code * GLYPH_H;

                if (src_y + GLYPH_H <= s_font_h && src_x + GLYPH_W <= s_font_w) {
                    for (int r = 0; r < GLYPH_H; r++) {
                        const uint32_t *src_row = s_font_pixels + (src_y + r) * s_font_w + src_x;
                        uint32_t *dst_row = dst + (dst_y + r) * CANVAS_W + dst_x;
                        memcpy(dst_row, src_row, GLYPH_W * sizeof(uint32_t));
                    }
                }
            }
        }
    }

    AndroidBitmap_unlockPixels(env, targetBitmap);
    return 0;
}

JNIEXPORT void JNICALL
Java_com_jersnet_wtfview_osd_Lz4Native_clearTargetBitmap(
    JNIEnv *env,
    jclass clazz,
    jobject targetBitmap
) {
    if (targetBitmap == NULL) return;
    void *pixels = NULL;
    if (AndroidBitmap_lockPixels(env, targetBitmap, &pixels) >= 0 && pixels != NULL) {
        memset(pixels, 0, CANVAS_W * CANVAS_H * sizeof(uint32_t));
        AndroidBitmap_unlockPixels(env, targetBitmap);
    }
}

JNIEXPORT jstring JNICALL
Java_com_jersnet_wtfview_osd_Lz4Native_getOsdText(
    JNIEnv *env,
    jclass clazz
) {
    char text[ROWS * (COLS + 1) + 1];
    int idx = 0;
    const uint16_t *chars = (const uint16_t *)s_decomp_buffer;

    for (int y = 0; y < ROWS; y++) {
        for (int x = 0; x < COLS; x++) {
            uint16_t c = chars[x * ROWS + y];
            char ch = (char)(c & 0xFF);
            if (ch >= 32 && ch <= 126) {
                text[idx++] = ch;
            } else {
                text[idx++] = ' ';
            }
        }
        text[idx++] = '\n';
    }
    text[idx] = '\0';

    return (*env)->NewStringUTF(env, text);
}

