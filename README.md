# Lineup

On-device Android app that processes a portrait video, detects faces, identifies the unique
people across separate appearances, and produces a shareable collage — one representative shot
per person, with per-person appearance counts. No backend; everything runs on the phone.

Built against `BUILD_GUIDE.md` in this repo, which has the full step-by-step plan and the
research behind each tech choice.

## Status

- **Step 1 (ingest & detect)** — done. SAF video picker → `FrameExtractor` → `FaceDetectorWrapper`.
- **Step 2 (identify)** — done. `FaceEmbedder` → `FaceClusterer` → `AppearanceSegmenter`.
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

## Build & setup

1. Android Studio (or the CLI) with SDK platform 35 and build-tools ≥35, JDK 17.
2. `./gradlew :app:assembleDebug` — first run needs network to pull ML Kit / AndroidX / LiteRT.
   `app/src/main/assets/facenet.tflite` (the FaceNet embedding model) is already bundled in
   this repo.
3. Install on a physical device or emulator and run. Live-camera capture is not implemented or
   required — pick an existing portrait video via the file picker.

To run the instrumented checkpoint tests (need a connected device):
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
| Face embedding | **FaceNet**, `facenet.tflite` — 160×160×3 input, 128-dim output | Sourced from [shubham0204/FaceRecognition_With_FaceNet_Android](https://github.com/shubham0204/FaceRecognition_With_FaceNet_Android) (Apache-2.0). Preprocessing matches that repo's `FaceNetModel.kt` exactly: bilinear resize, then whole-image standardization `(x - mean) / max(std, 1/√N)` (the classic FaceNet "prewhiten" step) — getting this normalization wrong is the most common way to silently break embedding quality, so it was copied verbatim rather than re-derived. Output is L2-normalized so cosine similarity is well-defined. |
| Inference runtime | `com.google.ai.edge.litert:litert:2.1.0` | LiteRT — current name for TensorFlow Lite; `Interpreter` API is the same as `org.tensorflow.lite.Interpreter`. |
| Clustering | Hand-rolled agglomerative clustering, centroid cosine similarity | No external library — appropriate at this scale (a few hundred points per video). |

## Similarity threshold

`FaceClusterer.CLUSTER_SIMILARITY_THRESHOLD = 0.6`.

Chosen by: (1) a standalone same-vs-different-person cosine check on hand-picked crops from all
three sample videos — same-person pairs scored 0.70–0.98, different-person pairs scored
-0.11–0.48, a clean gap; then (2) a τ sweep (0.50→0.80 in 0.05 steps) run through the full
detect→embed→cluster→segment pipeline. τ=0.60–0.65 was the only range that produced exactly 5
clusters on all three sample videos, with appearance counts close to the expected 4-per-person
(20 total): 18/20, 20/20, 19/20 across the three clips. Above τ=0.70, a single person's varying
expression/pose starts splitting into extra clusters even as raw appearance totals drift upward,
which is the wrong kind of "more appearances." `AppearanceSegmenter` additionally pre-filters
detections below `SHARPNESS_MIN = 150.0` (calibrated against the real Laplacian-variance
distribution of face crops in the sample videos — see comments in that file) and `|yaw| >
MAX_YAW = 45°` **before** embedding/clustering, not just before counting appearances — a blurry
crop embeds to a near-random vector, and doing the filter late let one show up as a phantom extra
"person."

A `RESCUE_MERGE` second pass in `FaceClusterer` gives any leftover small (≤2-face) cluster one
more chance to merge into its nearest real cluster at a lower `RESCUE_MERGE_THRESHOLD = 0.45`
before it's counted as a standalone person — added after finding, on-device, that a single sharp
frontal crop can still land below the main threshold against its own person purely from scale
mismatch (a much closer framing than that person's other shots).

## Verified on-device (emulator, real ML Kit + real FaceNet, threshold=0.6)

| Sample | People found | Appearances | Per-person |
|---|---|---|---|
| 1 | **5** (matches ground truth) | 18/20 | [4,2,4,4,4] |
| 2 | 7 | 22 | [4,4,4,4,4,1,1] |
| 3 | 6 | 21 | [5,1,4,4,3,4] |

(Single-run snapshot — see the run-to-run variance note below.)

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

- Samples 2 and 3 still over-count people by 1–2 (see the on-device table above). Traced to a
  shared two-face frame (sample 2, ~10.1s): the 1.6× expanded *embedding* crop for each face
  likely bleeds into the neighboring face, degrading both embeddings below even the rescue-merge
  threshold. **Tried and reverted:** applying the same neighbor-clipping used for the collage crop
  to the embedding crop in `FaceEmbedder` — measured on-device, it made clustering *worse* (sample
  1 went from 5 people/18 appearances to 8 people/20). Clipping shrinks/skews the crop for every
  detection that merely shares a frame with someone else, and most of those weren't actually
  bleeding into their neighbor; the resulting asymmetric crop hurt more embeddings than the bleed
  itself did. The real fix is more targeted: only clip when the *unclipped* 1.6x rect would
  actually overlap the neighbor's bbox, not whenever a neighbor merely exists in the frame.
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
- No landmark-based face alignment yet (MVP padded-bbox-crop only, per `BUILD_GUIDE.md` §3).
- `saveToGallery()` needs `WRITE_EXTERNAL_STORAGE` at runtime on API 26-28 (declared in the
  manifest with `maxSdkVersion="28"`, but no runtime permission-request flow is implemented yet —
  untested below API 29).
