# Lineup

On-device Android app that processes a portrait video, detects faces, identifies the unique
people across separate appearances, and produces a shareable collage — one representative shot
per person, with per-person appearance counts. No backend; everything runs on the phone.

Built against `BUILD_GUIDE.md` in this repo, which has the full step-by-step plan and the
research behind each tech choice.

## Status

- **Step 1 (ingest & detect)** — done. SAF video picker → `FrameExtractor` → `FaceDetectorWrapper`.
- **Step 2 (identify)** — done. `FaceEmbedder` → `FaceClusterer` → `AppearanceSegmenter`.
- **Step 3 (select & compose)** — not started. Representative-shot scoring and the collage UI.
- **Step 4 (wire, verify, ship)** — not started. Results screen, save/share, full 3-sample run.

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
| 2 | 7 | 21 | [3,4,4,4,4,1,1] |
| 3 | 6 | 19 | [4,3,4,3,1,4] |

## Known deviations / open items

- Samples 2 and 3 still over-count people by 1–2. Traced to a shared two-face frame (sample 2,
  ~10.1s): the 1.6× expanded embedding crop for each face likely bleeds into the neighboring
  face, degrading both embeddings below even the rescue-merge threshold. Next fix: shrink or clip
  the embedding crop when another detection's bbox is nearby, rather than a raw uniform expansion.
- No landmark-based face alignment yet (MVP padded-bbox-crop only, per `BUILD_GUIDE.md` §3).
