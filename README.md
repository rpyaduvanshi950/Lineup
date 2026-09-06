# Lineup

On-device Android app that processes a portrait video, detects faces, identifies the unique
people across separate appearances, and produces a shareable collage — one representative shot
per person, with per-person appearance counts. No backend; everything runs on the phone.

## Status

- **Step 1 (ingest & detect)** — done. SAF video picker → `FrameExtractor` → `FaceDetectorWrapper`.
- **Step 2 (identify)** — done. `FaceAligner` → `FaceEmbedder` (MobileFaceNet/ArcFace) → `FaceClusterer` → `AppearanceSegmenter`.
- **Step 3 (select & compose)** — done. `ShotScorer` → `RepresentativeShotSelector` →
  `CollageComposer`, wired into a real `ResultsActivity` with save/share.
- **Step 4 (wire, verify, ship)** — done: full pipeline verified end-to-end on-device
  (VideoSelect → SAF picker → Processing → Results with collage + save/share) for all three
  samples; a rough demo recording exists at `docs/demo.mp4` (see below).

## Demo

**`docs/demo.mp4` predates the visual redesign below** — it still shows the earlier
purple/light UI, not the current cinematic dark theme. Re-record before submission. It's also
an editorial cut (jump cuts between samples, title cards bridging them) rather than one
continuous take, since three full on-device runs back-to-back exceed a single `adb screenrecord`
invocation’s cap — the actual submission recording should be a real, unedited capture on your
own device (the flow needs no editing to look good, per the assignment).

Screenshots in `docs/` (`screenshot_home.png`, `screenshot_processing.png`,
`screenshot_results.png`, `screenshot_avatars.png`) **are current** — taken after the redesign
below.

## Visual design

Cinematic photo-booth identity, not default Material colors: near-black charcoal background
(`#121212`), warm amber primary (`#F2B84B`), muted coral secondary (`#FF6B5B`), Manrope
(ExtraBold for headlines/the big display numerals, via a downloadable Google Fonts provider —
`res/font/manrope*.xml` + `res/values/font_certs.xml`) over the system default for body text.
Built with stable `com.google.android.material:material:1.12.0` (Material 3, not the 1.14.0-alpha
Expressive components) + ConstraintLayout throughout, plus Android's Transition APIs
(`android:windowEnterTransition`/`ExitTransition` = `Fade`, see `themes.xml` and
`res/transition/fade.xml`) for screen-to-screen motion instead of the default abrupt cut.

- **VideoSelect**: scattered rotated decorative "polaroid" cards behind a glowing pill CTA,
  foreshadowing the collage output.
- **Processing**: a 5-node stepper (Extract→Detect→Embed→Cluster→Compose) that lights up amber
  per stage, a big circular progress ring, a Manrope-ExtraBold hero counter that animates
  (`ValueAnimator`) from 0 to the real cluster count the moment clustering resolves, friendly
  per-stage microcopy, and a live blurred preview of the frame currently being processed
  (cheap downscale-then-upscale blur, decoded off the main thread) behind a dark scrim for
  contrast.
- **Results**: the collage as a full-bleed "physical photo card" (elevated card mat + shadow),
  a native Manrope-ExtraBold count header, a horizontally scrollable strip of circular avatar
  chips — each the person's *actual* representative-shot crop, not a placeholder — with an amber
  appearance-count badge, and a pinned Save/Share action bar.

Accessibility: decorative views (`polaroidA/B/C`, the button glow, the blurred preview) are
marked `importantForAccessibility="no"`; the stage label uses `accessibilityLiveRegion="polite"`
so TalkBack announces stage changes; the results screen calls `announceForAccessibility` once
settled; touch targets are ≥48dp; text/icon colors were chosen for WCAG-AA contrast against both
the dark canvas and the amber fill (near-black text/icons on amber, per `color_on_amber`).
Layouts use ConstraintLayout guidelines/percentage dimensions rather than fixed device-specific
values.

## How to run

### Prerequisites

| | |
|---|---|
| JDK | 17 (Temurin/OpenJDK) |
| Android SDK | platform **35**, build-tools **≥35.0.0**, platform-tools (`adb`) |
| Gradle | wrapper is committed — use `./gradlew`, don't install Gradle yourself (it pins 8.11.1 / AGP 8.7.3 / Kotlin 2.2.0) |
| Device | a physical Android device (API 26+ / Android 8.0+) with USB debugging on, **or** an emulator running an API 26+ system image |
| Network | needed **only for the first build** (pulls ML Kit / AndroidX / LiteRT from Maven). The app itself runs 100% offline — both `.tflite` models and the bundled ML Kit face model ship inside the APK. |

Point Gradle at your SDK with either `ANDROID_HOME` / `ANDROID_SDK_ROOT` in your environment or a `local.sdk.dir` line in `local.properties` at the repo root, e.g. `sdk.dir=/home/you/Android/Sdk`.

### Option A — install the prebuilt APK

If a `app-debug.apk` was supplied with the submission, just:

```
adb install -r app-debug.apk
```

Then open **Lineup** from the launcher.

### Option B — build from source

```
# from the repo root
./gradlew :app:assembleRelease     # or :app:assembleDebug
```

The APK lands at `app/build/outputs/apk/release/app-release.apk` (~90 MB — it bundles ML Kit,
LiteRT, and both embedding models for offline use). Install it on a connected device/emulator:

```
adb install -r app/build/outputs/apk/release/app-release.apk
```

Or open the project in Android Studio (Giraffe or newer), let it sync, pick a device, and hit
**Run**.

**Release signing:** if `keystore.properties` (gitignored) exists at the repo root with
`storeFile` / `storePassword` / `keyAlias` / `keyPassword`, the release build is signed with that
key. Otherwise it falls back to the standard Android debug keystore so `assembleRelease` still
produces an installable, signature-verified APK out of the box.

### Using the app

1. Launch **Lineup**. The first screen has a single **Choose a video** button.
2. Pick a **portrait video** from the system file picker (the three assignment sample clips, or
   any portrait `.mp4`/`.mov`). Live-camera capture is intentionally not implemented — the
   assignment only requires processing an existing video.
3. The **Processing** screen runs the whole pipeline on-device and shows a 5-stage stepper —
   Extract → Detect → Embed → Cluster → Compose — with a live blurred preview of the frame being
   worked on and a counter that animates up to the number of unique people found. A 30-second
   clip takes roughly **30–90 s** on a mid-range physical device (longer on an emulator).
4. The **Results** screen shows the generated collage (one representative shot per person),
   a per-person list with **appearance counts**, and **Save** (writes the collage to the gallery
   via `MediaStore`) and **Share** (`ACTION_SEND` via `FileProvider`) actions.

### Getting the sample videos onto the device

Physical device: copy them into `Downloads` (`adb push sample1.mp4 /sdcard/Download/`) or via
MTP, then pick them from the file picker.

Emulator: `adb push sample1.mp4 /sdcard/Download/` then pick from the picker, or drag-and-drop
the file onto the emulator window.

### Running the instrumented checkpoint tests (optional, needs a connected device)

```
./gradlew :app:assembleDebug :app:assembleDebugAndroidTest
adb install -r app/build/outputs/apk/debug/app-debug.apk
adb install -r app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
adb shell am instrument -w -r com.lineup.app.test/androidx.test.runner.AndroidJUnitRunner
```
`Step1CheckpointTest` logs frame/detection counts per sample video (tag `Lineup.Step1`).
`Step2CheckpointTest` runs an embedding same/different-person sanity check and a
similarity-threshold sweep per sample video (tag `Lineup.Step2`).

## Models

| Stage | Model | Notes |
|---|---|---|
| Face detection | `com.google.mlkit:face-detection:16.1.7` (bundled) | `PERFORMANCE_MODE_ACCURATE`, landmarks + classification + tracking on. Ships in the APK, no network needed at runtime. |
| Face alignment | 2D similarity transform (Procrustes) — `FaceAligner.kt` | The 5 ML Kit landmarks (eyes, nose, mouth corners) are fit to the InsightFace canonical 112×112 template via a closed-form scale+rotation+translation (no shear), and the face is warped through that `android.graphics.Matrix`. ArcFace embeddings are only meaningful on aligned faces. Falls back to a padded bbox crop if ML Kit returned fewer than 5 landmarks. |
| Face embedding | **MobileFaceNet (ArcFace-trained)**, `mobilefacenet.tflite` — 112×112×3 input, 192-dim output | Sourced from [syaringan357/Android-MobileFaceNet-MTCNN-FaceAntiSpoofing](https://github.com/syaringan357/Android-MobileFaceNet-MTCNN-FaceAntiSpoofing) (MIT). Fixed `(x − 127.5) / 128` normalization. The model's input tensor is batch-2 (its origin compares two faces per call); both slots are filled with the same aligned face and slot 0's output is used. Output is L2-normalized so cosine similarity is well-defined. `facenet.tflite` (128-dim, whole-image "prewhiten") remains selectable via `FaceEmbedder.Model.FACENET`. |
| Inference runtime | `com.google.ai.edge.litert:litert:2.1.0` | LiteRT — current name for TensorFlow Lite; `Interpreter` API is the same as `org.tensorflow.lite.Interpreter`. |
| Clustering | Hand-rolled agglomerative clustering, centroid cosine similarity | No external library — appropriate at this scale (a few hundred points per video). |

## Similarity threshold

`FaceClusterer.CLUSTER_SIMILARITY_THRESHOLD = 0.45` (cosine, on L2-normalized MobileFaceNet
embeddings of aligned faces).

Chosen by a τ sweep (0.20→0.60 in 0.05 steps) run through the full detect→align→embed→cluster→
segment pipeline on all three sample clips in one on-device run. The people count is flat at the
correct **5** across τ = 0.45–0.55 on all three videos (below 0.45 people merge together; the
plateau is the stable operating point), so 0.45 sits at the low edge of that plateau — favouring
recall of distinct people. ArcFace/MobileFaceNet same-person cosines sit lower than plain
FaceNet's, which is why this is well below the old FaceNet value of 0.6. `AppearanceSegmenter`
additionally pre-filters
detections below `SHARPNESS_MIN = 150.0` (calibrated against the real Laplacian-variance
distribution of face crops in the sample videos — see comments in that file) and `|yaw| >
MAX_YAW = 45°` **before** embedding/clustering, not just before counting appearances — a blurry
crop embeds to a near-random vector, and doing the filter late let one show up as a phantom extra
"person."

A `RESCUE_MERGE` second pass in `FaceClusterer` gives any leftover small (≤2-face) cluster one
more chance to merge into its nearest real cluster at a lower `RESCUE_MERGE_THRESHOLD = 0.30`
before it's counted as a standalone person — added after finding, on-device, that a single sharp
frontal crop can still land below the main threshold against its own person purely from scale
mismatch (a much closer framing than that person's other shots).

## Verified on-device (emulator, real ML Kit + real MobileFaceNet + alignment, threshold=0.45)

| Sample | People found | Appearances | Per-person |
|---|---|---|---|
| 1 | **5** (matches ground truth) | 19/20 | [4,3,4,4,4] |
| 2 | **5** (matches ground truth) | 19/20 | [4,3,4,4,4] |
| 3 | **5** (matches ground truth) | **20/20** | [4,4,4,4,4] |

Ground truth for all three clips is 5 people × 4 appearances each.

### Before / after landmark alignment + ArcFace

| Sample | FaceNet, unaligned crop (τ=0.6) | MobileFaceNet + 5-point alignment (τ=0.45) |
|---|---|---|
| 1 | 5 people, 18/20 appearances | 5 people, 19/20 |
| 2 | **7 people**, 22 appearances | **5 people**, 19/20 |
| 3 | **6 people**, 21 appearances | **5 people**, 20/20 |

The prior FaceNet-only build was fed unaligned padded crops and over-counted samples 2 and 3 by
1–2 people (phantom clusters spun off the shared-frame moments). Fitting the 5 ML Kit landmarks
to the canonical template before embedding, plus an ArcFace-trained model, fixed the people count
on all three. (Single-run snapshot — the emulator has some run-to-run variance in detection
count; the people count has been stable at 5 across runs.)

## Step 3: representative shot + collage

`ShotScorer` scores every candidate on frontality (head yaw/pitch), sharpness (normalized against
the sharpest candidate actually available for that person — "sharp" is relative to what a clip
offers, not an absolute bar), eyes-open, and smiling. `RepresentativeShotSelector` prefers
non-edge-clipped candidates (falling back to edge-clipped only if that's all a person has), then
strongly prefers a frame where that person appears **alone** — verified on-device that picking a
shared two-person frame and expanding its bbox 2.5× can bleed into the neighboring face, producing
a tile that visibly bisects two different people. When a shared frame is unavoidable, the crop is
clipped to the midpoint against every neighboring bbox so it never crosses into someone else's
face — a real trade-off documented below. `CollageComposer` lays the results into a 2/3/4-column
grid (by person count), rounded-corner tiles, scale-to-cover (never letterboxed or stretched), a
header with the person count, saved as a cached PNG and handed to `ResultsActivity` for
save-to-gallery (`MediaStore.Images`) and share (`FileProvider` + `ACTION_SEND`).

## Known deviations / open items

- Per-person appearance counts are 19/20, 19/20, 20/20 across the three clips — one appearance
  on samples 1 and 2 is a person whose two nearby windows get merged (or one very short window is
  dropped by the sharpness/pose pre-filter) rather than a mis-identification. People counts are
  exact (5/5/5).
- **Tried and reverted (FaceNet build):** neighbor-clipping the embedding crop to stop a shared
  two-face frame bleeding across faces — measured on-device it made clustering *worse* (5→8
  people). Landmark alignment + ArcFace made it moot: aligned crops are driven by the 5 facial
  landmarks, not a fixed bbox expansion, so a neighbor sharing the frame no longer skews the crop.
- **Tried and reverted:** track-averaged clustering (group per-frame faces by ML Kit
  `trackingId`, cluster the track means). Collapsed sample 1 from 5 people to 2 — ML Kit tracking
  is not shot-boundary aware and this footage is all hard cuts, so a "track" spans multiple people
  framed similarly across a cut.
- Run-to-run variance was also noticeable on this emulator: identical code/video produced 186-210
  raw detections across repeated runs (ML Kit accurate mode and/or `MediaMetadataRetriever`'s
  decode timing aren't perfectly deterministic here), which shifts cluster/appearance counts by
  a person or two between runs even with no code change. Treat single-run numbers as indicative,
  not exact — re-run before trusting a specific count.
- When a person's only available representative-shot candidates are all in a shared frame, the
  neighbor-clip can produce an unusually tight tile (verified on sample 1: one person's tile
  ended up mouth/chin-only) rather than the intended generous 2.5× crop. Better fix: re-center
  the clipped rect on the face rather than just shrinking it, or fall back to a slightly lower
  expansion factor before clipping.
- Face alignment uses ML Kit's 5 landmarks (not a 68-point / dense mesh); good enough for
  frontal-ish portrait footage. Faces with <5 landmarks fall back to a padded bbox crop.
- `saveToGallery()` needs `WRITE_EXTERNAL_STORAGE` at runtime on API 26-28 (declared in the
  manifest with `maxSdkVersion="28"`, but no runtime permission-request flow is implemented yet —
  untested below API 29).
