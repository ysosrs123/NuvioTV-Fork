# Nuvio V2 UI/UX Technical Design Specification

**Document status:** Working technical specification — second-pass revised  
**Version:** 0.4 — Profile entry ident / loading visual  
**Date:** 12 September 2026  
**Target repository:** `ysosrs123/NuvioTV-Fork`, branch `nuvio-test`  
**Primary platform:** Android TV / Google TV, Kotlin + Jetpack Compose for TV  
**Design target:** TV-first, remote-first, high-performance media browsing and playback  
**Primary hardware envelope:** 2–4 GB Android TV devices through higher-end boxes, with 1080p and 4K televisions

---

## 1. Purpose

This document converts the Nuvio V2 visual explorations into an implementation-oriented UI architecture for the NuvioTV fork.

The objective is not to produce a decorative skin. The objective is to create a coherent visual system that:

- looks premium on capable hardware;
- remains fast on memory-constrained Android TV devices;
- retains Nuvio's dense, efficient content browsing;
- behaves consistently across devices that expose different Android logical canvases on the same physical TV;
- preserves the fork's playback-first priorities;
- gives users meaningful customisation without turning Settings into a wall of switches;
- can be introduced incrementally rather than by rewriting the entire app.

The V2 design therefore separates **appearance**, **layout density**, and **rendering cost** into independent systems.

## 1.1 Second-pass architecture conclusions

A second review of the design and current fork produced four important additions that are now considered architectural requirements rather than optional refinements:

1. **Original Nuvio must remain available.** V2 should ship as an alternate presentation system, not as a destructive replacement for the current Modern UI.
2. **V2 should be a selective presentation-layer rebuild over the existing core.** Playback, repositories, ViewModels, source resolution, tracking, diagnostics and other working business logic should be shared rather than rewritten.
3. **60 Hz / 60 fps-quality browsing should be an explicit engineering target on displays operating near 60 Hz.** At 60 Hz, the UI has approximately 16.67 ms per frame; this target must be benchmarked rather than judged only by feel.
4. **Automatic Visual Quality must protect frame-time performance first.** Device specifications may choose an initial tier, but measured rendering behaviour should be allowed to downgrade the tier conservatively when the current effects budget causes persistent jank.

These additions materially strengthen the implementation plan without changing the visual direction established in the first design pass.

---

# 2. Product principles

## 2.1 Content first

Artwork and video remain the dominant visual elements. Glass is a framing material, not the content itself.

A common failure mode with "liquid glass" designs is applying blur, tint and glowing borders to every object. Nuvio V2 should instead use glass selectively:

- navigation surfaces;
- dialogs and side sheets;
- player controls;
- selected utility panels;
- focus emphasis where it improves orientation.

Poster artwork itself should usually remain clean and unfiltered.

## 2.2 Dense, roomy browsing

The preferred home experience is not giant cards with only a handful of titles visible. Nuvio should continue to display **many relatively small posters**, with a comfortable amount of negative space and strong focus feedback.

"Premium" should come from composition, focus, motion, depth and material treatment — not from making everything larger.

## 2.3 TV-first interaction

Every interaction must work naturally with a D-pad remote.

Each directional press should have a predictable purpose. Focus movement takes priority over visual flourish. No animation may make the UI feel as though focus is lagging behind the remote.

## 2.4 The same Nuvio on different hardware

Performance mode must not look like a separate cheap UI.

The information architecture, typography hierarchy, card geometry and navigation model should remain the same. Lower rendering tiers should reduce expensive visual effects rather than remove functionality.

## 2.5 Playback is sacred

The redesigned UI must never compromise video playback stability, high-bitrate streaming performance, audio passthrough or frame pacing.

Player chrome must therefore be cheaper to render than browsing chrome.

---

# 3. Decisions established during the design exploration

The following items should be treated as the current design baseline.

| Area | Decision |
|---|---|
| Home density | Many smaller tiles rather than oversized poster rows |
| Details layout | Keep the current detail-screen information architecture broadly intact |
| Main visual styles | **Cinematic Glass** and **Pure Liquid Dark** |
| Theme colours | Retain existing selectable Nuvio colours |
| Colour behaviour | Colours become primarily accent/tint personalities rather than complete layout skins |
| Adaptive colour | Optional artwork-derived accent mode |
| Navigation | User-selectable independently of visual style |
| Focus | User-selectable independently of visual style |
| Settings | **Minimal Settings** is the recommended/default presentation |
| Alternative settings style | Glass Settings can remain available |
| Player | Preserve existing player functionality, HUD, source, audio, subtitle and utility controls |
| Player philosophy | Video remains the background; controls appear as temporary glass/overlay surfaces |
| UI scale | Add **Automatic UI Scale** as the recommended default |
| Visual quality | Add **Automatic Visual Quality** as the recommended default |
| 1080p / 4K | Same design system; no separate "4K UI" |
| Device fragmentation | Logical canvas/density differences are a first-class concern |

---

# 4. Current fork audit

This specification is intentionally grounded in the current fork rather than assuming a blank-slate app.

## 4.1 Existing global UI scale

The fork already has a device-local scale store:

`app/src/main/java/com/nuvio/tv/data/local/UiScalePreference.kt`

Current behaviour:

- stores `ui_scale_percent`;
- defaults to `100`;
- clamps the manual value to `85..115`;
- is available before the settings graph starts.

At the theme root, `NuvioTheme()` multiplies Compose's density by the configured percentage:

```kotlin
Density(
    density = baseDensity.density * (uiScalePercent / 100f),
    fontScale = baseDensity.fontScale
)
```

This is a useful foundation because it scales the whole Compose design system coherently rather than requiring every component to know about a scale factor.

The weakness is that it is currently **manual only** and its `85–115%` range may be too narrow to compensate for the very different logical canvases exposed by some TV devices.

## 4.2 Existing colour/theme system

The current theme architecture already contains a strong palette abstraction.

Relevant files:

- `ui/theme/Theme.kt`
- `ui/theme/Color.kt`
- `ui/theme/ThemeColors.kt`
- `data/local/ThemeDataStore.kt`

The current built-in palette includes:

- Crimson
- Ocean
- Violet
- Emerald
- Amber
- Rose
- White

and supporter palettes including:

- Gold
- Jade
- Rose Gold
- Arctic Blue
- Graphite

The existing `ThemeColorPalette` currently influences both accent colours **and some background/surface colours**.

For V2, those responsibilities should be separated:

> **Visual Style decides surfaces. Accent decides personality.**

That allows a user to run, for example:

- Cinematic Glass + Violet
- Cinematic Glass + Amber
- Pure Liquid Dark + Ocean
- Pure Liquid Dark + Adaptive Artwork

without creating a separate complete theme definition for every combination.

## 4.3 Existing design-token architecture

`NuvioTheme` already exposes central tokens for:

- colours;
- typography;
- spacing;
- radii;
- shapes;
- sizes;
- strokes;
- elevations;
- effects;
- motion;
- focus;
- layout;
- media;
- components.

This is exactly the architecture V2 should extend.

The goal should be to add **resolved V2 surface/focus/render tokens** and have components consume them, rather than adding screen-specific constants everywhere.

## 4.4 Existing sidebar and blur work

`MainActivity.kt` already contains:

- a modern sidebar;
- collapsed/expanded behaviour;
- graphics-layer animation to avoid relayout;
- a Haze blur implementation;
- `HazeInputScale`;
- focus-transfer handling;
- explicit effort to prevent per-frame recomposition.

The existing collapsed sidebar blur uses a 24 dp blur and a reduced Haze input scale.

This means V2 does **not** need to prove that translucent navigation can be implemented in the current stack. It needs to generalise and quality-tier the mechanism.

## 4.5 Existing focus/performance work

The fork already contains performance-oriented decisions such as:

- `JankStats` in `MainActivity`;
- poster prefetch/pre-decode work;
- smooth/fast navigation preferences;
- focus-restoration logic;
- graphics-layer transformations;
- recomposition isolation in the player.

V2 should preserve these patterns.

Visual upgrades should be attached to existing efficient focus state changes, not replace them with layout-heavy animations.

## 4.6 Existing layout preference store

`LayoutPreferenceDataStore.kt` already contains a large set of display preferences including:

- modern sidebar;
- sidebar blur;
- landscape posters;
- full-screen hero backdrop;
- poster labels;
- focused-poster backdrop expansion;
- focused-poster trailer behaviour;
- poster width and height;
- corner radius;
- card depth;
- episode overlay styles;
- home/detail ratings visibility;
- scrolling/navigation options.

The fork also forces `HomeLayout.MODERN`.

V2 should therefore evolve the current Modern UI rather than reintroducing multiple legacy home-layout engines.

## 4.7 Existing player architecture

`PlayerScreen.kt` already has substantial state and focus handling for:

- primary playback controls;
- source selection;
- episodes;
- audio tracks;
- subtitles;
- subtitle styling and timing;
- playback speed;
- aspect ratio;
- internal player switching;
- stream information;
- playback statistics;
- issue reporting;
- skip intro;
- post-play / next episode;
- seek thumbnails;
- display mode information;
- player clock.

The current Stats/HUD path is also deliberately isolated. Playback statistics are sampled approximately once per second rather than forcing high-frequency whole-screen recomposition.

This is an excellent basis for a visual refactor: the player should primarily be **restyled and reorganised**, not reimplemented.

## 4.8 Important display-resolution observation already in the fork

The player code contains a useful implementation note: on some Amlogic-class devices, the application framebuffer can be 1080p while HDMI is outputting 4K.

The player therefore reads `Display.Mode` for negotiated output information rather than assuming app framebuffer metrics equal physical HDMI mode.

This distinction should also inform V2 scaling:

> **HDMI resolution is not the same thing as UI canvas size.**

## 4.9 Existing Settings architecture

The current Settings screen is already category-based and has both rail and alternate layout logic.

The current categories include Appearance, Layout, Playback, Advanced and others, which gives V2 an appropriate place for the new systems.

One current code detail should be verified before implementation: `ThemeDataStore.settingsUiStyle` appears to read the stored key but then always return `SettingsUiStyle.CLASSIC`. If that branch behaviour is intentional, no issue; if not, it should be corrected before exposing a V2 Settings Presentation selector.

---

# 5. Core V2 architecture

V2 should be implemented as a **new presentation system over the existing Nuvio core**, not as a full application rewrite and not as a superficial skin.

Conceptually:

```text
                  NUVIO CORE
        data / playback / ViewModels / state
                       |
        +--------------+--------------+
        |                             |
  ORIGINAL RENDERER              V2 RENDERER
  Current Modern UI              New UI system
  Current themes                 Cinematic Glass
                                 Pure Liquid Dark
                                 New focus/nav/player
```

The two renderers should share the same domain state, repositories, playback controller, metadata, source logic, tracking and diagnostics.

Where practical, they may also share lower-level content components. Where V2 genuinely changes structure — for example navigation chrome, player chrome, focus rendering or settings scaffolding — it should provide a new presentation composable around the same underlying state/events.

This is best described as:

> **Selective UI rebuild, not application rebuild.**

Nuvio V2 then resolves appearance through three independent layers.

## Layer A — Geometry

Answers:

> How large should the UI be, and how much content should fit?

Controls:

- Automatic UI Scale;
- manual UI Scale;
- responsive layout breakpoints;
- safe-area insets.

## Layer B — Visual language

Answers:

> What does Nuvio look and feel like?

Controls:

- Original vs V2 interface experience;
- visual style;
- navigation style;
- focus style;
- accent colour;
- glass tint;
- settings presentation;
- player control style.

## Layer C — Rendering budget

Answers:

> How much graphical work is this device allowed to spend on appearance while preserving smoothness?

Controls:

- Automatic Visual Quality;
- Performance;
- Enhanced;
- Maximum.

These layers must not be conflated.

A low-powered device can still use Cinematic Glass. It simply renders a cheaper interpretation of Cinematic Glass.

---

# 6. Proposed preference model

A single giant theme enum should be avoided.

A better model is compositional.

```kotlin
enum class InterfaceExperience {
    ORIGINAL_NUVIO,
    NUVIO_V2
}

enum class VisualStyle {
    CINEMATIC_GLASS,
    PURE_LIQUID_DARK
}

enum class NavigationStyle {
    FLOATING_SIDEBAR,
    TOP_NAVIGATION,
    MINIMAL
}

enum class FocusStyle {
    GLASS_LIFT,
    CINEMATIC_FOCUS
}

enum class AccentMode {
    FIXED_THEME,
    ADAPTIVE_ARTWORK
}

enum class GlassTintMode {
    NEUTRAL,
    ACCENT,
    ARTWORK
}

enum class SettingsPresentation {
    MINIMAL,
    GLASS
}

enum class PlayerChromeStyle {
    CONTROL_DECK,
    INVISIBLE
}

enum class UiScaleMode {
    AUTOMATIC,
    MANUAL
}

enum class VisualQualityMode {
    AUTOMATIC,
    PERFORMANCE,
    ENHANCED,
    MAXIMUM
}
```

Suggested preference containers:

```kotlin
@Immutable
data class AppearancePreferences(
    val interfaceExperience: InterfaceExperience,
    val visualStyle: VisualStyle,
    val navigationStyle: NavigationStyle,
    val focusStyle: FocusStyle,
    val accentMode: AccentMode,
    val fixedAccentTheme: AppTheme,
    val glassTintMode: GlassTintMode,
    val settingsPresentation: SettingsPresentation,
    val playerChromeStyle: PlayerChromeStyle
)

@Immutable
data class DeviceUiPreferences(
    val uiScaleMode: UiScaleMode,
    val manualUiScalePercent: Int,
    val autoScaleFineTunePercent: Int,
    val visualQualityMode: VisualQualityMode
)

@Immutable
data class ResolvedAppearance(
    val preferences: AppearancePreferences,
    val resolvedAccent: AccentPalette,
    val resolvedUiScalePercent: Int,
    val resolvedVisualQuality: VisualQualityTier,
    val glassTokens: GlassTokens,
    val focusTokens: FocusTokens,
    val motionTokens: MotionTokens
)
```

---

# 7. Presets versus independent choices

Cinematic Glass and Pure Liquid Dark should be presented as **style presets**, but they must not lock the rest of the interface.

Recommended initial presets:

## Cinematic Glass

- Visual Style: Cinematic Glass
- Focus: Cinematic Focus
- Glass Tint: Artwork
- Navigation: Floating Sidebar
- Player: Control Deck
- Accent: user's existing theme colour or Adaptive

## Pure Liquid Dark

- Visual Style: Pure Liquid Dark
- Focus: Glass Lift
- Glass Tint: Neutral or Accent
- Navigation: Top Navigation or Floating Sidebar
- Player: Invisible
- Accent: user's existing theme colour

However:

**Changing Visual Style should not silently overwrite customised navigation, focus or player settings.**

Instead, Settings can expose:

> **Apply recommended configuration for this style**

This avoids the frustrating behaviour where changing one visual choice unexpectedly resets several others.

---

# 7A. Original Nuvio compatibility mode

The current fork's **Modern UI** should remain available as a first-class option.

User-facing setting:

> **Interface Experience**  
> Nuvio V2  
> Original Nuvio

"Original Nuvio" means the current Modern interface and its existing theme behaviour. It does **not** imply restoring retired Classic/Grid home engines that the fork has already removed.

## Requirements

- Original mode must remain visually isolated from V2-specific surface tokens.
- Changes to V2 glass, focus, backdrop or quality systems must not subtly alter Original mode.
- Original mode should continue to use the existing current-theme and current-layout logic unless a bug fix is required.
- Switching between Original and V2 should preserve navigation/business state where practical.
- The interface-experience choice should be **device-local by default**, so one device can use Original while another uses V2 without forcing the same renderer everywhere.
- Hardware-specific settings such as UI scale and visual quality remain device-local regardless of interface experience.

Example:

```text
Ugoos AM9 Pro           -> V2 / Maximum
Fire TV Stick 4K Max    -> V2 / Automatic
Older bedroom device    -> Original
```

This compatibility mode provides a safe fallback for users who prefer the current interface and reduces migration risk during V2 rollout.

---

# 8. Visual Style A — Cinematic Glass

## Intent

Atmospheric, content-led and cinematic.

Artwork contributes to the mood of the screen. Glass reacts to the imagery without obscuring it.

## Surface behaviour

- deep neutral background;
- hero/backdrop artwork allowed to bleed into the UI;
- stronger ambient gradients;
- selected surfaces may inherit subtle artwork tint;
- glass is visually present but not bright;
- specular edges are soft rather than neon;
- backdrop and foreground are separated through luminance and blur rather than thick borders.

## Recommended defaults

- Cinematic Focus;
- Artwork glass tint;
- Floating Sidebar;
- Control Deck player;
- Adaptive Accent as an optional high-end preference.

## Performance fallback

Performance tier should retain:

- translucency;
- gradient depth;
- edge highlight;
- static ambient colour.

It may remove:

- real-time background blur;
- layered bloom;
- multi-pass glass effects.

The mode should still look recognisably Cinematic Glass.

---

# 9. Visual Style B — Pure Liquid Dark

## Intent

Cleaner, darker, more precise and UI-led.

The interface has depth, but artwork is less likely to flood the entire background with colour.

## Surface behaviour

- deeper near-black base;
- more neutral glass;
- stronger separation between content and controls;
- accent colour is slightly more prominent in focus and active states;
- less full-screen artwork influence;
- sharper edge highlights;
- restrained shadows.

## Recommended defaults

- Glass Lift;
- Neutral or Accent glass tint;
- Top Navigation or Floating Sidebar;
- Invisible player;
- fixed colour theme.

---

# 10. Accent colour system

The existing Nuvio colour themes should be retained.

V2 changes their job.

## Current behaviour to phase out

A palette can currently influence:

- secondary colour;
- focus ring;
- focus background;
- background;
- elevated background;
- card background.

## V2 behaviour

The base dark surfaces should primarily come from `VisualStyle`.

The selected colour should influence:

- focus outline;
- focus bloom;
- active navigation indicator;
- selected toggles;
- progress;
- selected chips;
- active playback control;
- subtle glass tint;
- occasional light spill.

The accent should **not** recolour the entire interface.

## Recommended intensity

Typical glass tint contribution:

- neutral glass: 0%;
- accent tint: approximately 5–12%;
- focused accent highlight: approximately 40–100%, depending on element;
- full background wash: avoid.

These are design-token targets, not hardcoded alpha values for every component.

---

# 11. Adaptive artwork accent

Add:

> **Accent → Adaptive to artwork**

The system extracts a stable accent from the currently relevant hero/title artwork.

## Requirements

The selected colour must be:

- sufficiently saturated to be visible;
- clamped away from near-black;
- clamped away from near-white;
- contrast-safe against the current surface;
- stable while a title remains selected;
- cached by content ID;
- transitioned gently rather than changing instantly.

## Behaviour

The artwork accent should influence UI identity, not recolour the artwork.

Example:

- warm desert artwork may resolve to amber;
- a cold sci-fi image may resolve to cyan/blue;
- a green-dominant nature scene may resolve to emerald.

## Performance

Do not continuously sample bitmaps.

Extract once when artwork is loaded, cache the result, and reuse it.

Adaptive accent should be available on every visual-quality tier because the expensive part is infrequent colour extraction, not ongoing rendering.

---

# 12. Glass system

"Liquid glass" should be simulated intelligently rather than implemented as expensive optical refraction everywhere.

The desired appearance can be built from:

1. translucent dark fill;
2. controlled background blur where affordable;
3. subtle tint;
4. one-pixel-ish bright edge;
5. darker opposite edge;
6. gentle highlight gradient;
7. optional bloom outside focused surfaces;
8. depth through shadow and separation.

## Core component

Create a reusable component such as:

```kotlin
@Composable
fun NuvioGlassSurface(
    role: GlassRole,
    focused: Boolean,
    modifier: Modifier = Modifier,
    content: @Composable BoxScope.() -> Unit
)
```

Possible roles:

```kotlin
enum class GlassRole {
    NAVIGATION,
    CONTROL,
    PANEL,
    MODAL,
    HUD,
    CARD_FOCUS,
    TOOLTIP
}
```

Every role resolves its actual visuals from the current quality tier.

## Do not

- scatter arbitrary `hazeEffect()` calls across screens;
- run multiple full-screen blur captures;
- blur video frames underneath the player controls;
- animate blur radius continuously while scrolling;
- use true displacement/refraction effects simply because Maximum mode exists.

---

# 13. Quality-tiered glass

## Performance

- no general live backdrop blur;
- dark translucent fill;
- gradient edge;
- single specular highlight;
- simple shadow;
- optional static tint.

Approximate visual result: premium smoked glass.

## Enhanced

- selective Haze blur;
- reduced-resolution blur input;
- subtle inner highlight;
- soft accent bloom on focused elements;
- richer artwork ambience.

## Maximum

- higher-quality selective blur;
- richer multi-layer surface gradient;
- stronger but still restrained bloom;
- additional edge depth;
- optional slow ambient backdrop movement;
- more refined transitions.

Maximum should **not** mean "apply every effect everywhere."

---

# 14. Focus system

Two focus styles remain independently selectable.

## 14.1 Glass Lift

Designed for clarity and speed.

Recommended starting behaviour:

- scale: ~1.03–1.04;
- tiny upward translation;
- crisp glass/accent edge;
- compact shadow;
- no global dimming;
- no layout reflow.

Implementation should use `graphicsLayer`.

This is ideal for dense poster rows.

## 14.2 Cinematic Focus

Designed for atmosphere.

Recommended starting behaviour:

- scale: ~1.045–1.06;
- elevation/translation;
- surrounding row subtly recedes;
- selected artwork contributes ambient colour;
- light spill appears behind the focused tile;
- optional metadata reveal.

Surrounding dimming must remain subtle enough that users can still see where they are going next.

## Focus timing

Focus feedback should begin immediately.

Suggested motion tokens:

- micro response: 80–120 ms;
- focus settle: 140–180 ms;
- panel transition: 180–260 ms;
- large backdrop transition: 250–400 ms.

A remote user should never be waiting for the visual system before the next D-pad press can be processed.

---

# 15. Navigation styles

Navigation must be independent of visual style.

## 15.1 Floating Sidebar

This should evolve the current Modern sidebar rather than start from scratch.

States:

- collapsed pill/icon state;
- expanded glass rail;
- selected route;
- focused route.

The existing implementation already avoids relayout by animating with graphics transforms. Preserve that principle.

### Focus model

- `Back` or left edge entry can reveal/expand navigation;
- Up/Down moves within the rail;
- Right enters content;
- returning to the sidebar should restore the last route focus.

### Quality behaviour

Performance:

- solid/translucent surface;
- no live blur.

Enhanced:

- selective Haze blur.

Maximum:

- richer blur/tint/edge treatment.

## 15.2 Top Navigation

A slim, horizontally focused top bar.

Best suited to users who want maximum poster width.

Focus model:

- Up from the top content row enters navigation;
- Left/Right moves between routes;
- Down returns to the last focused content item.

The top bar should not become a giant web-style menu.

## 15.3 Minimal Navigation

Optional third style.

Navigation remains mostly absent until requested, exposing a compact route dock/pill.

This should be implemented after Sidebar and Top Navigation are stable, not as an initial launch blocker.

---

# 16. Home / Browse

## Design intent

Dense, cinematic, efficient.

## Geometry

Use the current ~126 × 189 dp poster size as a useful reference, but make it a design token rather than a fixed assumption.

After automatic scale resolution, the layout should target many visible cards per row.

The UI should not compensate for a cramped device canvas by merely clipping posters.

## Hero

The hero may use:

- full-screen backdrop;
- title/logo;
- compact metadata;
- description;
- primary actions.

Cinematic Glass can allow more artwork ambience.

Pure Liquid Dark should keep stronger neutral separation.

## Poster labels

Support current user preference.

When labels are disabled, focus metadata can be surfaced in the hero/detail region rather than permanently taking vertical space under every poster.

## Focus

Card scale must never alter LazyRow measurement.

Always transform rather than resize layout bounds.

## Scrolling

Preserve the fork's current anti-jank work.

Avoid:

- blur per poster;
- live shadow recomputation on every frame;
- backdrop colour extraction on every focus tick;
- expensive image effects during rapid horizontal navigation.

Artwork ambience should debounce briefly so extremely fast focus movement does not trigger unnecessary work.

---

# 16A. Profile landing screen

The profile selector is part of the primary Nuvio experience and must receive the same V2 design treatment as Home, Details, Search, Settings and Player.

It should not feel like a legacy setup screen that happens before the "real" app.

## 16A.1 Role

The profile landing screen is the first interactive surface many users see after launch.

Its responsibilities are:

- show available profiles clearly;
- make the current focus target obvious at TV viewing distance;
- enter the selected profile quickly;
- expose profile management without cluttering the main choice;
- preserve fast startup and 60 Hz-quality focus movement;
- support both Original Nuvio and Nuvio V2 presentation modes.

## 16A.2 Presentation hierarchy

Recommended V2 layout:

```text
                    Nuvio

               Who's watching?

        [ Avatar ]  [ Avatar ]  [ Avatar ]
          Paul        Guest        Kids

                    + Add Profile

                 Manage Profiles
```

The exact number of visible profiles should respond to the logical canvas and UI Scale resolver.

For typical TV use, profiles should remain large enough to recognise from a distance while avoiding oversized cards that make three profiles fill the entire screen.

## 16A.3 V2 visual treatment

### Cinematic Glass

- dark cinematic backdrop;
- optional slow ambient gradient or artwork-derived background;
- avatars sit on subtle translucent glass plinths or rings;
- focused profile receives Cinematic Focus treatment;
- soft accent/artwork light spill may appear behind the selected avatar;
- labels remain crisp and high-contrast.

### Pure Liquid Dark

- near-black neutral background;
- cleaner avatar treatment;
- focused profile uses Glass Lift;
- accent colour appears in the focus ring, active label and subtle halo;
- little or no ambient artwork colour.

## 16A.4 Original Nuvio

When `InterfaceExperience.ORIGINAL_NUVIO` is active, the current profile-selection presentation should remain available.

The V2 profile screen should therefore be implemented over the shared profile/session state rather than replacing profile-management business logic.

---

# 16B. Pre-profile appearance resolution

The profile landing screen appears before a user profile has been selected, which creates an important state-resolution problem.

V2 should not depend on profile-scoped appearance data that is unavailable before profile selection.

## Recommended resolution order

```text
1. Device-local Interface Experience
2. Device-local last-used V2 appearance snapshot, if available
3. Device-local/default V2 appearance
4. Profile-scoped appearance after profile selection
```

This permits the first screen to render consistently without waiting for the profile context.

## Recommended behaviour

- **Original vs V2** remains device-local and is known before profile selection.
- UI Scale and Visual Quality remain device-local and therefore apply immediately.
- The profile landing screen may use the last resolved V2 visual style/accent used on that device.
- After a profile is selected, profile-scoped appearance may take over if the product later chooses to store appearance per profile.
- The transition between pre-profile and profile appearance should crossfade rather than flash.

If appearance is not profile-scoped, the device-level V2 appearance simply continues unchanged.

---

# 16C. Profile focus behaviour

The profile selector should use the same focus system as the rest of V2.

## Glass Lift

Recommended:

- ~1.04 scale;
- slight upward lift;
- accent/glass ring;
- label brightens;
- no surrounding dim.

## Cinematic Focus

Recommended:

- ~1.06 scale;
- stronger depth;
- soft halo or ambient bloom;
- non-selected profiles subtly recede;
- no layout reflow.

## Focus rules

- initial focus should be deterministic;
- preferably restore the last-used profile when appropriate;
- Left/Right moves between profiles;
- Down reaches Add/Manage actions;
- Up returns from actions to the nearest profile;
- Back follows current product behaviour and should never strand focus;
- rapid key-repeat must not queue large avatar animations.

---

# 16D. Profile startup performance

The profile screen is on the cold-start path and must remain inexpensive.

Hard rules:

1. Do not block first interaction on remote avatar downloads.
2. Cache avatar assets locally.
3. Render placeholders immediately when an avatar is not yet available.
4. Do not perform expensive live blur before the screen is interactive.
5. Maximum-quality ambient effects may appear after the first stable frame.
6. Avoid image extraction or animated background work during startup.
7. Profile-selection focus should be available as soon as the profile list is loaded.

A useful staged render is:

```text
Stage 1:
  background + cached profile cards + focus

Stage 2:
  higher-quality avatar image / ambient effect

Stage 3:
  optional Maximum-tier polish
```

This keeps startup feeling fast even on lower-powered hardware.

---

# 16E. Avatar system

Avatars should become a first-class V2 component rather than a raw image inside a generic card.

Create a reusable semantic component such as:

```kotlin
@Composable
fun NuvioAvatar(
    avatar: AvatarModel,
    size: Dp,
    focused: Boolean,
    selected: Boolean = false,
    modifier: Modifier = Modifier
)
```

The same avatar renderer can be reused in:

- profile landing;
- profile editor;
- account/settings surfaces;
- user switcher;
- compact identity indicators.

## Avatar shapes

Recommended default:

- circular or softly rounded-square artwork;
- do not use a different shape per profile;
- shape should be controlled by a design token.

A rounded-square treatment may fit Nuvio's broader poster/card language better, while circular avatars create stronger separation from content posters.

Both are viable, but V2 should pick one consistent default.

## Focus treatment

Focused avatars may receive:

- glass/accent ring;
- subtle depth;
- soft shadow;
- light spill in Cinematic Focus;
- label emphasis.

Do not apply heavy blur directly to the avatar image.

---

# 16F. Avatar library and custom avatars

The profile editor should support an avatar library with clear categories.

Possible structure:

```text
Choose Avatar

Recent
Nuvio
Abstract
Characters / Media
Colours
Custom
```

The exact content library is a product/content decision, but the UI should support multiple groups without changing architecture.

## Custom avatar support

If custom images are supported:

- crop to the selected avatar shape;
- generate appropriate cached sizes;
- avoid decoding full-resolution source files on every app start;
- provide a fallback when the file becomes unavailable;
- respect privacy by storing only what the app needs.

## Generated fallback

When no avatar image exists:

- use initials or a simple abstract glyph;
- derive a stable background from the profile identifier;
- ensure foreground/background contrast;
- never show a broken-image icon.

---

# 16G. Avatar image sizing and caching

Avatar images are small UI assets and should not be treated like hero artwork.

Recommended:

- request/decode close to actual rendered size;
- retain a modestly higher-resolution source for 4K TVs;
- keep a memory/disk cache;
- prefetch all visible profile avatars before the landing screen transition where practical;
- avoid loading multi-megapixel images for 100–200 dp surfaces.

The avatar cache should remain independent of poster/backdrop caches if that improves predictability.

---

# 16H. Profile management screen

`Manage Profiles` should use the same utility-first philosophy as Minimal Settings.

Recommended actions:

- edit name;
- change avatar;
- profile-specific preferences if supported;
- delete profile;
- create profile;
- optional profile lock/PIN if implemented elsewhere.

The edit surface can be a V2 glass panel, but destructive actions should remain explicit and readable rather than highly stylised.

Profile editing should not require entering the profile itself if the current account model allows management from the landing screen.

---

# 16I. Profile transition into Home

Selecting a profile should feel immediate and polished.

Recommended transition:

1. focused avatar confirms with a short scale/opacity response;
2. profile screen begins a short fade/zoom transition;
3. Home content can begin loading behind the transition;
4. the first Home focus target becomes available as soon as navigation is ready.

Avoid a long cinematic animation that delays entry.

Suggested total transition duration:

~200–350 ms, excluding actual data loading.

If Home data is not ready, retain a stable dark/glass transitional surface rather than flashing a blank screen.

---

# 16IA. Profile entry ident / loading visual

After profile selection, Nuvio V2 should support a **short branded entry ident** that plays while Home loads in the background.

This is intentionally different from a traditional utility-style loading screen.

The goal is to make the app entry feel polished and cinematic without imposing an unnecessary wait.

## 16IA.1 Purpose

The entry ident should:

- provide a refined transition from profile selection into Home;
- mask short loading/setup work that occurs after profile selection;
- let poster rows, hero content and initial focus state prepare in the background;
- make the app entry point feel premium;
- remain fast and understated rather than theatrical;
- preserve the ability to skip quickly into Home when Home is already ready.

## 16IA.2 Design principle

This should behave more like a **short motion ident** than a status dashboard.

Avoid:

- verbose loading text;
- fake progress bars;
- technical loading states;
- random sci-fi scenery unrelated to the product;
- long animations that force the user to wait.

Prefer:

- near-black/dark background;
- minimal motion;
- subtle light sweep or liquid-light reveal;
- the Nuvio wordmark as the primary visual subject;
- optional accent-colour influence from the current V2 appearance;
- a clean fade into populated Home.

## 16IA.3 Flow sequence

Recommended sequence:

```text
1. User selects profile
2. Avatar/profile confirms selection
3. Profile screen fades/dissolves out
4. Short Nuvio entry ident plays
5. Home appears already populated
```

A useful timing model:

```text
0–150 ms     profile confirmation response
150–400 ms   fade from profile screen
400–1800 ms  Nuvio ident
1800+ ms     transition to Home as soon as ready
```

These timings are targets, not rigid delays.

## 16IA.4 Minimum time, not fixed delay

The entry ident should be governed by:

- a **minimum display time**; and
- a **readiness point** for Home.

Do **not** implement it as "always wait 3 seconds".

Recommended behaviour:

- if Home is ready early, allow the ident to complete a natural minimum cycle and transition promptly;
- if Home needs longer, the ident may briefly hold or loop subtly;
- if loading becomes unusually long, gracefully fall back to a light-weight loading state rather than looping a polished ident forever.

This keeps fast devices fast while still masking moderate loading latency on slower devices.

## 16IA.5 Background work while ident plays

While the ident is on screen, Nuvio may perform:

- profile/session initialisation;
- content-row requests;
- first-viewport poster requests;
- hero/backdrop resolution;
- watchlist/resume state preparation;
- first Home focus-target preparation;
- any lightweight appearance/theme resolution needed after profile selection.

The ident should buy time for useful work, not merely delay the user.

## 16IA.6 Visual treatment by style

### Cinematic Glass

- softer, more atmospheric light reveal;
- subtle refractive or glass-like sweep;
- optional faint artwork/accent influence;
- elegant dissolve into Home.

### Pure Liquid Dark

- tighter, cleaner and more restrained;
- less ambient softness;
- stronger monochrome base;
- accent colour appears as a precise line/sweep rather than an atmospheric wash.

Both styles should remain recognisably the same Nuvio ident rather than completely different animations.

## 16IA.7 Accent colour integration

The ident may subtly adopt the active accent colour.

Examples:

- a blue accent may tint the light sweep blue;
- amber may give the reveal a warm glow;
- neutral/white remains crisp and minimal.

The accent should remain tasteful and restrained. It should not recolour the full screen heavily.

## 16IA.8 Visual quality tiers

The ident should be compatible with the Visual Quality system.

| Feature | Performance | Enhanced | Maximum |
|---|---:|---:|---:|
| Wordmark reveal | Yes | Yes | Yes |
| Light sweep | Simple | Refined | Rich |
| Background ambience | Minimal | Moderate | Rich |
| Refractive/glass feel | Simulated | Subtle | Stronger |
| Motion complexity | Low | Medium | Medium-high |
| Home preload in background | Yes | Yes | Yes |

Even in Performance mode, the ident must still look intentional and premium.

## 16IA.9 Implementation options

Two implementation approaches are acceptable:

### Option A — Local video ident

Pros:
- easiest to art-direct precisely;
- visually consistent across devices;
- hardware video decode is usually efficient;
- simplest route to a premium-looking result.

Cons:
- larger bundled asset footprint;
- less inherently adaptive to theme/accent;
- separate assets may be needed for stylistic variations if desired.

### Option B — Real-time Compose / graphics ident

Pros:
- smaller asset footprint;
- can inherit accent colour and appearance state exactly;
- flexible for future adjustments.

Cons:
- more engineering effort;
- more GPU/render-thread sensitivity on weaker devices;
- requires stricter discipline to avoid startup jank.

### Recommended starting point

Use a **short local ident asset** with optional UI-level compositing/tint where needed.

This gives predictable visuals and low implementation risk while the rest of Home is loading.

## 16IA.10 Resolution and asset strategy

The ident does not need to be a heavy 4K asset.

Recommended:

- start with a high-quality 1080p abstract/dark ident asset;
- let 4K televisions upscale it cleanly;
- keep duration short;
- keep bitrate and decode cost reasonable;
- ensure the asset is dark/minimal enough that compression artifacts are not distracting.

A small, elegant 1080p ident is preferable to a huge 4K asset that increases APK size and startup cost.

## 16IA.11 Player / refresh-rate considerations

This screen occurs before video playback and therefore does not interfere with content frame-rate matching.

However, it should still respect the app's browsing/display environment and should not introduce unnecessary display-mode switching of its own.

The ident should behave as part of the launch/browsing UI pipeline, not as playback content.

## 16IA.12 Startup performance rules

Hard rules:

1. Do not delay initial Home entry unnecessarily on fast devices.
2. Do not block Home readiness on ident completion once the minimum cycle has finished.
3. Do not make the ident longer merely to appear "premium".
4. Do not decode an excessively heavy asset on low-end devices.
5. Do not couple the ident to network success.
6. Do not let the ident hide a broken or stalled Home-load path indefinitely.

If the preload fails or stalls, transition to a clear fallback loading state or directly into a recoverable Home state.

## 16IA.13 Optional user preference

A user-facing setting may be offered:

> **Profile Entry Animation**  
> Automatic / On / Off

Recommended behaviour:

- **Automatic**: enabled for V2, but short and non-intrusive;
- **On**: always use the ident flow;
- **Off**: transition directly from profile selection to Home.

This preference should be optional. It should not block the architecture if omitted from the first implementation.

## 16IA.14 Testing

Test:

- cold start to profile screen;
- profile selection on fast device;
- profile selection on slow device;
- Home ready before minimum ident time;
- Home not ready by ident completion;
- loading failure/fallback;
- Original Nuvio mode;
- Cinematic Glass;
- Pure Liquid Dark;
- all visual-quality tiers;
- 1080p output;
- 4K output;
- repeated app launches to ensure the ident does not become annoying or feel too long.

The ident flow should be included in release-mode Macrobenchmarks because it sits on the startup path.

---

# 16J. Profile accessibility and identity

Requirements:

- profile name must be readable without relying on avatar recognition;
- focus state must be visible without relying only on accent colour;
- avatar and label should expose an accessible combined description;
- duplicate profile names should still be distinguishable visually;
- Add Profile and Manage Profiles must be reachable without awkward directional jumps.

---

# 16K. Profile-screen visual quality tiers

| Feature | Performance | Enhanced | Maximum |
|---|---:|---:|---:|
| Avatar focus scale | Yes | Yes | Yes |
| Accent ring | Yes | Yes | Yes |
| Glass plinth | Simulated | Selective blur | Rich glass |
| Ambient backdrop | Static | Moderate | Rich |
| Bloom/light spill | Minimal | Soft | Rich |
| Background motion | Off | Off/minimal | Optional |
| Startup interaction | Immediate | Immediate | Immediate |

The screen must remain attractive in Performance mode because it is visible before the rest of the app.

---

# 16L. Profile-screen diagnostics / test matrix

Test:

- 1 profile;
- 2–4 profiles;
- 5+ profiles;
- long profile names;
- missing/corrupt avatar;
- custom avatar;
- 1080p output;
- 4K output;
- Automatic UI Scale;
- minimum/maximum manual scale;
- Original Nuvio;
- Cinematic Glass;
- Pure Liquid Dark;
- all visual-quality tiers;
- cold start on a 2 GB device;
- rapid profile switching;
- profile creation/deletion and focus restoration.

The profile landing screen should be included in the same release-mode Macrobenchmark and focus-regression suite as Home, Search, Settings and Player.

---

# 17. Search

Search should inherit the utility-first side of the design system.

## Default presentation

- dark background;
- compact translucent search field;
- clear focus ring;
- dense results;
- limited glass surfaces.

## Behaviour

- Search input remains obvious from 10-foot distance;
- results update without shifting the focus target unexpectedly;
- moving into results retains the current query;
- Back returns to the query before leaving the screen where practical.

## Cinematic variation

When a result is focused and the user pauses on it, a subdued backdrop may appear.

Do not change the full background for every rapid D-pad movement.

---

# 18. Movie / TV details

The current layout is already considered good and should be preserved conceptually.

V2 should therefore prioritise **material treatment**, not information-architecture redesign.

## Recommended hierarchy

- backdrop;
- title/logo;
- year / runtime / certification / quality metadata;
- synopsis;
- Play/Resume;
- secondary actions;
- seasons/episodes;
- trailers/cast/related content where currently supported.

## Cinematic Glass

- backdrop is more atmospheric;
- title information can sit directly over controlled scrims;
- action buttons can use translucent glass.

## Pure Liquid Dark

- stronger neutral metadata area;
- less artwork colour intrusion;
- sharper panel boundaries.

## Episodes

Episode tiles should use the same focus system as posters but should not expand enough to cause row clipping.

The current blurred-unwatched and episode overlay preferences should continue to function.

---

# 19. Player design

The player is a separate performance domain.

## Principle

> The movie is the background. The UI visits temporarily.

No permanent glass container should frame the video.

## 19.1 Player chrome zones

### Top zone

Reserved for temporary/technical overlays:

- Full HUD;
- Compact HUD;
- display mode badge;
- clock if enabled.

### Bottom-left

- title or show/episode identity;
- compact metadata where useful.

### Bottom centre

- seek/progress;
- elapsed / remaining time;
- play/pause;
- seek controls.

### Bottom utility dock

Secondary controls:

- Info / stream information;
- Stats HUD;
- Audio;
- Subtitles;
- Sources;
- Episodes where relevant;
- Start Over;
- More.

Less common actions can remain in More:

- speed;
- aspect ratio;
- switch internal player;
- open external player;
- issue report;
- subtitle advanced controls.

This prevents a row of twelve equally weighted icons.

---

# 20. Player Chrome styles

The two player concepts can remain an independent preference.

## 20.1 Control Deck

A shallow glass deck rises from the bottom.

Characteristics:

- stronger grouping;
- easier discoverability;
- title, scrubber and actions feel like one system;
- best match for Cinematic Glass.

## 20.2 Invisible Player

Controls mostly float directly over the image.

Characteristics:

- smallest visual footprint;
- individual glass pills appear on focus;
- secondary dock remains compact;
- best match for Pure Liquid Dark.

## Performance rule

Neither style should blur the live video surface.

Use:

- gradient scrims;
- dark translucent surfaces;
- edge highlights;
- precomputed/static tint.

This protects decoder/rendering headroom.

---

# 21. Progressive disclosure in the player

The current player exposes substantial functionality. V2 should keep it but organise it.

Suggested interaction:

1. First press reveals essential playback chrome.
2. Play/Pause is the default focus.
3. Up reaches title/options context or the utility dock.
4. Left/Right across the utility dock moves between Info, Audio, Subtitles, Source, etc.
5. Activating a utility opens a side sheet/panel.
6. Back dismisses the deepest overlay first, then the controls, then exits playback.

This matches the current architecture well because `PlayerScreen` already has explicit transient-overlay state and layered Back handling.

---

# 22. Player HUD / Stats for Nerds

The HUD is a core differentiating capability in this fork and should be treated as first-class UI.

## Modes

### Compact

Show only the values a power user wants to glance at:

- resolution;
- HDR / Dolby Vision;
- video codec;
- audio codec / passthrough state;
- measured throughput;
- buffer health;
- dropped frames;
- current refresh/frame-rate status.

### Full

Retain the complete diagnostic set, including:

- stream/source;
- video codec/profile;
- measured bitrate;
- decoder;
- HDR/DV state;
- audio codec;
- audio bitrate;
- passthrough path;
- AVR/sink status;
- network throughput;
- connection state;
- buffer;
- frame lead;
- jitter/underrun information;
- memory;
- CPU / SoC temperature where available;
- display mode.

## Visual treatment

HUD glass should be more opaque than decorative navigation glass.

Readability wins.

Recommended:

- near-black panel;
- light blur or no blur;
- 85–95% effective darkness;
- thin neutral edge;
- section headings;
- tabular alignment;
- colour only for meaningful status.

Examples:

- green: healthy/verified;
- amber: marginal;
- red: fault;
- selected accent: active UI focus, not technical status.

## Update behaviour

Retain the current low-frequency stats update strategy.

Do not animate numeric changes.

Do not rebuild the entire player UI every second.

---

# 23. Player side sheets

Audio, Subtitles and Source selection should visually converge on one reusable surface.

Create a `PlayerSideSheet` or `NuvioPlayerPanel`.

## Common behaviour

- appears from the right;
- player remains visible behind it;
- a dark scrim separates it from video;
- first relevant row receives focus;
- focus is restored to the originating button on dismiss;
- no expensive video blur.

## Audio

Allow:

- selected track;
- language;
- codec;
- channel layout;
- delay/amplification controls where currently supported.

## Subtitles

Allow:

- Off;
- embedded tracks;
- addon subtitles;
- style;
- delay;
- sync-by-line;
- advanced subtitle options.

## Sources

Retain the richer source list because source switching is a power feature.

Source panels may be wider than Audio/Subtitles.

---

# 24. Settings design

**Minimal Settings should be the default.**

Settings is a utility surface. It should be extremely clear, fast and predictable.

## Layout

Recommended default:

- left category rail;
- right options pane;
- compact section titles;
- short explanatory subtitles;
- no permanent large preview canvas.

For Appearance specifically, a small live preview tile can be shown where useful.

## Glass Settings

Optional alternate presentation.

It may use:

- more translucent panels;
- larger preview region;
- slightly richer transitions.

It should not have a different information hierarchy.

---

# 25. Proposed Appearance settings hierarchy

Avoid exposing every implementation parameter.

Recommended user-facing structure:

## Experience

**Interface Experience**
- Nuvio V2
- Original Nuvio

When Original Nuvio is selected, V2-specific appearance controls should either be hidden or shown as inactive with a concise explanation.

## Style

**Interface Style**
- Cinematic Glass
- Pure Liquid Dark

**Navigation**
- Floating Sidebar
- Top Navigation
- Minimal

**Focus**
- Glass Lift
- Cinematic Focus

**Player Controls**
- Control Deck
- Invisible

## Colour

**Accent**
- White
- Ocean
- Violet
- Crimson
- Emerald
- Amber
- Rose
- existing supporter themes
- Adaptive to artwork

**Glass Tint**
- Neutral
- Accent
- Artwork

## Display

**UI Scale**
- Automatic — Recommended
- Manual

When Automatic:
- Fine adjustment: e.g. `-10 ... +10%`

When Manual:
- Compact / Standard / Large presets;
- optional fine slider.

**Visual Quality**
- Automatic — Recommended
- Performance
- Enhanced
- Maximum

## Settings

**Settings Presentation**
- Minimal
- Glass

## Actions

- Apply recommended configuration for current style
- Reset Appearance to defaults

---

# 26. Automatic UI Scale

This is one of the most important V2 systems.

## 26.1 Problem

The same physical TV can show very different apparent UI sizes depending on the Android TV device.

The Ugoos AM9 Pro can expose a roomier canvas while a Fire TV Stick 4K Max connected to the same TV can make the same UI feel zoomed-in.

Physical TV resolution alone does not solve this.

A device may:

- output 3840 × 2160 HDMI;
- render the application into a 1920 × 1080 framebuffer;
- expose a different density;
- therefore expose a different number of logical dp to Compose.

## 26.2 Current limitation

The existing manual scale range is `85–115%`.

If, hypothetically:

- one device exposes an effective 1280 × 720 dp canvas;
- another exposes about 960 × 540 dp;

then a 960 dp-wide device would need roughly:

```text
960 / 1280 = 0.75
```

or a 75% density multiplier to make its effective content density resemble the 1280 dp-wide device.

The current 85% lower clamp could therefore be insufficient.

This example must be validated against actual diagnostics from the target devices, but it illustrates why Automatic Scale should be based on **logical canvas**, not product name or HDMI resolution.

---

# 27. Nuvio reference canvas

Android's TV design guidance commonly uses a 960 × 540 mdpi design canvas.

Nuvio, however, deliberately prefers a denser interface with more visible posters.

Therefore V2 should define its own **Nuvio Reference Canvas**.

Recommended process:

1. Run a diagnostics build on the Ugoos AM9 Pro where the current density feels correct.
2. Capture:
   - app window pixel bounds;
   - system density;
   - `screenWidthDp`;
   - `screenHeightDp`;
   - current scale percent;
   - active `Display.Mode`;
   - framebuffer size.
3. Treat that effective canvas as the initial "100% Nuvio density".
4. Compare the Fire TV Stick 4K Max on the same TV.
5. Tune the reference until both devices produce similar perceived content density.

A likely starting hypothesis is a reference nearer **1280 × 720 effective dp** than 960 × 540, but this should be measured rather than hardcoded blindly.

---

# 28. Automatic scale resolver

Inputs:

```kotlin
data class UiCanvasSnapshot(
    val windowWidthPx: Int,
    val windowHeightPx: Int,
    val baseDensity: Float,
    val systemWidthDp: Int,
    val systemHeightDp: Int,
    val activeModeWidthPx: Int?,
    val activeModeHeightPx: Int?,
    val refreshRateHz: Float?,
    val aspectRatio: Float
)
```

Derived base logical canvas:

```kotlin
logicalWidthDp = windowWidthPx / baseDensity
logicalHeightDp = windowHeightPx / baseDensity
```

For a reference canvas `referenceWidthDp × referenceHeightDp`:

```kotlin
widthFactor = logicalWidthDp / referenceWidthDp
heightFactor = logicalHeightDp / referenceHeightDp
rawScale = min(widthFactor, heightFactor)
```

Because V2 applies scale by multiplying `LocalDensity`, the resulting effective canvas becomes:

```text
effectiveWidthDp = logicalWidthDp / rawScale
```

which converges toward the reference canvas.

## Suggested clamps

Automatic mode should initially support a wider range than the existing manual preference, for example:

- lower bound: ~75–80%;
- upper bound: ~110–115%.

Exact bounds should be validated through device testing.

A broken vendor density configuration should never be allowed to produce a 50% or 160% UI.

---

# 29. Automatic UI Scale must be stable

Do not adapt UI scale continuously.

Resolve it:

- at app start;
- when display configuration changes;
- when Android density changes;
- when the user changes the manual fine adjustment.

Do not change scale:

- while browsing;
- during playback;
- during a frame-rate switch;
- because a temporary performance problem occurred.

UI scale is geometry, not a performance governor.

---

# 30. Manual fine adjustment

Automatic will never know viewing distance, eyesight or personal preference.

Therefore Automatic mode should allow:

> **Automatic scale fine adjustment**

Example:

- Auto result: 82%
- Fine adjustment: -5%
- Effective result: 78%

The user sees a simple control such as:

> Smaller ← `────●────` → Larger

Advanced diagnostics can reveal the actual percentage.

---

# 31. 1080p versus 4K

Nuvio should not maintain a separate 4K layout.

Android TV guidance explicitly recommends density-independent layout.

V2 should therefore:

- use dp/sp for geometry;
- use high-resolution artwork assets;
- use actual app-window/logical-canvas data for UI scaling;
- use `Display.Mode` primarily for output diagnostics and playback decisions.

A 4K TV should not automatically display twice as many UI elements merely because the HDMI mode is 3840 × 2160.

Similarly, a 1080p TV should not receive a larger-looking interface simply because it has fewer physical pixels.

---

# 32. Safe area / overscan

Android TV design guidance recommends keeping important interactive content within a roughly 5% safe region.

For the classic 960 × 540 reference, the guidance is around:

- ~48 dp horizontally;
- ~24–28 dp vertically.

Nuvio should keep:

- focusable navigation;
- titles;
- key controls;
- HUD text;

inside the safe region.

Background artwork and ambient gradients may extend to the physical screen edges.

Where the current Nuvio spacing system already satisfies these margins, avoid adding redundant padding.

---

# 32A. 60 Hz / 60 fps browsing performance contract

When the television/UI is operating at approximately 60 Hz, V2 should target **60-fps-quality interaction**.

At 60 Hz the app has approximately:

```text
1000 / 60 = 16.67 ms
```

to prepare each frame.

This should become an explicit engineering target for browsing, settings, search, navigation and transient overlays.

## Benchmark philosophy

"Feels smooth" is not sufficient by itself.

V2 should measure:

- frame overrun / slow-frame distribution;
- P50 / P90 / P95 / P99 frame timing where available;
- `JankStats` output;
- key-path Macrobenchmark results;
- GPU/render-thread pressure when investigating problem screens.

Recommended benchmark journeys:

```text
Cold-start to profile landing and move rapidly across profiles
Select a profile and transition into Home
Hold Right through a dense poster row
Rapid Up/Down between catalog rows
Open/close the sidebar repeatedly
Enter and leave Details
Move rapidly through Search results
Expand/collapse Settings categories
Show/dismiss player controls
Open/close Audio, Subtitles and Source panels
```

## Performance acceptance principle

The automatic quality system should prefer:

> stable frame time first, richer effects second.

If Maximum-quality effects consistently exceed the frame budget on a device, Automatic mode should step down to Enhanced or Performance rather than retaining visually richer but visibly janky rendering.

## Important playback caveat

The UI should **not force the television to remain at 60 Hz during video playback**.

The fork already supports frame-rate matching for film/TV content. A 23.976/24 fps title may switch the display to 23.976, 24, 48 or another matched mode depending on device/display capability.

Player overlays must render appropriately inside the active playback mode. Opening subtitles or the HUD must not force the TV back to 60 Hz and undermine judder-free playback.

Therefore:

```text
Browsing / utility UI:
  target 60-fps-quality behaviour when display is ~60 Hz

Video playback:
  respect matched display mode chosen for content
```

---

# 33. Automatic Visual Quality

Automatic UI Scale solves geometry.

Automatic Visual Quality solves rendering cost.

They must remain completely independent.

## User-facing options

- Automatic — Recommended
- Performance
- Enhanced
- Maximum

---

# 34. Quality-tier feature matrix

| Feature | Performance | Enhanced | Maximum |
|---|---:|---:|---:|
| Live glass blur | Mostly off | Selective | Selective, higher quality |
| Haze input resolution | Very low/off | Reduced | Higher |
| Glass edge | Simple | Layered | Rich layered |
| Focus bloom | Minimal | Soft | Rich |
| Artwork ambience | Static | Moderate | Rich |
| Backdrop blur | Avoid | Selective/static | Selective |
| Background motion | Off | Minimal | Optional |
| Shadows | Basic | Soft | Layered |
| Focus scaling | Yes | Yes | Yes |
| D-pad animations | Yes | Yes | Yes |
| Player video blur | Never | Never | Never |
| Layout/functionality | Same | Same | Same |

---

# 35. Automatic Visual Quality initial capability assessment

Do not determine quality solely from RAM.

Inputs should include:

- `ActivityManager.isLowRamDevice`;
- total system RAM;
- app heap class;
- Android API level;
- hardware acceleration availability;
- actual root rendering resolution;
- GLES/Vulkan capability where useful;
- device model/SoC only as supplemental data;
- historical jank result from this device/app version.

A reasonable initial classifier could be conservative:

- obvious memory-tight / low-RAM device → Performance;
- mainstream modern TV box → Enhanced;
- demonstrably strong device with headroom → Maximum.

The target is not to reward spec-sheet numbers. The target is to preserve smooth focus navigation.

---

# 36. Adaptive rendering governor

The fork already uses `JankStats`, which makes a conservative automatic governor practical.

## Behaviour

Automatic mode should be designed to observe navigation performance and step the visual tier down if the current tier consistently causes jank.

Device specifications should choose only the **initial** tier. Once sufficient measured behaviour exists, real frame-time performance should outrank spec-sheet assumptions.

However it must not oscillate.

Suggested rules:

- observe only during browsing/settings, not video playback;
- ignore app startup warm-up;
- require a meaningful sample window;
- downgrade only after persistent missed-frame/jank evidence;
- do not downgrade during an active scroll animation;
- apply the lower tier on the next stable screen transition;
- require a much longer healthy period before upgrading;
- preferably upgrade on a future session rather than suddenly making the current UI richer.

## Hysteresis

Example concept:

```text
Enhanced -> Performance:
  sustained jank threshold exceeded for >= 10–20 s of active UI work

Performance -> Enhanced:
  healthy history over multiple sessions or explicit reassessment
```

Exact thresholds should be determined with benchmark data.

---

# 37. Visual quality persistence

Store two values:

```kotlin
VisualQualityMode     // what the user selected
ResolvedQualityTier   // what Automatic currently chose
```

The resolved automatic tier should be device-local.

It may be invalidated when:

- app version changes significantly;
- Android version changes;
- display/framebuffer configuration changes substantially;
- user runs a manual reassessment;
- a device performance profile changes.

---

# 38. Playback rendering policy

Player overlays should use their own restricted render policy even when the global UI is Maximum.

Recommended:

```text
Browsing Maximum != Player Maximum
```

During playback:

- no full-screen Haze capture of the decoded video;
- no continuously animated blur;
- no large animated shadows;
- no expensive artwork extraction;
- no dynamic background material simulation.

The player should look premium through:

- scrims;
- translucent near-black panels;
- precise typography;
- accent highlights;
- subtle borders;
- fast focus transitions.

This keeps video/audio resources prioritised.

---

# 39. Theme implementation changes

## 39.1 Decouple palette from surfaces

Current:

```text
AppTheme -> accent + backgrounds + card colours
```

V2:

```text
VisualStyle -> base surfaces
AppTheme / Adaptive -> accent
GlassTintMode -> amount/source of tint
VisualQuality -> effect implementation
```

Proposed resolved palette:

```kotlin
@Immutable
data class NuvioV2Palette(
    val background: Color,
    val surfaceLow: Color,
    val surfaceMid: Color,
    val surfaceHigh: Color,
    val glassBase: Color,
    val glassEdge: Color,
    val textPrimary: Color,
    val textSecondary: Color,
    val accent: Color,
    val accentSoft: Color,
    val focusRing: Color,
    val focusGlow: Color
)
```

---

# 40. New CompositionLocals

Rather than passing many parameters through every screen:

```kotlin
val LocalResolvedAppearance = staticCompositionLocalOf<ResolvedAppearance> { ... }
val LocalVisualQuality = staticCompositionLocalOf { VisualQualityTier.PERFORMANCE }
val LocalUiScaleDecision = staticCompositionLocalOf { UiScaleDecision.Default }
val LocalGlassTokens = staticCompositionLocalOf { GlassTokens.Performance }
```

`NuvioTheme()` can resolve these once and expose stable immutable objects.

---

# 41. Device-local versus profile-synced preferences

This distinction is essential.

## Device-local

These describe hardware/display behaviour or device-specific presentation preference and should **not** sync between boxes by default:

- Interface Experience (Original vs V2);
- UI Scale mode;
- resolved automatic scale;
- scale fine adjustment, arguably device-local;
- Visual Quality mode;
- resolved automatic visual tier;
- device quality history.

A Fire TV Stick and a Ugoos should not inherit each other's scale.

## Profile preference / syncable

These are aesthetic/user choices:

- Cinematic Glass / Pure Liquid Dark;
- navigation style;
- focus style;
- accent;
- adaptive colour;
- glass tint;
- settings presentation;
- player chrome style.

Whether all of these are remotely synced is a product decision, but the data model should not put hardware-specific values in the same synced blob.

---

# 42. Recommended DataStore structure

Keep the existing `UiScalePreference` concept but extend it.

Possible device store:

```text
device_ui_preferences
  interface_experience
  ui_scale_mode
  ui_scale_manual_percent
  ui_scale_auto_fine_adjust_percent
  visual_quality_mode
  resolved_auto_quality
  resolved_auto_scale
  quality_profile_version
```

Possible profile Appearance store:

```text
appearance_v2
  visual_style
  navigation_style
  focus_style
  accent_mode
  fixed_accent_theme
  glass_tint_mode
  settings_presentation
  player_chrome_style
```

Existing settings can be migrated into these values.

---

# 42A. Shared-state / dual-renderer implementation

The Original and V2 experiences should share the same core state model rather than maintaining two applications in one codebase.

Recommended shape:

```kotlin
@Composable
fun NuvioRoot(
    state: AppUiState,
    events: AppUiEvents,
    experience: InterfaceExperience
) {
    when (experience) {
        InterfaceExperience.ORIGINAL_NUVIO ->
            OriginalNuvioRenderer(state, events)

        InterfaceExperience.NUVIO_V2 ->
            NuvioV2Renderer(state, events)
    }
}
```

The renderer boundary should sit **above** repositories, ViewModels, playback logic and source/tracking logic.

Avoid duplicating:

- player controllers;
- playback state;
- source resolution;
- metadata repositories;
- watch/progress tracking;
- diagnostics sampling;
- account/session logic;
- navigation destination models.

The renderer may differ in:

- scaffold;
- navigation chrome;
- focus treatment;
- surfaces;
- player chrome;
- settings scaffolding;
- screen composition.

This architecture makes the Original UI a reliable compatibility path without requiring two independent feature implementations.

---

# 43. Migration strategy

V2 should preserve existing users' choices wherever sensible.

The safest rollout is to treat V2 as an opt-in or staged-default presentation during early releases while keeping Original Nuvio available immediately from Settings.

Example mappings:

```text
existing AppTheme             -> fixedAccentTheme
existing sidebar enabled      -> Floating Sidebar
existing sidebar blur enabled -> retain as initial quality hint, then superseded by V2 quality
existing settings style       -> Settings Presentation
existing UI scale             -> Manual mode with existing percentage
existing card depth           -> seed focus/surface behaviour
```

For existing installs:

**Do not silently switch manual UI Scale to Automatic on first V2 launch.**

Recommended migration:

- new installs: Automatic;
- existing installs with exactly 100% and no meaningful customisation: optionally migrate to Automatic;
- existing installs with a non-default scale: preserve Manual.

This avoids breaking someone's carefully tuned TV layout.

---

# 44. Settings defaults

Recommended V2 new-install defaults:

```text
Interface Experience:  Nuvio V2
Interface Style:       Cinematic Glass
Navigation:            Floating Sidebar
Focus:                 Cinematic Focus
Accent:                Existing/default Nuvio colour
Glass Tint:            Artwork
Player Controls:       Control Deck
Settings Presentation: Minimal

UI Scale:              Automatic
Visual Quality:        Automatic
```

These defaults can be revisited after real-device testing.

---

# 45. Performance constraints

## Hard rules

1. Focus movement must never wait for background artwork processing.
2. No layout reflow for focused-card scaling.
3. No per-card live blur.
4. No full-screen blur over active video.
5. No high-frequency HUD-wide recomposition.
6. No continuous automatic UI scaling.
7. No rendering-tier oscillation.
8. Expensive ambience work must debounce during fast navigation.
9. Static fallbacks must still look intentional.
10. Performance mode is a supported visual target, not an emergency downgrade.

---

# 46. Suggested component architecture

## Core

```text
ui/v2/
  appearance/
    AppearancePreferences.kt
    AppearanceResolver.kt
    ArtworkAccentResolver.kt

  quality/
    DeviceCapabilitySnapshot.kt
    VisualQualityResolver.kt
    VisualQualityGovernor.kt

  scale/
    UiCanvasSnapshot.kt
    UiScaleResolver.kt
    UiScaleDecision.kt

  components/
    NuvioGlassSurface.kt
    NuvioFocusSurface.kt
    NuvioPosterCard.kt
    NuvioActionPill.kt
    NuvioSideSheet.kt
    NuvioNavigationSurface.kt

  player/
    PlayerChrome.kt
    PlayerUtilityDock.kt
    PlayerHud.kt
    PlayerSideSheet.kt
```

This directory layout is illustrative. It should be reconciled with the fork's existing package organisation rather than imposed mechanically.

---

# 47. Central glass renderer

A major implementation rule:

> Screens request a semantic glass role. They do not decide how blur is rendered.

Example:

```kotlin
val glass = NuvioTheme.glass.forRole(GlassRole.NAVIGATION)
```

The renderer then decides:

```text
Performance:
  translucent fill + edge

Enhanced:
  Haze inputScale 0.50–0.66 + moderate blur

Maximum:
  Haze inputScale 0.66–0.80 + richer edge/bloom
```

This makes later performance tuning possible in one place.

---

# 48. Motion system

Add named motion roles rather than screen-specific magic numbers.

```kotlin
data class NuvioV2MotionTokens(
    val focusResponseMs: Int,
    val focusSettleMs: Int,
    val panelInMs: Int,
    val panelOutMs: Int,
    val ambientCrossfadeMs: Int,
    val backdropDebounceMs: Int
)
```

Suggested initial ranges:

- focus response: 90–120 ms;
- focus settle: 140–180 ms;
- small overlay: 150–200 ms;
- side sheet: 200–240 ms;
- hero/background crossfade: 280–400 ms.

Performance tier may reduce complexity, but not necessarily make every animation faster.

---

# 49. Focus-state implementation detail

Use state transforms such as:

```kotlin
Modifier.graphicsLayer {
    scaleX = focusScale
    scaleY = focusScale
    translationY = focusLiftPx
    alpha = resolvedAlpha
}
```

Avoid changing card width/height when focused.

For Cinematic Focus, surrounding dimming should preferably happen at the row/container level using a cheap derived state rather than recomposing every card with unique effects.

---

# 50. Backdrop behaviour

Artwork ambience should be controlled through a small state machine.

```text
FAST NAVIGATION
  Keep previous backdrop
  Do not launch expensive transition

FOCUS SETTLED
  Load/resolve next backdrop
  Resolve artwork accent
  Crossfade ambient layer

DETAIL OPEN
  Allow full cinematic treatment
```

Suggested debounce before changing a home backdrop:

~150–250 ms.

This prevents the background from flashing through ten images when a user holds Right.

---

# 51. Image strategy

For high-density TV displays:

- use source artwork large enough to remain sharp;
- request sizes appropriate to the rendered region;
- avoid loading 4K poster images for 126 dp cards;
- keep hero/backdrop and poster requests separate;
- continue the fork's prefetch/pre-decode approach.

Artwork-adaptive colour should reuse already loaded images or cached metadata where possible.

---

# 52. Accessibility and legibility

Glass must never reduce legibility.

Requirements:

- focus must be identifiable by more than colour alone;
- text contrast should be validated against the darkest and brightest backdrops;
- active accent colours require contrast-adjusted foreground colours;
- labels must not rely on thin typography;
- selected control state and focused control state should remain distinct;
- user font scale must remain respected within the app's existing TV-specific limits.

The existing root currently caps extreme font scale at 1.15. V2 should preserve that only if it remains an intentional TV usability choice and should be tested with accessibility settings.

---

# 53. Remote navigation acceptance rules

Every V2 screen should pass these checks:

- repeated D-pad movement never loses focus;
- Back dismisses the deepest transient layer first;
- returning from a child screen restores meaningful focus;
- hidden navigation can always be rediscovered;
- opening a dialog does not allow focus to remain behind it;
- closing a dialog restores focus to the launching control;
- long/rapid key repeat does not queue visual animations faster than focus state can settle.

The current fork already contains careful focus restoration. V2 should extend that architecture rather than replace it with implicit geometric focus everywhere.

---

# 54. Performance instrumentation

Use existing `JankStats` and add V2-specific state markers.

In addition, create release-mode Macrobenchmarks for the critical D-pad journeys. Debug builds are not sufficient for judging Compose frame performance because compiler/runtime overhead differs from optimized release builds. Baseline Profile coverage should be regenerated after major V2 navigation and player changes.

Examples:

```text
screen=home
visualStyle=cinematic_glass
quality=enhanced
nav=floating_sidebar
focus=cinematic
autoScale=78
backdropTransition=true
```

This makes device comparison substantially more useful.

For local diagnostics, expose:

- current UI scale;
- resolved visual tier;
- app-window pixels;
- logical dp;
- base density;
- active HDMI display mode;
- recent jank percentage;
- memory class.

No remote telemetry is required for this to be useful.

---

# 55. Appearance diagnostics screen

Under Advanced / Debug, add:

```text
UI Scale
Mode: Automatic
Resolved: 78%
Fine adjustment: 0%
Effective logical canvas: 1276 × 718 dp

Display
App window: 1920 × 1080 px
System density: 2.00
Reported screen: 960 × 540 dp
HDMI mode: 3840 × 2160 @ 59.94 Hz

Visual Quality
Mode: Automatic
Resolved: Enhanced
Reason: stable frame performance / supported blur path

UI Performance
Recent jank: ...
```

This screen will be extremely valuable for comparing the Ugoos AM9 Pro and Fire TV Stick 4K Max.

---

# 56. UI scale and poster density

Automatic scale should normalise overall UI geometry.

It should **not** be used as a secret poster-size preference.

Keep poster dimensions as design tokens.

If later desired, a separate advanced preference could control content density, but V2 should first solve device inconsistency before adding another user-facing density axis.

---

# 57. Quality-tier testing matrix

At minimum, test these combinations:

## Hardware classes

- 2 GB Amlogic S905X5M-class
- 4 GB Amlogic S905X4-class
- Ugoos AM9 Pro
- Fire TV Stick 4K Max
- representative Google TV device
- Nvidia Shield if available/community tested

## Display modes

- 1920 × 1080 output
- 3840 × 2160 output
- 23.976 playback transition
- 24.000 playback transition
- 50/60 Hz UI operation

## UI modes

- Performance
- Enhanced
- Maximum
- Automatic

## Scale

- Auto
- minimum supported manual
- 100%
- maximum supported manual

## Themes

- Cinematic Glass
- Pure Liquid Dark
- bright accent such as Amber
- dark/cool accent such as Violet/Ocean
- Adaptive artwork

---

# 58. Screen-level acceptance targets

## Profile landing

- cached profiles appear quickly on cold start;
- focus is available immediately after profile state loads;
- avatar loading never blocks D-pad interaction;
- selecting a profile enters Home through the configured entry flow without an unnecessary long delay;
- the entry ident masks moderate loading work but does not artificially hold fast devices;
- missing avatars have a stable fallback;
- Original and V2 profile renderers use the same underlying profile/session state;
- Automatic UI Scale keeps profile density sensible across differing Android TV canvases.

## Home

- rapid horizontal navigation remains responsive;
- no focus clipping;
- no backdrop flashing under held D-pad;
- poster decode/prefetch remains effective;
- selected row never jumps because of scale.

## Details

- title text remains legible over all backdrops;
- actions have unambiguous focus;
- episode rows remain dense;
- backdrop loading does not delay input.

## Settings

- categories and options restore focus;
- Appearance changes preview quickly;
- changing visual quality does not lose focus;
- changing UI scale gives a controlled confirmation flow.

## Player

- controls do not interfere with decoder/render timing;
- HUD remains readable;
- 1 Hz stats updates do not cause player-wide recomposition;
- opening Source/Audio/Subtitles is immediate;
- closing a panel restores launching-button focus.

---

# 59. UI scale change UX

Changing global density can cause a large recomposition and geometry shift.

For manual changes:

1. User chooses new value.
2. Preview/update.
3. Keep focus on a stable logical setting row.
4. Optionally show:
   - Keep
   - Revert
5. Auto-revert after a timeout only if testing shows users can accidentally make the UI unusable.

For TV, avoid a tiny phone-style slider as the only control. D-pad-friendly discrete steps are preferable.

Example:

```text
75  80  85  90  95  100  105  110  115
```

Automatic fine adjustment can use 2–5% steps.

---

# 60. Visual quality change UX

Unlike UI Scale, changing rendering tier should not move geometry.

A user can safely preview Performance / Enhanced / Maximum live.

Show a short description:

**Performance**
> Reduced blur and lighting. Best for lower-powered devices.

**Enhanced**
> Rich glass and focus effects with balanced performance.

**Maximum**
> Highest visual fidelity. Recommended only for devices with graphics headroom.

**Automatic**
> Nuvio selects and adjusts the best level for this device.

---

# 60A. Second-pass implementation review checklist

Before any screen is declared V2-ready, review it for:

- memory allocation churn during rapid D-pad movement;
- recomposition scope;
- accidental layout remeasurement on focus;
- GPU overdraw;
- offscreen rendering cost;
- Haze/blur capture area;
- shadow cost;
- image decode/request sizing;
- focus restoration;
- animation interruption under key-repeat;
- Original-mode isolation;
- Performance-tier fallback appearance;
- 60 Hz frame-time behaviour in release builds.

This is deliberately broader than blur optimisation. On 2–4 GB Android TV devices, allocation pressure, image work, overdraw and unnecessary recomposition can be just as damaging as the blur effect itself.

---

# 61. Recommended implementation phases

## Phase 0 — Instrument before redesign

Before changing the main visuals:

- add UI canvas diagnostics;
- capture Ugoos and Fire TV scale data;
- expose resolved dp canvas;
- benchmark current navigation;
- record baseline jank;
- verify current Haze cost.

Deliverable:

> Known baseline, not guesses.

## Phase 1 — Appearance state architecture and renderer boundary

Implement:

- `InterfaceExperience` and Original/V2 renderer selection;
- V2 preference models;
- device-local versus profile stores;
- migration;
- resolved appearance object;
- no major visual change yet.

## Phase 2 — Automatic UI Scale

Implement:

- `UiCanvasSnapshot`;
- `UiScaleResolver`;
- Auto/manual modes;
- fine adjustment;
- diagnostics;
- widened safe scale range.

Test on same TV with Ugoos + Fire Stick before proceeding.

## Phase 3 — Visual Quality system and frame-budget baseline

Implement:

- quality enum;
- initial capability resolver;
- centralized glass quality tokens;
- manual tier selector;
- diagnostic output;
- release-mode 60 Hz Macrobenchmark journeys and P95/P99 baseline.

Do not add adaptive jank downgrading yet.

## Phase 4 — Core surfaces and focus

Build reusable:

- GlassSurface;
- FocusSurface;
- action pill;
- panel;
- sheet.

Implement:

- Glass Lift;
- Cinematic Focus;
- accent tint.

## Phase 4A — Profile landing, avatar foundation and entry ident

Implement:

- V2 profile landing scaffold;
- `NuvioAvatar`;
- cached/fallback avatar rendering;
- Glass Lift and Cinematic Focus avatar states;
- Original/V2 shared profile-state renderer boundary;
- avatar picker/profile-management visual treatment;
- short profile-entry ident / loading visual;
- minimum-time + readiness-point transition logic;
- cold-start performance benchmark.

## Phase 5 — Home and navigation

Apply V2 to:

- home;
- hero;
- poster cards;
- floating sidebar.

Then add Top Navigation.

Minimal Navigation can remain later.

## Phase 6 — Details and Search

Restyle without large functional rewrites.

## Phase 7 — Player

Create V2 player chrome around existing player state/events.

Implement:

- Control Deck;
- Invisible;
- utility dock;
- common side sheet;
- Compact / Full HUD visuals.

## Phase 8 — Settings

Make Minimal Settings the default V2 presentation.

Add Appearance controls and previews.

Retain optional Glass Settings.

## Phase 9 — Automatic quality governor

Only after real rendering benchmarks exist:

- integrate JankStats feedback;
- add conservative downgrade logic;
- hysteresis;
- persistence.

## Phase 10 — polish and compatibility

- RTL;
- localisation;
- accessibility;
- 1080p;
- 4K;
- overscan;
- unusual aspect/window configurations;
- memory pressure;
- process recreation.

---

# 62. Recommended first engineering milestone

Do **not** start by implementing the most elaborate glass.

The first milestone should be:

> **Nuvio V2 Foundation Build**

It should contain:

1. Automatic UI Scale.
2. Automatic/Manual Visual Quality state.
3. Resolved appearance architecture.
4. Existing colour palettes decoupled into accents.
5. Glass Lift focus.
6. Cinematic Focus.
7. One reusable V2 GlassSurface.
8. Floating Sidebar rendered through that surface.
9. Appearance diagnostics.
10. No player redesign yet.

Why:

This tests the hardest architectural assumptions — device scale, rendering tiers and token resolution — before touching every screen.

---

# 63. Suggested Phase 0 experiment: Ugoos versus Fire TV

Because both devices can be connected to the same physical TV, they form an unusually good calibration pair.

Add a temporary debug panel and capture:

```text
Device
Build.MODEL
SDK
RAM

Window
width px
height px

Android density
density
densityDpi
screenWidthDp
screenHeightDp

Display mode
physicalWidth
physicalHeight
refreshRate

Nuvio
manual scale %
effective width dp
effective height dp
```

Take one screenshot from each device with the same Nuvio row focused.

Then calculate what automatic scale would need to make the visible card count and spacing converge.

This is preferable to adding hardcoded:

```kotlin
if (Build.MODEL.contains("Fire TV")) ...
```

Device-specific overrides should be a last resort.

---

# 64. Potential automatic-scale implementation sketch

```kotlin
class UiScaleResolver(
    private val referenceWidthDp: Float,
    private val referenceHeightDp: Float
) {
    fun resolve(
        snapshot: UiCanvasSnapshot,
        fineAdjustPercent: Int
    ): UiScaleDecision {
        val logicalW = snapshot.windowWidthPx / snapshot.baseDensity
        val logicalH = snapshot.windowHeightPx / snapshot.baseDensity

        val widthFactor = logicalW / referenceWidthDp
        val heightFactor = logicalH / referenceHeightDp

        val base = minOf(widthFactor, heightFactor)
            .coerceIn(0.75f, 1.15f)

        val adjusted = base * (1f + fineAdjustPercent / 100f)

        return UiScaleDecision(
            percent = (adjusted * 100f).roundToInt().coerceIn(72, 120),
            logicalWidthDp = logicalW,
            logicalHeightDp = logicalH,
            reason = UiScaleReason.LOGICAL_CANVAS_NORMALISATION
        )
    }
}
```

The numbers above are starting points only.

The critical design is the **relationship**, not the final constants.

---

# 65. Potential quality resolver sketch

```kotlin
class VisualQualityResolver {
    fun initialTier(c: DeviceCapabilitySnapshot): VisualQualityTier {
        if (c.isLowRamDevice) return PERFORMANCE

        var score = 0

        if (c.totalRamGb >= 4f) score += 2
        else if (c.totalRamGb >= 3f) score += 1

        if (c.apiLevel >= 31) score += 1
        if (c.renderWidthPx * c.renderHeightPx <= 1920 * 1080) score += 1
        if (c.previousUiPerformanceHealthy) score += 2

        return when {
            score >= 5 -> MAXIMUM
            score >= 2 -> ENHANCED
            else -> PERFORMANCE
        }
    }
}
```

This is intentionally illustrative.

Before shipping, replace simplistic score assumptions with measurements from the target hardware matrix.

---

# 66. Why render resolution matters to blur

A blur surface scales roughly with the number of pixels that must be sampled and composited.

Therefore:

- HDMI output resolution is not sufficient;
- actual UI render target resolution matters;
- reduced `HazeInputScale` is useful;
- clipping blur to small surfaces is much cheaper than capturing a full-screen layer.

The current sidebar already demonstrates the right principle by using a reduced Haze input scale.

V2 should formalise that into quality tokens.

---

# 67. Settings presentation option

Keeping both Minimal and Glass Settings is feasible because they can share the same state/content composables.

Architecture:

```text
Settings data/content
        |
        +--> MinimalSettingsScaffold
        |
        +--> GlassSettingsScaffold
```

Do not duplicate the actual setting rows or business logic.

The presentation mode should only change:

- rail/background surface treatment;
- preview prominence;
- spacing;
- transitions.

---

# 68. Navigation option architecture

Likewise:

```text
Navigation model/routes/focus memory
        |
        +--> FloatingSidebarNavigation
        +--> TopNavigation
        +--> MinimalNavigation
```

The route graph remains shared.

Changing navigation style must not rebuild the rest of the app's business state.

---

# 69. Player option architecture

The same principle applies:

```text
PlayerUiState + PlayerEvents
        |
        +--> ControlDeckChrome
        +--> InvisibleChrome
```

Audio/Subtitles/Sources/HUD should be shared components.

This is especially important because the current PlayerScreen already has significant playback logic that should remain untouched.

---

# 70. What should NOT be rewritten

Unless profiling proves otherwise, V2 should not initially rewrite:

- stream resolution;
- playback engine;
- player runtime controller;
- stats sampling backend;
- audio/subtitle business logic;
- source selection business logic;
- metadata repositories;
- tracking;
- home content retrieval.

The UI project should remain a presentation-layer migration.

---

# 71. Testing visual consistency

Create snapshot/reference scenes with identical content for:

- Profile landing;
- Avatar picker/profile editor;
- Home;
- Details;
- Search;
- Settings;
- Player;
- HUD;
- Source panel.

Capture each scene under:

- Cinematic Glass;
- Pure Liquid Dark;
- Performance;
- Enhanced;
- Maximum;
- several accents.

The purpose is not pixel-perfect golden testing across every GPU. It is to catch:

- missing tint;
- wrong focus colour;
- opacity regressions;
- layout breakage;
- theme combinations that become unreadable.

---

# 72. Design token starting values

These are intentionally provisional.

## Glass radii

```text
small control:     10–14 dp
pill:              full/50%
panel:             18–24 dp
large modal:       22–28 dp
```

## Borders

```text
resting glass edge:    ~0.5–1 dp visual weight
focused edge:          ~1–2 dp
```

## Focus scale

```text
Glass Lift:       1.03–1.04
Cinematic Focus:  1.045–1.06
```

## Blur

```text
Performance:      0
Enhanced:         ~14–22 dp selective
Maximum:          ~20–32 dp selective
```

## Tint alpha

```text
Neutral glass:    0
Accent glass:     ~0.05–0.12
Artwork glass:    ~0.05–0.14
```

All values should be interpreted through the resolved quality/style tokens.

---

# 73. Recommended source-code touch points

Known current files that are likely central to the V2 work:

```text
app/src/main/java/com/nuvio/tv/MainActivity.kt

app/src/main/java/com/nuvio/tv/data/local/
  ThemeDataStore.kt
  LayoutPreferenceDataStore.kt
  UiScalePreference.kt

app/src/main/java/com/nuvio/tv/domain/model/
  AppTheme.kt
  [new V2 appearance models]

app/src/main/java/com/nuvio/tv/ui/theme/
  Theme.kt
  ThemeColors.kt
  Color.kt
  [existing token files]
  [new Glass/Quality token files]

app/src/main/java/com/nuvio/tv/ui/screens/home/
  [existing Modern home components]

app/src/main/java/com/nuvio/tv/ui/screens/settings/
  SettingsScreen.kt
  [Appearance/Layout settings content]

app/src/main/java/com/nuvio/tv/ui/screens/player/
  PlayerScreen.kt
  [existing player overlay components]
```

Before implementation, run a repo-wide symbol search for all direct `hazeEffect`, raw glass colour, fixed focus-scale and UI-scale usages so V2 does not leave conflicting styles behind.

---

# 74. Source references reviewed

Repository:

`https://github.com/ysosrs123/NuvioTV-Fork`

Relevant current source snapshots reviewed:

- `https://raw.githubusercontent.com/ysosrs123/NuvioTV-Fork/nuvio-test/app/src/main/java/com/nuvio/tv/MainActivity.kt`
- `https://raw.githubusercontent.com/ysosrs123/NuvioTV-Fork/nuvio-test/app/src/main/java/com/nuvio/tv/data/local/UiScalePreference.kt`
- `https://raw.githubusercontent.com/ysosrs123/NuvioTV-Fork/nuvio-test/app/src/main/java/com/nuvio/tv/data/local/ThemeDataStore.kt`
- `https://raw.githubusercontent.com/ysosrs123/NuvioTV-Fork/nuvio-test/app/src/main/java/com/nuvio/tv/data/local/LayoutPreferenceDataStore.kt`
- `https://raw.githubusercontent.com/ysosrs123/NuvioTV-Fork/nuvio-test/app/src/main/java/com/nuvio/tv/ui/theme/Theme.kt`
- `https://raw.githubusercontent.com/ysosrs123/NuvioTV-Fork/nuvio-test/app/src/main/java/com/nuvio/tv/ui/theme/Color.kt`
- `https://raw.githubusercontent.com/ysosrs123/NuvioTV-Fork/nuvio-test/app/src/main/java/com/nuvio/tv/ui/theme/ThemeColors.kt`
- `https://raw.githubusercontent.com/ysosrs123/NuvioTV-Fork/nuvio-test/app/src/main/java/com/nuvio/tv/ui/screens/settings/SettingsScreen.kt`
- `https://raw.githubusercontent.com/ysosrs123/NuvioTV-Fork/nuvio-test/app/src/main/java/com/nuvio/tv/ui/screens/player/PlayerScreen.kt`

Android TV design references:

- `https://developer.android.com/design/ui/tv/guides/styles/layouts`
- `https://developer.android.com/training/tv/playback/compose/layouts`
- `https://developer.android.com/training/tv/playback/compose`

---

# 75. Remaining decisions that do NOT block the architecture

These can be refined after V2 Foundation exists:

- exact blur radii;
- exact focus scale values;
- exact panel corner radius;
- whether Minimal Navigation ships in V2.0 or later;
- whether Adaptive Artwork is default;
- whether Glass Settings ships immediately;
- exact Cinematic Glass backdrop strength;
- exact automatic-quality jank thresholds;
- exact Nuvio Reference Canvas after Ugoos/Fire diagnostics;
- whether Compact HUD is the first tap and Full HUD is a secondary action.

None of these require changing the three-layer architecture.

---

# 76. Recommended immediate next step

Before implementing the visual redesign itself:

## Build a small "V2 Device UI Diagnostics" branch

Add the diagnostic values from Section 55 and test:

1. Ugoos AM9 Pro on the user's main TV.
2. Fire TV Stick 4K Max on the same TV.
3. Current 100% scale.
4. Current minimum 85% scale.
5. Experimental 75–80% scale on the Fire TV if safe.

This will tell us whether the apparent zoom difference is primarily:

- Android density;
- framebuffer size;
- window metrics;
- or a combination.

From that data, the Automatic UI Scale algorithm can be finalised before the new UI is built around the wrong reference geometry.

---

# 77. Final V2 product definition

Nuvio V2 should feel like:

> A dark, cinematic TV interface with dense content browsing, selective liquid-glass depth, excellent remote focus feedback and power-user playback tooling — while automatically adapting both its geometry and its graphical expense to the Android TV device underneath it.

The key technical idea is not "add blur."

It is also not "replace the current app."

Nuvio should retain a stable Original Modern renderer while V2 becomes the new, performance-aware presentation system over the same core.

It is:

```text
                         NUVIO CORE
                            |
                +-----------+-----------+
                |                       |
          ORIGINAL MODERN             NUVIO V2
                                        |
                    +-------------------+-------------------+
                    |                   |                   |
                 GEOMETRY           APPEARANCE          RENDER BUDGET
                    |                   |                   |
                Auto UI              Style              Auto Quality
                 Scale              Navigation          Performance
                Manual              Focus               Enhanced
                                    Accent              Maximum
                                    Glass
                                    Player
```

That separation is what allows Nuvio to remain:

- visually ambitious on high-end hardware;
- smooth on constrained hardware;
- consistent between 1080p and 4K displays;
- consistent between devices that expose different Android canvases;
- configurable without becoming fragmented;
- capable of 60-Hz-quality browsing where the display/device permit it;
- able to preserve the current Original Modern UI for users who prefer it;
- visually coherent from the very first profile-selection screen, including avatars, profile management and a short premium entry ident;
- and faithful to the fork's core priority: **excellent playback first**.
