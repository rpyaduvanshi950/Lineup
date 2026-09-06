# Progress checkpoint — 2026-09-06

Snapshot of where the project stands before starting the ArcFace / face-alignment work.
If that work regresses accuracy, this commit (`da3a68b`) is the clean point to return to.

## Done and working

| Step | State |
|---|---|
| 1 — ingest & detect | SAF picker → `FrameExtractor` (MMR `OPTION_CLOSEST`, ~6fps) → `FaceDetectorWrapper` (ML Kit 16.1.7, accurate mode). ~185–210 detections/video. |
| 2 — identify | `FaceEmbedder` (FaceNet 128-d, whole-image standardize, 1.6× padded crop, **no alignment**) → `FaceClusterer` (agglomerative, centroid cosine, τ=0.6, + rescue-merge pass) → `AppearanceSegmenter` (blur/pose pre-filter, split on gap > 750ms). |
| 3 — select & compose | `ShotScorer` (frontality/sharpness/eyes/smile) → `RepresentativeShotSelector` (prefer solo frames, neighbor-clip on shared) → `CollageComposer` (2/3/4-col grid), wired to `ResultsActivity` with save (MediaStore) / share (FileProvider). |
| 4 — ship | Full flow verified on-device end-to-end for all 3 samples. Cinematic dark redesign (Manrope, amber, stepper, blurred preview, avatar chips, Fade transitions). |

## Current accuracy (on-device, emulator, τ=0.6)

**Sample 1** (the only one with published ground truth — 5 people × 4 appearances = 20):

```
person0: 1.7-3.0s   8.5-9.6s    18.4-19.8s  28.6-29.9s   (4)
person1: 0.0-1.3s   10.1-11.3s  22.9s                    (3)
person2: 5.1-6.3s   10.1-11.3s  16.8-18.1s  23.6-24.7s   (4)
person3: 6.8-8.0s   15.1-16.4s  20.3-21.4s  26.9-28.1s   (4)
person4: 3.5-4.6s   13.4-14.8s  20.3-21.4s  25.2-26.4s   (4)
```

→ **5/5 people, 19/20 appearances.** Both spec-documented shared-frame moments (10.1s A+B, 20.3s C+D) are correctly split across the right pairs.

**Samples 2 & 3:** over-count people by 1–2 (run-to-run: 6–7 people, 19–24 appearances). No published ground truth. This is the residual weakness.

## Tried and reverted (measured worse)

- **Neighbor-clipping the embedding crop** on shared frames — clustering got worse (5→8 people). Clipping shrinks/skews the crop for every shared-frame detection, most of which weren't actually bleeding.
- **Track-averaged clustering** (group per-frame faces by ML Kit `trackingId`, cluster the track means) — catastrophic: Sample 1 collapsed 5→2 people. ML Kit tracking isn't shot-boundary aware and this footage is all hard cuts, so a "track" spans multiple people framed similarly across a cut.

## ArcFace + 5-point alignment — DONE, kept (improved accuracy)

Shipped:
1. 5 ML Kit landmarks (spatially ordered) threaded through `FaceDetection.landmarks`.
2. `FaceAligner` — closed-form 2D Procrustes similarity transform (scale+rotation+translation),
   fits the 5 landmarks to the InsightFace canonical 112 template, warps via `android.graphics.Matrix`.
3. `FaceEmbedder` rewritten with a `Model` enum; default `MOBILEFACENET` (`mobilefacenet.tflite`,
   ArcFace-trained, 112×112 → 192-d, `(x−127.5)/128`, batch-2 input handled). Aligns via
   `FaceAligner`, falls back to a padded bbox crop when <5 landmarks.
4. τ re-swept on-device across all 3 samples in one run: people count flat at 5 for τ=0.45–0.55.
   `CLUSTER_SIMILARITY_THRESHOLD` 0.6 → **0.45**, `RESCUE_MERGE_THRESHOLD` 0.45 → **0.30**.

### Accuracy after (on-device, emulator, τ=0.45) — ground truth 5 people × 4 each

| Sample | People | Appearances | Per-person |
|---|---|---|---|
| 1 | 5/5 | 19/20 | [4,3,4,4,4] |
| 2 | 5/5 | 19/20 | [4,3,4,4,4] |
| 3 | 5/5 | **20/20** | [4,4,4,4,4] |

Samples 2 & 3 previously over-counted by 1–2 people — now exact.
