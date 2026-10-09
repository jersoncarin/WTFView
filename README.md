# WTFView

Low-latency Android digital FPV video viewer and OSD renderer for DJI FPV Goggles.

---

## What is WTFView?

WTFView allows you to connect an Android device (phone, tablet, or display) to DJI FPV Goggles via USB-C to:
- Stream live 720p 60 FPS digital FPV video with low glass-to-glass latency.
- Render full HD Betaflight / INAV / ArduPilot OSD overlays on top of the live feed.
- Record high-quality DVR (video + internal/mic audio) directly to your Android device storage.

---

## Compatible Goggles

- **DJI FPV Goggles V1**
- **DJI FPV Goggles V2**

### Connection Modes:
1. **Stock Mode (Bulk USB)**: Works on stock firmware. Uses the standard DJI USB handshake protocol to stream video.
2. **Rooted / WTF Mode (ADB)**: Works on goggles rooted via [fpv.wtf](https://github.com/fpv-wtf/). Enables full HD MSP OSD extraction and telemetry forwarding alongside live video.

---

## How It Works

### 1. Connection & Ingestion
- **DJI Protocol Mode (Stock)**: Claims the DJI USB interface (VID `0x2ca3`), sends the magic start handshake packets (`0x52 0x6d 0x78 0x74` and `0xAA 0x55...`), and reads raw H.264 video packets over USB bulk endpoints.
- **ADB Stream Mode (Rooted)**: Pushes `wtf_forwarder` to `/tmp/wtf_forwarder` on the goggles. The binary listens to internal socket traffic on the goggles:
  - Port `7654`: Video stream
  - Port `7655`: MSP DisplayPort OSD stream
  - Port `7656`: Telemetry stream
  
  Packets are multiplexed with a 4-byte header (`WTFV`) and streamed over ADB stdout via USB to the Android app.

### 2. Video Pipeline
- **NAL Demuxing**: `H264Extractor` scans incoming byte buffers for H.264 NAL units (SPS `0x67`, PPS `0x68`, IDR `0x65`, non-IDR `0x41`, SEI `0x06`).
- **Hardware Decoding**: Passes NAL frames directly to Android's hardware `MediaCodec` decoder through an ExoPlayer `SurfaceView`.
- **Zero-Buffering**: Internal buffering and render pipelines are tuned to zero cached frames to ensure minimum display latency.

### 3. OSD Pipeline
- **Decompression**: MSP DisplayPort OSD frames from port `7655` are LZ4-compressed with a pre-shared dictionary (`dictionary_1.bin`). A native C library (`native_lz4.c`) decompresses these frames via JNI.
- **Font & Glyph Rendering**: `FontManager` matches the flight controller font texture (Betaflight, INAV, ArduPilot, etc.) and parses the 53x20 or 30x16 character grid.
- **HUD Overlay**: `OsdView` renders the transparent character canvas over the video surface at 60 FPS without blocking video decoding threads.

### 4. DVR Recording
- Uses Android `MediaProjection` API to capture the composited live video and OSD canvas.
- Encodes output to MP4 with synchronized internal audio and microphone support.

---

## How to Build & Develop

### Prerequisites
- JDK 17
- Android SDK (API 34)
- Android NDK (25.1.8937393 or newer)
- CMake 3.22.1+

### Build Commands

```bash
# Clone the repository
git clone https://github.com/jersoncarin/WTFView.git
cd WTFView

# Build Debug APK
./gradlew assembleDebug

# Build Release APK
./gradlew assembleRelease
```

Build outputs:
- **Debug**: `app/build/outputs/apk/debug/app-debug.apk`
- **Release**: `app/build/outputs/apk/release/app-release.apk`

### Release Signing
To sign your release build, copy `keystore.properties.example` to `keystore.properties`:
```properties
storeFile=../your-release-key.jks
storePassword=your_keystore_password
keyAlias=your_key_alias
keyPassword=your_key_password
```

### Developing Native Code
- **Android Native Library**: Located in `app/src/main/cpp/`. Built automatically by CMake during `./gradlew assemble...`.
- **Goggles Forwarder**: Located in `goggles_forwarder/wtf_forwarder.c`. Precompiled ARM binary is located in `app/src/main/assets/wtf_forwarder`.

---

## Credits

- **[FPV.WTF](https://github.com/fpv-wtf/)** — Goggles rooting tools, protocols, and original concepts.
- **[DigiView-Android](https://github.com/fpvout/DigiView-Android)** — Reference Android implementation.
