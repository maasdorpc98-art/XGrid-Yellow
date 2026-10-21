# XGrid Yellow Native — staged implementation

## What Stage 1 proves
- Full source project under version control.
- Separate package: `com.xgrid.yellownative`.
- Android app compiles and opens an honest status screen.
- No old APK re-signing, no secrets, no fake visual analysis claims.

## Design contract from existing Yellow
- A selected MORE LIKE THIS anchor is recorded without moving the page.
- FIND MORE initiates a bounded visual analysis and performs at most **one** visible reorder.
- RE-SEARCH alone triggers retrieval/reload.
- Core similarity remains **Geometry 70% / Motion 30%** with confidence-aware weight reduction.
- No semantic Goal, Signal, Tag, or site score may silently contaminate visual similarity.
- Two-stage candidate retrieval: cheap frame/thumbnail geometry, followed by detailed analysis for shortlisted GIFs.
- Visual signature caching and diagnostic counters are first-class features.

## Next stages (not implemented yet)
1. **MediaPipe Pose Landmarker**: add Tasks Vision, bundle the Full model, compare pose results and confirm failure modes, keep it offline.
2. **GIF decoder and frame scheduler**: deterministic timing and frame uniqueness checks, robust disposal/transparency handling, no unreliable animated-IMG sampling.
3. **Geometry & motion**: extract landmarks + spatial fallback, optical/block motion, camera compensation and temporal rhythm; compute confidence.
4. **Search/retrieval**: browser/WebView front end, compliant Google results integration, candidate acquisition & caching; keep anchor/query state stable.
5. **XSearch Quality UI**: match supplied interface, wire genuine actions and diagnostics.
6. **Optimization / testing**: unit + instrumentation + on-device benchmarks; check recall, latency, cold starts, failures.

This is a **rebuild**, not the recovered original native app source. Do not overwrite `com.xgrid.yellow1` or import its private keystore.
