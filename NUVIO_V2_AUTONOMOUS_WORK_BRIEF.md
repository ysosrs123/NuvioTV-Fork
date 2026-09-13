# Nuvio V2 — Autonomous Work Brief

## Mission

Implement the complete Nuvio V2 UI/UX redesign in the GitHub repository:

`ysosrs123/NuvioTV-Fork`

Use the attached `Nuvio_V2_UI_UX_Technical_Design_Spec_v0.4.md` as the primary product and technical source of truth. Read it in full before making code changes.

Start from the latest `nuvio-test` branch and create:

`feature/nuvio-v2-ui`

Continue autonomously until the implementation is complete, a pull request targeting `nuvio-test` is ready, or a genuine blocker prevents further progress.

Do not stop after producing a plan. Implement the code.

---

## Autonomy

Do not ask for approval of routine engineering decisions when the specification provides enough direction.

Make sensible, reversible decisions; implement them; test them; document material deviations; and continue.

Only stop for a genuine blocker such as:

- missing credentials or repository permissions;
- a destructive/non-reversible decision with no safe alternative;
- a major unresolved product decision not covered by the specification;
- a dependency or licensing issue requiring owner approval;
- physical-device behaviour that cannot reasonably be resolved without hardware testing.

If uncertainty can safely be handled through a preference, abstraction, feature flag, or sensible default, do that instead of stopping.

---

## Baseline first

Before V2 code changes:

1. Update/check out `nuvio-test`.
2. Record the exact base commit SHA.
3. Create `feature/nuvio-v2-ui` from that SHA.
4. Create `NUVIO_V2_IMPLEMENTATION_STATUS.md`.
5. Record the base SHA and initial branch HEAD.
6. Run the repository's current build, lint and tests.
7. Record any pre-existing failures.
8. Capture whatever current UI/JankStats performance baseline is practical.
9. Commit this baseline/status scaffolding as the first feature-branch checkpoint.

Optionally create a lightweight baseline tag such as `nuvio-v2-baseline-<date>` if permissions and repository conventions make that appropriate. The immutable base SHA is the essential recovery reference.

After establishing the baseline, continue directly into implementation without waiting for approval.

---

## Architectural rules

Do not rebuild Nuvio from scratch.

Implement V2 as a **new presentation layer over the existing Nuvio core**.

Preserve and reuse existing:

- ViewModels and repositories;
- navigation/domain state;
- account/profile/session logic;
- source resolution;
- Media3/player controller and playback state;
- AFR/frame-rate matching;
- audio passthrough;
- subtitles;
- tracking/watch progress;
- poster/image infrastructure;
- diagnostics and Stats-for-Nerds data;
- existing performance optimisations.

Rebuild presentation components only where V2 genuinely needs a different UI structure.

Original and V2 renderers must share business/playback logic.

---

## Preserve Original Nuvio

The current Modern UI must remain available.

Implement a device-local `Interface Experience` choice:

- `Nuvio V2`
- `Original Nuvio`

Original mode should retain the current Modern UI and current theme behaviour as closely as possible.

V2-specific glass/focus/navigation/player changes must not accidentally alter Original mode.

Do not resurrect retired Classic/Grid home layouts merely to satisfy this requirement.

---

## V2 systems to implement

Follow v0.4 for exact behaviour and design intent.

The V2 system includes:

- Cinematic Glass and Pure Liquid Dark;
- selectable navigation styles;
- Glass Lift and Cinematic Focus;
- existing Nuvio colours reworked primarily as accent/tint choices;
- optional Adaptive Artwork accent;
- Neutral / Accent / Artwork glass tint;
- Control Deck and Invisible Player chrome;
- Minimal Settings as the default V2 settings presentation;
- optional Glass Settings;
- Automatic and manual UI Scale;
- Automatic / Performance / Enhanced / Maximum Visual Quality;
- profile landing screen;
- reusable avatar system;
- profile management/avatar picker treatment;
- short profile-entry Nuvio ident while Home prepares;
- dense Home/Browse;
- Details and Search;
- redesigned Player, HUD and utility sheets.

Keep appearance choices modular rather than hard-wiring navigation/focus/player style to one visual theme.

---

## Automatic UI Scale

Treat UI Scale as geometry, not as a performance setting.

Do not determine it from HDMI resolution alone.

Use actual window metrics, density and logical dp canvas as specified.

Known calibration case:

- Ugoos AM9 Pro on the same TV looks appropriately roomy;
- Fire TV Stick 4K Max looks noticeably more zoomed-in.

Add diagnostics for window px, density, densityDpi, screen dp, effective canvas, active `Display.Mode`, output resolution/refresh rate, and resolved Nuvio scale.

Avoid model-specific hacks unless measurements prove the generic resolver is insufficient.

Automatic scale must be stable and must not fluctuate during normal use.

Keep manual scale and fine adjustment available.

---

## Automatic Visual Quality

Implement:

- Automatic
- Performance
- Enhanced
- Maximum

Visual Quality changes graphical expense, not layout or features.

Automatic should use capability information for its initial tier, but measured rendering performance should ultimately outrank spec-sheet assumptions.

Reuse `JankStats` where useful.

Use strong hysteresis so quality does not oscillate.

Performance mode must still look intentional and polished.

---

## 60 Hz / 60 fps target

When browsing on a display operating near 60 Hz, target 60-fps-quality UI behaviour (approximately 16.67 ms/frame).

Measure it in release builds.

Pay particular attention to:

- rapid poster navigation;
- row changes;
- sidebar expansion/collapse;
- hero/backdrop changes;
- Search;
- Settings;
- profile navigation;
- player overlays and side sheets.

Prefer transform/render-phase animation over focus-driven remeasurement.

Avoid per-card live blur, excessive offscreen layers, full-screen blur, focus-driven layout resizing, expensive image processing during rapid navigation, high-frequency whole-screen recomposition, avoidable allocation churn and excessive overdraw.

Add/update Macrobenchmarks for key D-pad journeys and maintain Baseline Profile coverage where appropriate.

Do **not** force 60 Hz during video playback. Preserve AFR/frame-rate matching and the currently selected playback display mode.

---

## Profile entry and ident

The profile landing screen is part of the premium V2 experience.

Implement the avatar/focus/profile work described in v0.4.

After profile selection, implement the short Nuvio entry ident:

`Profile selected -> confirmation -> Nuvio ident -> populated Home`

It must not be an artificial fixed delay.

Use:

- a minimum natural animation duration;
- a Home-readiness signal;
- transition as soon as both are satisfied;
- a graceful hold/fallback if Home needs longer.

While the ident plays, prepare initial Home rows, first-visible posters, hero/backdrop, resume/watchlist state where needed, and the first Home focus target.

If a final production video asset is unavailable, do not block the project. Build the complete readiness/transition architecture and use a tasteful lightweight placeholder/procedural ident that can later be replaced without structural changes.

Do not bundle copyrighted third-party media into the ident.

---

## Player

Preserve the existing player engine and behaviour.

Refactor only the presentation layer around the same state/events.

Implement the V2 player controls, progressive disclosure, HUD, Audio, Subtitles, Source, Episodes, Start Over and advanced controls described in the spec.

Do not blur the live decoded video surface.

Preserve low-frequency HUD sampling and avoid player-wide recomposition.

---

## Incremental implementation

Do not implement the project as one giant commit.

Use checkpoints approximately like:

1. Audit, baseline and diagnostics
2. Original/V2 renderer + preference foundation
3. Automatic UI Scale
4. Visual Quality framework + performance baseline
5. V2 design primitives (palette/glass/focus/motion/sheets)
6. Profile landing + avatars + entry ident
7. Home + Floating Sidebar
8. Top/additional navigation
9. Details + Search
10. Minimal Settings + Appearance UI
11. Player + HUD + Audio/Subtitles/Source
12. Automatic performance governor
13. Compatibility, accessibility, benchmarking and polish

Each checkpoint should compile and be independently testable where practical.

Use meaningful commits.

---

## Build/test loop

After meaningful changes:

1. format/lint where configured;
2. compile;
3. run relevant tests;
4. run instrumentation/UI tests where available;
5. run release/performance benchmarks when rendering behaviour changed;
6. inspect failures;
7. fix regressions;
8. commit a coherent checkpoint;
9. continue.

Do not blame V2 for failures that already exist on the recorded base SHA.

---

## Performance guardrails

Do not accept prettier UI at the cost of materially worse:

- playback;
- frame pacing;
- D-pad latency;
- memory pressure;
- startup;
- image loading;
- A/V synchronisation;
- AFR;
- source switching;
- subtitle responsiveness.

Profile before making large speculative performance rewrites.

---

## Visual judgement

The target should feel:

- dark;
- minimal;
- cinematic;
- selective liquid-glass depth;
- restrained rather than flashy;
- dense rather than oversized;
- sophisticated and TV-native;
- content-first.

Avoid generic AI-looking sci-fi decoration, excessive neon/glow, giant empty layouts, random slogans and gratuitous gradients.

It should look like a shipping premium TV application, not concept art.

---

## Physical-device validation

Target lower-end 2 GB Android TV hardware as well as stronger 4 GB-class devices.

Do not optimise only for the Ugoos AM9 Pro.

When physical validation is required, do not stop at a vague "test this on device." Leave a specific grouped test procedure containing:

- exact APK/build;
- exact screen/action;
- values/logs/screenshots to capture;
- expected success/failure criteria.

Group hardware-validation requests so the owner is not required to test every small change.

---

## Repository safety

Work only on `feature/nuvio-v2-ui`.

Do not force-push or rewrite unrelated history.

Do not commit secrets, credentials, local-only configuration, generated build output, or unnecessary huge binary assets.

Respect current repository conventions and licenses.

Do not remove existing functionality simply because it complicates the redesign.

---

## Implementation status file

Maintain `NUVIO_V2_IMPLEMENTATION_STATUS.md` with:

- base `nuvio-test` SHA;
- current/final feature-branch HEAD SHA;
- completed phases;
- current phase;
- important architectural decisions;
- meaningful performance measurements;
- known issues;
- remaining physical-device validation.

Keep it concise and useful rather than writing a diary.

---

## Definition of done

Do not declare completion based only on visual resemblance.

Completion requires, at minimum:

- Original Nuvio still works and is selectable;
- V2 appearance architecture works;
- Automatic/manual UI Scale works;
- Automatic/manual Visual Quality works;
- profile landing, avatars and entry-ident readiness flow work;
- Home/Browse works;
- navigation alternatives work;
- Details and Search work;
- Settings works;
- player controls/HUD/panels work;
- existing playback functionality remains intact;
- no known severe D-pad/focus regressions;
- migration is safe;
- release build succeeds;
- tests are in an acceptable state;
- major 60 Hz browsing paths have measurable performance results;
- unresolved physical-device validation is clearly documented.

---

## Final delivery

When ready:

1. perform a final diff/review against the recorded `nuvio-test` base;
2. explicitly check for accidental playback/business-logic changes;
3. run final builds/tests/benchmarks;
4. update `NUVIO_V2_IMPLEMENTATION_STATUS.md`;
5. record base SHA and final feature HEAD SHA;
6. push the feature branch;
7. open a pull request targeting `nuvio-test`;
8. provide a concise PR description covering architecture, major features, migration, performance results, known limitations and remaining device testing.

**Continue working autonomously until this point is reached or a genuine blocker prevents further progress.**
