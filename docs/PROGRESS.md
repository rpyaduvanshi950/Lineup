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

## Next: ArcFace + 5-point alignment (in progress)

Rationale: FaceNet is fed **unaligned** padded crops, but it (and especially ArcFace models) are trained on faces aligned to canonical landmark positions. Expected to be the biggest single lever for the Samples 2/3 over-counting.

Plan:
1. Thread the 5 ML Kit landmarks (eyes, nose, mouth corners) through to `FaceDetection`.
2. `FaceAligner`: Umeyama similarity transform → warp to the canonical 112×112 InsightFace template.
3. Try alignment with the **existing** FaceNet first (isolate the alignment effect), measure.
4. Swap to MobileFaceNet (ArcFace-trained, 112×112 → 192-d, `(x-127.5)/128` normalization), re-sweep τ (ArcFace cosine scale is much lower — expect ~0.25–0.4), measure.
5. Revert whichever step doesn't help, same as the two dead ends above.
