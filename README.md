# Pocket Video Toolkit

A native Kotlin/Jetpack Compose Android Studio project for offline video conversion and lightweight editing.

## What works in this baseline

- Import one or multiple video clips through Android's document picker; re-order or remove clips.
- Inspect basic container, dimensions, and duration metadata.
- Export H.264 or H.265 MP4 at 240p, 360p, 480p, 720p, 1080p, 1440p, 4K, or custom dimensions.
- Request video bitrate and AAC audio bitrate, or keep the input audio stream when Media3 can pass it through.
- Compressor-style High, Balanced, and Small presets; estimated target-size mode; source/60/30/24 fps caps; and device-default, variable, or constant bitrate mode. Target size is approximate because hardware encoders can vary the final size.
- Trim a single clip using start/end seconds; concatenate a sequence of clips.
- Run export as a WorkManager foreground job with progress notification, in-app progress, and cancellation.
- Save output to `Movies/Pocket Video Toolkit` on Android 10+.
- Switch between light and dark themes; the choice is saved on the device.
- Select and preview a still image using Android's image decoder.
- Open **HDR tools** for the two andAicaroid-inspired conversions: BT.2020 HLG/PQ video frame to Ultra HDR JPEG, and Ultra HDR gain-map JPEG to a short HEVC Main10 HLG video. These require Android 14+ and compatible device codecs. They use the published AkariCore and libaicaroid libraries; they are specialized conversions, not a full HDR timeline editor.
- Use Media3 Transformer defaults for HDR handling. Keep-HDR, tone mapping, encoder fallback, and metadata retention depend on the device and source. This app does not claim guaranteed HDR or dynamic metadata preservation.

## Not implemented yet

- Per-clip trim handles, split-at-playhead, timeline editing, crossfades, multi-track overlays, text/sticker layers, chroma key, speed controls, and audio mixing.
- Device-specific HDR capability reporting and a confirmed output HDR metadata inspector.
- Full HDR timeline editing, HDR overlays/effects, and guaranteed HDR metadata preservation. The specialized conversions above do not provide general HDR editing.
- HEVC/AV1 profile, bit depth, frame-rate, and container selection beyond MP4/H.264/H.265.

The repository references demonstrate these as separate specialized paths. They are not integrated by pretending a basic transcode is equivalent to a complete HDR editor.

## Build

Open this folder in Android Studio with JDK 17 or newer, allow Gradle sync, then select **Build > Build Bundle(s) / APK(s) > Build APK(s)**. The project uses Android Gradle Plugin 9.1.1, Kotlin 2.4.10, compile SDK 37, target SDK 35, minimum SDK 26, WorkManager 2.10.0, Media3 1.6.1, AkariCore 6.4.2, and libaicaroid 1.0.0. Android Studio will download these dependencies.

For a command-line build from an environment with JDK 17+ and Android SDK platform/build tools 37 installed, use the included Gradle wrapper:

```sh
cd PocketVideoToolkit
./gradlew :app:assembleDebug
```

The debug APK is written to `app/build/outputs/apk/debug/app-debug.apk`. This project was built successfully with the included wrapper, and the APK signature and package metadata were verified. No Android device was connected during this build, so installation and on-device export behavior remain unverified.

## Design

The single-screen layout groups the workflow into a clip queue, export settings, HDR/photo tools, and export status. It offers light and dark palettes with mint accents. Import and processing stay on-device; Android's Storage Access Framework grants access only to chosen media.

## Implementation notes

- `MainActivity.kt`: Compose UI, SAF pickers, clip ordering, compressor-style output settings, WorkManager enqueue and state.
- `HdrToolsActivity.kt`, `HdrFrameCaptureSession.kt`, and `UltraHdrVideoExporter.kt`: HDR frame/photo conversions based on AkariCore and libaicaroid.
- `ExportWorker.kt`: foreground work, Media3 Transformer, progress polling, cancellation, and MediaStore publishing.
- `VideoInspector.kt`: metadata inspection through `MediaMetadataRetriever`.
- Media3 is configured with a selectable H.264/H.265/AV1 request (only advertised encoders are offered), requested bitrate, bitrate mode, and optional frame-rate cap. Device encoders can reject settings, ignore a requested bitrate, or fall back. The worker surfaces the platform error.
- Multi-clip joining uses one sequential Media3 composition. Media3 currently has constraints for compositions, especially HDR sequences, audio track consistency, and mixed HDR/SDR sources.
- Android 10+ output goes to the public Movies collection. On Android 8–9 it is kept under the app-specific Movies directory because legacy shared-storage write access is intentionally not requested.

## Reference review and reuse decision

No ZIP or prior Android project version was attached to the referenced conversation, and no project files were present in the workspace. The prior thread's attachment list was empty. This project is therefore a fresh implementation.

| Reference | Relevant contribution | License / integration decision |
|---|---|---|
| [JoshAtticus/Compressor](https://github.com/JoshAtticus/Compressor) | Kotlin + Media3 compressor; H.265/AV1 where supported; compact conversion workflow. | MIT is declared in its repository. Used only as architectural inspiration; no source copied. |
| [AkariDroid](https://github.com/takusan23/AkariDroid) | Compose editor, MediaCodec/OpenGL engine, effects, audio/video/text/image layers, chroma key, 10-bit HLG editing. | Repository declares Apache-2.0. Its `akari-core` is a separate renderer and would be a substantial integration; not included. |
| [HimariDroid](https://github.com/takusan23/HimariDroid) | On-device re-encoding with AVC, HEVC, AV1, VP9 and WebM variants; limited 10-bit HDR support. | A top-level license file was not visible in the inspected repository tree; no code copied. |
| [andAicaroid](https://github.com/takusan23/andAicaroid) | Extract/encode HDR frames as Ultra HDR stills using RGBA1010102 and libultrahdr. | Repository declares Apache-2.0. Native integration and HDR frame extraction are not included in this baseline. |
| [AndroidMediaCodecHdrVideoCanvasOverlay](https://github.com/takusan23/AndroidMediaCodecHdrVideoCanvasOverlay) | MediaCodec/OpenGL HDR canvas overlay sample. | Repository declares Apache-2.0; no sample code copied. |
| [HDRVideoToUltraHDR](https://github.com/takusan23/HDRVideoToUltraHDR) | 10-bit HDR video frame to Ultra HDR JPEG. The author's write-up describes OpenGL ES RGBA1010102 extraction to avoid P010 `ImageReader` crashes on some Snapdragon devices, then libultrahdr encoding. | Repository page was unavailable to this inspection; no project license verified and no code copied. The described approach needs native code, memory-heavy frame extraction, and device validation. |
| [UltraHdrBlurEdit](https://github.com/takusan23/UltraHdrBlurEdit) | Ultra HDR gain-map photo editing/effects. | Repository page was unavailable to this inspection; no license verified and no code copied. |
| [AndroidUltraHdrApi](https://github.com/takusan23/AndroidUltraHdrApi) | Demonstrates Android Ultra HDR APIs. | The inspected repository listing did not show a license file; no code copied. |
| [RAW / True HDR viewer listing](https://play.google.com/store/apps/details?id=com.myvnc.eal.cr3toultrahdrconverter) | The listing describes RAW, Ultra HDR, AVIF, JXL and other HDR viewing/export features. | A commercial app listing, not a source library. The baseline provides a basic Android-decoder still preview only. |

References reviewed on 2026-10-30. Android's [Media3 Transformer documentation](https://developer.android.com/media/media3/transformer) describes transcoding, edits, HDR handling, and the constraints around multi-asset compositions. The requested output should be tested with representative source files on the target phone before relying on HDR preservation.

## Licensing

This project contains original source written for the user and uses AndroidX/Media3 dependencies under their respective licenses. It includes no copied code from the referenced repositories. Confirm third-party dependency notices before distributing an APK.
