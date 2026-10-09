# Foldable two-page spread — development plan

Branch: `feature/fold-dual-page` (from upstream `main` @ `566ba0d`, v1.6.0 / versionCode 26)

Goal: when a Galaxy Z Fold8 is unfolded and the window is horizontal (wider than tall), readers show two pages side by side like an open book. Folded (cover screen), portrait, or any phone keeps today's single-page behavior.

## 1. Research summary

### Foldable SDKs

| Option | Status | Use here |
|---|---|---|
| **Jetpack WindowManager** `androidx.window:window` | Stable 1.5.1. Gives `WindowInfoTracker.windowLayoutInfo()` → `FoldingFeature` (state FLAT/HALF_OPENED, orientation, `isSeparating`, `occlusionType`, bounds) and `WindowMetricsCalculator` | **Yes** — read posture and hinge bounds; place the spread gutter on the hinge |
| `androidx.window:window-testing` | Stable, same version | **Yes** — `TestWindowLayoutInfo` / `FoldingFeature(...)` fakes for instrumented tests |
| Compose `material3-adaptive` (`currentWindowAdaptiveInfo`) | Stable 1.3.0, but needs Kotlin 2.0+ | **No** — the project is on Kotlin 1.9 / Compose compiler 1.5.14. Upgrading that is out of scope |
| Samsung-specific fold SDK | None current. Samsung's Flex-mode docs and codelab point to Jetpack WindowManager | **No** SDK. Use Samsung **Remote Test Lab** (has Z Fold8, SM-F971) and the Galaxy emulator skins for testing |
| Readium kotlin-toolkit (current 3.0.2, latest 3.4.0) | Reflowable EPUB: `EpubPreferences.columnCount` (ONE/TWO/AUTO). Fixed-layout EPUB: `spread` (AUTO is treated as *single page* — a FIXME in Readium, so set `ALWAYS`/`NEVER` explicitly). PDFium: **no spreads**. Image/comic navigator: **no spreads** | EPUB stays on 3.0.2 (everything needed is there). Comics need a custom spread view |

Sources: developer.android.com/jetpack/androidx/releases/window, developer.android.com/develop/ui/compose/layouts/adaptive/foldables/make-your-app-fold-aware, developer.samsung.com/codelab/galaxy-z/flex-mode.html, developer.samsung.com/remote-test-lab, readium.org/kotlin-toolkit/latest/guides/navigator/preferences/.

### Device facts that shape the design

- **Z Fold8** (the new "wide" model): inner display 7.6", 4:3, ~2448×1848. **Unfolded with the hinge vertical, it is landscape by default.** Each half is about 1224×1848, roughly a portrait page, so it is a natural book spread with the gutter on the hinge.
- **Z Fold8 Ultra** (classic form): inner display about 2256×2504, near-square and portrait by default. The user rotates it to get a horizontal window.
- Both cover screens are under 600dp on their shortest side. Both inner screens are well over 600dp.
- The dp sizes above are unverified. Measure at runtime and don't hard-code device values.
- When flat, the hinge *should* report `FoldingFeature(FLAT, VERTICAL, isSeparating=false)`. **Don't rely on it being present.** Decide spread vs single page from window geometry, and use the fold only to place the gutter.

### What the codebase already gives us

- Reader activities (EPUB/PDF/comic) do **not** declare `configChanges`. Folding or unfolding the phone recreates the activity and reopens the book at the saved locator; this is the issue #47 flow, already confirmed on a device. So a spread decision taken in `onCreate` is always fresh after fold/unfold or rotation.
- EPUB hard-codes `columnCount = ColumnCount.ONE` in `readiumPreferences()` (`ReadiumEpubReaderActivity.kt:196`). It never sets `spread` for fixed-layout EPUBs.
- Paginated comics use Readium `ImageNavigatorFragment` (a `ViewPager` with one page per item). Continuous comics already have a custom Compose surface with a bounded LRU bitmap cache, a decode-at-width loader, tap zones and long-press export (`ContinuousComicReader.kt`, `ReadiumComicReaderActivity.loadContinuousComicPage`). The spread view can reuse all of that.
- PDF: upstream **deliberately removed** a custom spread renderer in PR #269 (2026-10-08, closes #193). Their stated scope: "No custom two-page spread pairing". Any PDF spread work diverges from upstream direction.
- Reader preferences are per library, stored in a versioned JSON envelope (`LibraryReaderPreferences`, `AppPreferencesStore`). The options UI is in `BookOrbitHomeScreen.kt` (~L4260–4680).
- The global orientation lock (`requestedOrientationForLock`) is honored by every reader.

## 2. Spread trigger policy

A pure, unit-tested function in a new file `ReaderSpreadPolicy.kt`:

```kotlin
internal enum class ReaderSpreadMode { AUTO, OFF }   // per-library preference, default AUTO

internal data class ReaderWindowState(
    val widthDp: Int,
    val heightDp: Int,
    val hinge: ReaderHinge?          // from FoldingFeature, null when unknown
)

internal fun shouldUseTwoPageSpread(
    mode: ReaderSpreadMode,
    window: ReaderWindowState,
    layoutMode: ReaderLayoutMode
): Boolean =
    mode == ReaderSpreadMode.AUTO &&
        layoutMode == ReaderLayoutMode.PAGINATED &&       // continuous scroll never spreads
        minOf(window.widthDp, window.heightDp) >= 600 &&  // unfolded inner screen, not the cover screen
        window.widthDp > window.heightDp &&               // horizontal
        window.hinge?.isHorizontalSeparating != true      // tabletop half-open: keep single page
```

- **Inputs:**
  - `Configuration.screenWidthDp`/`screenHeightDp` in `onCreate`, which is fresh on every recreation.
  - `WindowInfoTracker.windowLayoutInfo(activity)` collected under `repeatOnLifecycle(STARTED)`. Posture changes (FLAT ↔ HALF_OPENED) do not recreate the activity, so this flow reapplies live.
- **Multi-window/split screen:** the dp check uses the *window* size, not the display size, so a half-screen split falls back to a single page automatically.
- **Orientation lock:** if the user has locked portrait, the window is never horizontal, so there's no spread. No special case is needed.

## 3. Phased work

### Phase 0 — Setup (½ day)

- [ ] Create a GitHub fork (`chortya/lagrange-reader`), push the branch, and add `upstream` as a remote.
- [ ] Run the baseline gates green: `./gradlew :app:testDebugUnitTest :app:lintDebug :app:assembleDebug`.
- [ ] Set up AVDs: "7.6\" Fold-in with outer display", Pixel 9 Pro Fold, and "Resizable (Experimental)". Install the Samsung Galaxy emulator skin for the Fold8 aspect ratio.
- [ ] Book a Remote Test Lab slot on Z Fold8 (SM-F971) for later phases if no physical device is available.

### Phase 1 — Foundation: policy, preference, window tracking (1–2 days)

- [ ] Add `androidx.window:window:1.5.1` and `androidx.window:window-testing` (androidTest). If AAR metadata demands compileSdk 36, either bump compileSdk (targetSdk stays 35) or pin 1.4.x. Decide at build time.
- [ ] `ReaderSpreadPolicy.kt`: `ReaderSpreadMode`, `ReaderWindowState`, `ReaderHinge` (orientation, separating, bounds in px), `shouldUseTwoPageSpread`, and `spreadGutterPx()` (hinge centre if a vertical fold exists, else window centre).
- [ ] `ReaderWindowTracker`: a small helper that each reader activity owns. It exposes `StateFlow<ReaderWindowState>` from Configuration plus WindowInfoTracker, so the code is written once and not three times.
- [ ] `LibraryReaderPreferences`: add `twoPageSpread: ReaderSpreadMode = AUTO` and `comicSpreadCoverAlone: Boolean = true`. Persist them in the JSON envelope with defaults when the key is absent. Legacy and malformed profiles must still load, per the architecture rule.
- [ ] Options UI (`BookOrbitHomeScreen.kt`): add a "Two-page spread on unfolded screen" toggle (EPUB + comic sections), plus a "Show cover alone" toggle for comics.
- [ ] Tests:
  - `ReaderSpreadPolicyTest`: cover screen (landscape) → single; inner portrait → single; inner landscape → spread; split-screen half → single; tabletop half-open → single; OFF → single; continuous → single.
  - `LibraryReaderPreferencesTest`: round-trip and legacy decode.

### Phase 2 — EPUB (2–3 days) — highest value, lowest risk

- [ ] Reflowable: `readiumPreferences(..., twoPageSpread: Boolean)` → `columnCount = if (spread) ColumnCount.TWO else ColumnCount.ONE`. Readium turns pages by full viewport width, so one "page turn" moves two columns.
- [ ] Fixed-layout: set `spread = if (spread) Spread.ALWAYS else Spread.NEVER`. Readium 3.x treats AUTO as single, so it must be explicit.
- [ ] Live switching: on a posture or spread change without recreation, call `navigator.submitPreferences(...)` (the existing `applyReaderPreferences` path). The locator survives.
- [ ] Gutter:
  - Readium's column gap sits at the viewport centre. Keep left and right padding symmetric while in spread so the gap lands on the hinge.
  - Check the Fold8 hinge is at the exact centre using `spreadGutterPx()`.
  - Optionally add an inner-gutter padding preference later.
- [ ] Verify these still behave correctly in two-column mode:
  - **Progress footer:** `PaginationListener` `pageIndex`/`totalPages` now count screens. Check the "page x of y" wording.
  - **Resume:** positions/percentage resume and saved-instance locator restore.
  - **Text to speech and read-along:** sentence highlighting and auto page-turn across columns, including TTS running across a fold/unfold.
  - **Annotations:** highlight selection and action mode when a selection crosses the column boundary.
  - **Image gestures:** the EPUB image gesture bridge (`epub-image-gesture.js`) for image-only pages.
  - **Navigation:** tap zones and the tutorial overlay (screen-level, so unchanged), plus volume-button paging.
- [ ] Tests:
  - JVM: `readiumPreferences` column/spread mapping.
  - Instrumented: extend `ReaderOrientationConfigurationInstrumentedTest` and `EpubReaderOptionsOverlayInstrumentedTest`, using `window-testing` fakes and a forced landscape ≥600dp configuration.

### Phase 3 — Comics, paginated (5–7 days)

Readium's image navigator can't do spreads, so add a custom Compose spread pager. It is used only when the spread is active; otherwise the existing `ImageNavigatorFragment` path stays untouched.

- [ ] **Pairing algorithm** (pure, `ComicSpreadPairing.kt`):
  - `comicSpreads(pageCount, coverAlone, pageAspect: (Int) -> Float?, offset: Int): List<ComicSpread>`.
  - Landscape or double-width pages (aspect > 1) stand alone.
  - The cover is shown alone when enabled.
  - Pages in the spread are ordered left/right by `LibraryReadingDirection`: RTL puts page *n* on the right.
  - An "offset by one page" toggle realigns two-page art in manga whose pairing is off by one, as other readers offer.
- [ ] **Aspect ratios** come from the existing `continuousComicPageAspectRatios` cache, or from cheap bounds-only decodes. Until a ratio is known, a page is treated as portrait, and pairs are recomputed when a wide page is found. The current spread must stay anchored while that happens.
- [ ] **`ComicSpreadReader` composable:**
  - `HorizontalPager` of spreads.
  - Each half is decoded at half the window width, reusing `loadContinuousComicPage` and the LRU cache, so memory use is about the same as the single-page view.
  - Gutter at the hinge.
  - Bounded pinch-zoom/pan on the whole spread, reusing the `ComicPageImageViewer` limits.
  - Long-press on either half exports that page through the existing path.
- [ ] **Navigation:** tap zones (`readerTapZoneAction`), volume buttons and the menu toggle move by spread. The tutorial overlay is reused.
- [ ] **Progress and resume:**
  - The locator saved is the *first* page of the spread, so the spread realigns after recreation.
  - Reported progress uses the *last* visible page, so the final spread reaches 100%.
  - `ReadingSessionTracker` page activity is fired for both pages.
- [ ] **Fold/unfold mid-book:** recreation reopens at the saved page index. That page is in the new spread (or shown alone when folded), so no position is lost.
- [ ] Tests:
  - `ComicSpreadPairingTest`: cover alone, odd/even counts, wide pages, RTL, offset, single-page book.
  - Progress-mapping unit test.
  - Instrumented open/rotate test, extending `ReadiumComicOpenInstrumentedTest`.

### Phase 4 — PDF — OUT OF SCOPE (decided 2026-10-09)

PDF keeps the native single-page PDFium navigator on every screen, consistent with upstream PR #269. The spread policy must not touch `ReadiumPdfReaderActivity`.

If this is revisited, the path would be to reuse the Phase 3 spread pager through a `PageBitmapSource` abstraction and render pages with PdfiumCore. That would mean rebuilding zoom, snapping, `PdfHyperlinkTapHandler` hit-testing and `pdfColorMatrix` inversion, and it would be a long-lived divergence from upstream.

### Phase 5 — Validation, docs, release (2 days)

- [ ] Automated gates from `CONTRIBUTING.md`: unit tests, `compileDebugAndroidTestKotlin`, lint, `assembleDebug`, `git diff --check`.
- [ ] Device matrix (Fold8 first): Fold8 inner landscape = natural posture, and inner after rotating to portrait; Fold8 Ultra inner rotated to landscape; cover screen both orientations; split-screen; half-open book and tabletop postures; fold/unfold mid-chapter with TTS playing; RTL manga; orientation lock on.
- [ ] Update `docs/architecture.md` (reader section), `docs/ui-ux.md` (reader contracts), `docs/testing.md` (spread manual procedure) and `CHECKLIST.md`.
- [ ] Optional: open an upstream issue/PR for the EPUB part. It's small, self-contained and likely acceptable. Keep the comic spread pager on the fork unless upstream wants it.

**Estimate:** about 2–2.5 weeks (Phases 0–3 plus Phase 5).

## 4. Risks and notes

- **Android 16 large-screen rules.** Once an app targets API 36, on sw≥600dp displays (the Fold inner screen) Android ignores `setRequestedOrientation`, orientation/aspect manifest attributes and `resizableActivity`. Upstream targets 35 today. Play now requires 36 for updates, so this will land soon, and the reader orientation lock will then stop working on the unfolded screen. The spread policy is purely geometry-based, so it keeps working. The orientation-lock UI should say it doesn't apply on large screens.
- **compileSdk bump.** The latest `androidx.window` may require compileSdk 36. That is low risk because targetSdk stays 35.
- **Near-square Ultra.** On the Ultra in landscape, each spread page is narrow (~0.55 aspect). Comics will letterbox top and bottom. EPUB columns of roughly 450–600dp are comfortable. The per-library OFF switch covers users who prefer a single page.
- **Readium upgrade not required.** 3.0.2 already has `columnCount` and `spread`. Upgrading to 3.4.0 is a separate decision; it raises compile/target SDK.
- **Upstream sync.** Keep spread code in new files (`ReaderSpreadPolicy.kt`, `ComicSpreadPairing.kt`, `ComicSpreadReader.kt`, `ReaderWindowTracker.kt`) with thin hooks into the existing activities, so rebasing on upstream stays cheap.

## 5. Decisions (2026-10-09)

1. **Target device: Z Fold8 (wide).** The primary posture is the natural unfolded landscape, with the hinge vertical and one page on each side of it. Test first on Fold8 (physical device or Remote Test Lab SM-F971). The Ultra and other foldables are covered by the same geometry policy but are secondary in the test matrix.
2. **PDF is out of scope.** Phase 4 is dropped, PDF keeps native single-page PDFium behavior, and there's no divergence from upstream PR #269.
3. **Work happens on a GitHub fork** (`chortya/lagrange-reader`) with `upstream` → `van-geaux/lagrange-reader`. Upstreaming the EPUB part stays optional.
4. **Added scope: true OLED white-on-black design for EPUB only.** Comics stay full colour.

## 6. Implementation status (2026-10-09)

| Phase | Status | Notes |
|---|---|---|
| 0 Setup | Done | Fork `chortya/lagrange-reader`. Baseline 837 JVM tests green. The 5 parser tests that fail on a non-English host locale are pre-existing; run Gradle with `JAVA_TOOL_OPTIONS="-Duser.language=en -Duser.country=US"`. Pixel Fold API 36 AVD used as the Fold8 stand-in. |
| 1 Foundation | Done | `ReaderSpreadPolicy`, `ReaderWindowTracker` (`androidx.window` 1.5.1, compileSdk 35 unchanged), per-library preferences and options UI. |
| 2 EPUB | Done | Readium reflowable `columnCount` / fixed-layout `spread`, plus `RsProperties` to bypass ReadiumCSS's 60em media query (found during implementation: the Fold8 window is under 960 CSS px), plus per-page margins that follow the reader margin setting live (symmetric on both pages; verified on device for margins, line spacing and font size). Rotating to portrait falls back to one page (instrumented test). |
| OLED | Done | Pure black/white page, chrome, sheets, dialogs, window and status bar. In-page white links and selection. Images untouched. |
| 3 Comics | Done | `ComicSpreadReader` pager: cover alone, shift, wide pages shown alone, RTL, zoom, export, spread navigation, first-page resume / last-page progress. |
| 4 PDF | Out of scope | — |
| 5 Validation | Automated done; device pending | JVM 905 tests, lint clean, connected suite on Pixel Fold AVD. A physical Fold8 / Remote Test Lab pass is still required (see `docs/testing.md` → Foldable spreads and OLED EPUB). |
