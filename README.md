# Pocket Video Toolkit

A native Kotlin/Jetpack Compose Android Studio project for offline video conversion and lightweight editing.

## What works in this baseline

- Import one or multiple video clips through Android's document picker; re-order or remove clips.
- Inspect basic container, dimensions, and duration metadata.
- Export H.264 or H.265 MP4 at source resolution, 240p, 360p, 480p, 720p, 1080p, 1440p, 4K, or custom dimensions.
- Request video bitrate and AAC audio bitrate, or keep the input audio stream when Media3 can pass it through.
- Compressor-style High, Balanced, and Small presets; estimated target-size mode; source/60/30/24 fps caps; and device-default, variable, or constant bitrate mode. Target size is approximate because hardware encoders can vary the final size.
- Trim a single clip using start/end seconds; concatenate a sequence of clips.
- Use separate Project, Edit, Convert, Media, and Jobs pages with persistent bottom navigation. Media keeps the HDR photo viewer separate from conversion workflows.
- Submit multiple independent exports. Each job captures the current clips and settings, runs as a WorkManager foreground job, and can be monitored or cancelled individually while you continue working elsewhere. Android/WorkManager schedules the jobs based on available resources.
- Save output to `Movies/Pocket Video Toolkit` on Android 10+.
- Switch between light and dark themes; the choice is saved on the device.
- View Ultra HDR gain-map photos in a separate viewer with explicit HDR detection, HDR/SDR preview switching, image/display headroom readouts on supported Android 14+ HDR displays, immersive full-screen viewing, portrait/landscape rotation, pinch zoom, and double-tap zoom reset.
- Adjust the HDR photo preview with compact, on-demand auto/manual exposure and highlight-guard controls. The viewer meters a software-readable SDR base, applies exposure to a preview copy, and caps the copied gain map; source photos remain unchanged.
- Capture high-quality photos from a dedicated, full-screen CameraX camera page. It offers direct physical-lens selection when the phone exposes its lens group, camera-style lens presets otherwise, tap-to-focus, flash, front/rear switching, Ultra HDR JPEG capability checks, and the manufacturer's CameraX HDR multi-frame extension when exposed. Photos are saved to Pictures/Pocket Video Toolkit.
- Open **HDR tools** for the two andAicaroid-inspired conversions: BT.2020 HLG/PQ video frame to Ultra HDR JPEG, and Ultra HDR gain-map JPEG to a short HEVC Main10 HLG video. These require Android 14+ and compatible device codecs. They use the published AkariCore and libaicaroid libraries; they are specialized conversions, not a full HDR timeline editor.
- Request Media3 Keep-HDR output when re-encoding. HDR retention and encoder fallback depend on the source and device; this route does not preserve HDR10+ scene-by-scene dynamic metadata. Copy source streams can preserve the original single MP4 byte-for-byte or join compatible HEVC samples without re-encoding.

## Not implemented yet

- Per-clip trim handles, split-at-playhead, timeline editing, crossfades, multi-track overlays, text/sticker layers, chroma key, speed controls, and audio mixing.
- Device-specific HDR capability reporting and a confirmed output HDR metadata inspector; multi-clip HDR10+ sample output still needs verification on real source files.
- Full HDR timeline editing, HDR overlays/effects, and HDR10+ preservation while resizing, trimming, or re-encoding. The specialized conversions above do not provide general HDR editing.
- HEVC/AV1 profile, bit depth, frame-rate, and container selection beyond MP4/H.264/H.265.
- Samsung Camera-specific ProVisual/AI image processing is proprietary and is not callable by third-party apps. Results depend on camera HAL/CameraX capabilities; the app uses the available vendor HDR extension and Ultra HDR output when exposed.

The repository references demonstrate these as separate specialized paths. They are not integrated by pretending a basic transcode is equivalent to a complete HDR editor.

## Build

Open this folder in Android Studio with JDK 17 or newer, allow Gradle sync, then select **Build > Build Bundle(s) / APK(s) > Build APK(s)**. The project uses Android Gradle Plugin 9.1.1, Kotlin 2.4.10, compile SDK 37, target SDK 35, minimum SDK 26, WorkManager 2.10.0, Media3 1.6.1, AkariCore 6.4.2, and libaicaroid 1.0.0. Android Studio will download these dependencies.

For a command-line build from an environment with JDK 17+ and Android SDK platform/build tools 37 installed, use the included Gradle wrapper:

```sh
cd PocketVideoToolkit
./gradlew :app:assembleDebug
```

The debug APK is written to `app/build/outputs/apk/debug/app-debug.apk`. This project was built successfully with the included wrapper. Device-side rendering and real HDR10+ sample-file behavior still need verification on the target phone.

If an already-installed debug copy was signed with a different key, build a clearly labeled side-by-side copy without uninstalling the existing app or clearing its data:

```sh
./gradlew :app:assembleDebug -PinstallBesideExisting=true
```

This installs as `Pocket Video Toolkit (Updated)` with application ID `app.pocketvideotoolkit.updated`.

## Design

The app uses a five-destination Material 3 bottom navigation layout: Project, Edit, Convert, Media, and Jobs. Media separates the HDR Photo Viewer from HDR Conversion; choosing a conversion direction switches its controls in place, with frame seeking shown only for video frame capture. Light and dark palettes use muted sage and neutral colors. Import and processing stay on-device; Android's Storage Access Framework grants access only to chosen media. Each export is submitted as an independent WorkManager task, so users can keep editing and prepare additional jobs without replacing an active export.

## Implementation notes

- `MainActivity.kt`: Compose UI, SAF pickers, clip ordering, compressor-style output settings, WorkManager enqueue and state.
- `HdrMediaPages.kt`, `HdrFrameCaptureSession.kt`, and `UltraHdrVideoExporter.kt`: separate HDR photo viewing and focused HDR conversion workflows based on AkariCore and libaicaroid. The photo viewer detects Android Ultra HDR gain maps, requests HDR window output on supported displays, and offers compact preview-only exposure and highlight controls. Exposure is applied to a preview bitmap and the highlight limit to a copied gain map; the original photo is not modified. The frame preview preserves source display aspect ratio and rotation metadata.
- `ExportWorker.kt`: foreground work, Media3 Transformer, progress polling, cancellation, and MediaStore publishing.
- `Hdr10PlusPassthrough.kt`: no-transcode stream copy. A single MP4 is copied byte-for-byte. Multiple matching HEVC clips can be joined by muxing their original encoded samples, which retains HDR10+ SEI carried in those samples. Multi-clip copy mode rejects mismatched codec configuration, dimensions/color format, audio formats, and extra tracks instead of re-encoding.
- `VideoInspector.kt`: metadata inspection through `MediaMetadataRetriever`.
- Media3 is configured with a selectable H.264/H.265/AV1 request (only advertised encoders are offered), requested bitrate, bitrate mode, and optional frame-rate cap. Device encoders can reject settings, ignore a requested bitrate, or fall back. The worker surfaces the platform error.
- `HDR_MODE_KEEP_HDR` requests HDR-preserving conversion when the source, Android Media3 pipeline, and hardware encoder permit it. This re-encode path does **not** preserve HDR10+ dynamic metadata. Use **Copy source streams** to retain the original encoded samples and any in-band HDR10+ metadata; resolution/bitrate conversion and trimming are unavailable in that mode.
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
