# End-to-End Build Guide — Unique-Person Video Collage App
Researched and version-checked against current official docs (Sept 2026). Complements
`ARCHITECTURE.md` and `PLAN.md` from earlier — this file is the one to hand to Claude Code first;
the other two are deeper reference material.

---

## 1. Confirmed tech stack (checked, not guessed)

| Layer | Choice | Why / source |
|---|---|---|
| Language / UI | Kotlin, XML Views, MVVM, minSdk 26 | your call, already decided |
| Frame extraction | `MediaMetadataRetriever.getFrameAtTime()` | Mature, huge prior art, low implementation risk. A newer official alternative exists (`androidx.media3:media3-inspector`'s `FrameExtractor`, coroutine-`.await()`-friendly) but its docs were only last updated **March 2026** — too little tutorial/Stack-Overflow coverage to safely code-gen under a 2.5-day deadline. Use it only if `MediaMetadataRetriever` proves too slow. |
| Face detection | `com.google.mlkit:face-detection:16.1.7` (**bundled** variant, not the Play-Services dynamic one) | Confirmed current version on Google's own docs. Bundled = model ships in the APK, works fully offline with no first-run download wait — important since your demo can't depend on network availability. `PERFORMANCE_MODE_ACCURATE`, landmarks + classification enabled. |
| On-device inference runtime | `com.google.ai.edge.litert:litert:2.1.0` | **TensorFlow Lite was renamed LiteRT in 2024**, and Google's Sept 2026 guidance is explicit: "all future feature updates and performance enhancements will be exclusive to LiteRT." The `Interpreter` API is a same-method-name drop-in for `org.tensorflow.lite.Interpreter`, so there's no real cost to using the current name. Fallback: `org.tensorflow:tensorflow-lite` still works unchanged if LiteRT's Maven artifact gives you any friction. |
| Face embedding model | MobileFaceNet **or** FaceNet TFLite/LiteRT — see §3 | Both are real, sourceable, well-documented options; pick one, don't build both. |
| Clustering | Hand-rolled agglomerative clustering, cosine distance | No external library needed at this scale (a few hundred points per video) — a dependency here would be pure risk for zero benefit. |
| Async / coroutines | `kotlinx-coroutines-android`, `kotlinx-coroutines-play-services` (for ML Kit `Task.await()`) | Standard, stable. |
| Save / share | `MediaStore.Images` insert, `FileProvider` + `ACTION_SEND` | Scoped-storage compliant on API 26+ target, no extra runtime permission needed for saving your own generated image. |
| Gradle DSL | `androidResources { noCompress.add("tflite") }` | `aaptOptions` is **deprecated** as of AGP 8+; `androidResources` is the current property-based DSL. Confirmed on Android's own Gradle API reference. |

---

## 2. The 4-step build plan

Each step ends with something you can run on a real phone/emulator against Sample 1 before moving
on — don't let Claude Code "finish" a step without that checkpoint.

### **Step 1 — Ingest & Detect**
Get a video in, get faces out, prove the numbers look sane.

- SAF video picker (`ACTION_OPEN_DOCUMENT`, video mimetypes, persisted read permission).
- `FrameExtractor`: sample every ~166ms (~6fps) via `MediaMetadataRetriever`, JPEG-compress each
  frame to `cacheDir/frames/` immediately, recycle the bitmap, keep only the path. (This single
  decision prevents the OOM crash that a naive "hold all frames in memory" approach causes — a
  30s clip is ~175 frames × ~8MB decoded ≈ 1.4GB uncompressed.)
- `FaceDetectorWrapper`: ML Kit `16.1.7`, accurate mode, landmarks + classification on. Wrap
  `Task<List<Face>>` with `.await()`. Per detection, capture bbox, `headEulerX/Y/Z`,
  eye-open/smiling probabilities, and compute Laplacian-variance sharpness on the face crop before
  discarding the decoded bitmap.
- **Checkpoint:** run on Sample 1, log total frames extracted and total faces detected. Sanity
  check against the spec's worked example — you should see roughly two frames around 10.1–11.5s
  and 20.2–21.6s carrying 2 faces each, consistent with the "5 people × 4 appearances" ground
  truth.

### **Step 2 — Identify** (embed → cluster → segment)
This is the highest-risk, highest-value step — 50% of your grade is identity accuracy.

- `FaceEmbedder`: load the chosen model (§3) via LiteRT `Interpreter`, run inference per face crop
  (aligned or padded-crop, MVP-first — see §3), get a 128–192-dim vector.
  - **Before wiring into the full pipeline:** grab 2–3 crops of the same person and 2–3 of
    different people from Sample 1 by hand, embed them, print pairwise cosine similarities.
    Same-person pairs must clearly separate from different-person pairs. If they don't, the bug is
    almost always input normalization — don't proceed to clustering until this passes.
- `FaceClusterer`: agglomerative, centroid-based cosine similarity, threshold `τ` as a named
  constant.
  - **Checkpoint:** run the full frames→detect→embed→cluster chain on Sample 1, confirm
    `clusters.size == 5`. Sweep `τ` (e.g. 0.50 → 0.80 in 0.05 steps) until it lands there — this
    sweep, and the final value, go straight into your README.
- `AppearanceSegmenter`: pre-filter blur (`sharpness < SHARPNESS_MIN`) and extreme pose
  (`|yaw| > MAX_YAW`), then split each cluster's sorted detections into segments on any time gap
  `> APPEARANCE_GAP_MS` (~750ms default).
  - **Checkpoint:** `appearancesPerPerson` should sum to ≈20 on Sample 1, ideally 4 per person.
    Tune `APPEARANCE_GAP_MS`/`SHARPNESS_MIN` if segments are over- or under-split.

### **Step 3 — Select & Compose**
Turn identities into something worth looking at.

- `ShotScorer` + `RepresentativeShotSelector`: score surviving candidates per cluster on
  frontality, normalized sharpness, eyes-open, smiling; exclude edge-clipped bboxes unless a
  cluster has no unclipped candidate at all. Pick the max, expand its bbox 2–3× (clamped to frame
  bounds), crop, cache.
  - **Checkpoint:** visually inspect the chosen shot per person — frontal, sharp, not a
    tight/low-res crop.
- `CollageComposer`: responsive grid (2/3/4 cols by person count), rounded-corner tiles, common
  aspect ratio, subtle shadow, header text with person count. Save via `MediaStore.Images`,
  cache a share copy, wire `FileProvider` + `ACTION_SEND`.
  - **Checkpoint:** collage renders cleanly on Sample 1, appears in the gallery app after save,
    share sheet opens with the image attached.

### **Step 4 — Wire, Verify, Ship**
- UI: VideoSelect → Processing (live stage label, progress bar, running "N people found") →
  Results (collage image, per-person appearance-count list, save/share buttons).
- Full run on **all three** sample videos — no crashes, plausible (not necessarily
  ground-truthed) counts on Samples 2 and 3.
- README: build/setup steps, exact embedding model + source cited, final `τ` and how it was
  tuned, any deviation from this plan and why.
- Record the ≤60s demo (processing → counts → collage, legible, held long enough to review) for
  all three videos, build the debug APK, push the repo, submit before **Sunday Sept 6, 11:59 PM
  IST**.

---

## 3. Sourcing the face embedding model — two vetted, real options

Don't let Claude Code invent or hallucinate a model file — it must come from a real source you
can cite in the README.

**Option A — MobileFaceNet (recommended for size/speed)**
- 112×112×3 input → 128–192-dim output, ~5MB, ArcFace/InsightFace-trained.
- Sourceable pre-converted `.tflite` from community Android demo repos such as
  `syaringan357/Android-MobileFaceNet-MTCNN-FaceAntiSpoofing` or
  `NaumanHSA/Android-Face-Recognition-MTCNN-FaceNet` (both mirror the same model, originally
  trained via `sirius-ai/MobileFaceNet_TF`). **Check that repo's own preprocessing code** (not
  just its README) for the exact input normalization before wiring it in — this is the detail
  most likely to silently break embedding quality.

**Option B — FaceNet (recommended if you want clearer, more current documentation to follow)**
- 160×160×3 input → 128-dim output.
- `shubham0204/FaceRecognition_With_FaceNet_Android` is a well-documented, actively-referenced
  Kotlin repo (with an accompanying ProAndroidDev writeup) that bundles the FaceNet `.tflite`
  asset and shows exactly how to run inference in Kotlin. Note: their tutorial uses MediaPipe for
  *detection* and ObjectBox for persistent storage — you don't need either; take only their
  **embedding model + inference code**, and feed it crops from your own ML Kit detections instead
  (you don't need persistent storage since each video is processed fresh, no cross-session
  identity database required).

Either is a legitimate, citable choice. Pick one before starting Step 2 — don't let Claude Code
default to a placeholder or fabricated model path.

**Alignment:** start with the MVP padded-bbox-crop-then-resize approach (no landmark-based
alignment). Only add proper 5-point similarity-transform alignment (using ML Kit's eye/nose/mouth
landmarks) if the Step 2 same/different-person sanity check looks weak — it's a real accuracy
improvement but nontrivial to implement correctly under time pressure.

---

## 4. Master prompt for Claude Code

```
I'm building an Android app (Kotlin, minSdk 26, XML/Views, MVVM) that processes a portrait video
on-device to detect faces, identify unique people, and produce a shareable collage. No backend —
everything runs locally. Build it in the 4 steps below, and don't move to the next step until the
checkpoint for the current one passes on a real video.

CONFIRMED TECH STACK (use these, don't substitute without telling me why):
- Frame extraction: MediaMetadataRetriever.getFrameAtTime(), sampling every ~166ms (~6fps).
  Compress each frame to JPEG in cacheDir/frames/ immediately and recycle the bitmap — never hold
  more than one decoded frame in memory at a time (a 30s clip is ~175 frames; holding them all
  decoded would OOM).
- Face detection: com.google.mlkit:face-detection:16.1.7 (bundled, not Play-Services-dynamic),
  PERFORMANCE_MODE_ACCURATE, landmarks + classification enabled. Wrap the Task with
  kotlinx-coroutines-play-services' .await().
- On-device inference: com.google.ai.edge.litert:litert:2.1.0 (LiteRT — TensorFlow Lite's current
  name/package; API is a drop-in match for org.tensorflow.lite.Interpreter, so use that if LiteRT's
  artifact gives you trouble).
- Face embedding model: [FILL IN — MobileFaceNet from <repo> OR FaceNet from
  shubham0204/FaceRecognition_With_FaceNet_Android — pick one before we start Step 2 and tell me
  the exact source + input size + output dims + normalization you're using].
- Clustering: hand-rolled agglomerative clustering over cosine distance, no external library.
- Save/share: MediaStore.Images insert + FileProvider + ACTION_SEND.
- Gradle: use androidResources { noCompress.add("tflite") } (aaptOptions is deprecated).

STEP 1 — Ingest & Detect:
  SAF video picker -> FrameExtractor (as specified above, Dispatchers.IO, emits progress via
  StateFlow/LiveData) -> FaceDetectorWrapper (Dispatchers.Default, sequential) capturing bbox,
  headEulerX/Y/Z, eye-open/smiling probabilities, and a Laplacian-variance sharpness score per
  detection. CHECKPOINT: run on a real sample video, log total frames and total face detections,
  confirm the numbers look plausible before we continue.

STEP 2 — Identify (embed -> cluster -> segment):
  FaceEmbedder using the model I specify above (padded-bbox-crop MVP alignment, not full landmark
  alignment yet). Before wiring into the pipeline, write a standalone check comparing cosine
  similarity of same-person vs different-person crops and show me the numbers.
  Then FaceClusterer (agglomerative, cosine, threshold as a named constant CLUSTER_SIMILARITY_
  THRESHOLD) and AppearanceSegmenter (pre-filter low-sharpness/extreme-pose detections, then split
  by time gap > APPEARANCE_GAP_MS ~750ms). CHECKPOINT: run end-to-end on the sample video with
  known ground truth (5 people, 4 appearances each = 20 total) and help me sweep the threshold
  until cluster count and appearance counts match.

STEP 3 — Select & Compose:
  ShotScorer (frontality from head pose, normalized sharpness, eyes-open, smiling, penalize/exclude
  edge-clipped bboxes) + RepresentativeShotSelector (generous 2-3x bbox crop, never a tight face
  crop). CollageComposer: responsive grid layout by person count, rounded corners, consistent
  aspect ratio, save via MediaStore, share via FileProvider/ACTION_SEND. CHECKPOINT: show me the
  rendered collage for the sample video before we move on.

STEP 4 — Wire, Verify, Ship:
  UI: VideoSelect -> Processing (stage label, progress bar, live person/appearance counts) ->
  Results (collage + per-person appearance count list + save/share buttons). Then help me run all
  three sample videos end to end and flag anything that looks off before I write the README and
  record the demo.

ARCHITECTURE: MVVM, Repository pattern orchestrating the pipeline as one suspend function exposing
a ProcessingState sealed class, one class per pipeline stage, all processing off the main thread.

Ask me before adding any dependency not listed above.
```

---

## 5. Sources checked for this guide

- ML Kit Face Detection (Android) — developers.google.com/ml-kit/vision/face-detection/android
  (confirmed current, last updated 2026-09-01; confirmed bundled version `16.1.7`)
- LiteRT migration guide — developers.google.com/edge/litert/migration (confirmed TFLite → LiteRT
  rename, current package `com.google.ai.edge.litert:litert`, drop-in API compatibility)
- Media3 frame extraction — developer.android.com/media/media3/inspector/extract-frames
  (confirmed the newer `FrameExtractor` API exists but is very recent — used to justify sticking
  with `MediaMetadataRetriever` for this deadline)
- Android Gradle Plugin `AndroidResources` DSL — developer.android.com Gradle API reference
  (confirmed `aaptOptions` deprecated in favor of `androidResources`)
- `shubham0204/FaceRecognition_With_FaceNet_Android` (GitHub) + accompanying ProAndroidDev
  article — confirmed as a real, current, documented FaceNet-on-Android reference
- `syaringan357/Android-MobileFaceNet-MTCNN-FaceAntiSpoofing` and
  `NaumanHSA/Android-Face-Recognition-MTCNN-FaceNet` (GitHub) — confirmed as real, existing
  MobileFaceNet `.tflite` sources
