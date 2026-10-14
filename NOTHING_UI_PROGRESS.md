# Nothing-style redesign: progress log (read this first)

Reference apps (not in this zip): Shrinky (Compose, Nothing look), Music-dev (Nothing-OS music app), VoidCaller screenshots.
Design tokens: black/near-black bg, #1C1C1C cards, #323232 hairlines, Nothing red #D71921 as only accent,
Geist (body), NType82 (screen titles), Ndot 77 (dot-matrix brand/accents), pill shapes, no heavy shadows.

## Already present in this fork (before this pass)
fonts (geist, geist_mono, ntype82, ndot), nothing_red tokens, pill switch/slider, rounded dialogs + popup menus,
bg_nav_pill / bg_mini_player / bg_comment_card / bg_list_card drawables, window + list-enter animations (anim/, animator/press_scale, interpolator/).

## DONE in pass 1 (~30%)
- colors.xml: slate/blue palette -> Nothing neutrals (light #F2F2F2/#FFF, dark #0A0A0A/#1C1C1C, black #000, toolbar tokens).
- Geist set as global android:fontFamily in Base.V21 + 4 dialog themes.
- Toolbar title -> NType82 26sp; TextAppearance.App.Accent -> Ndot; Headline -> NType82; Mono -> Geist Mono.
- layouts: monospace -> @font/geist_mono, main_bg brand text -> @font/ndot.

## DONE in pass 2 (~30%)
Audit result: static styling (cards, pills, nav pill, mini player, detail page, settings segments) was already mostly Nothing-like.
The gap was MOTION + tactile feedback, so pass 2 focused there:
- InfoListAdapter: rows rise + fade in with a 42ms stagger (first appearance only, comments excluded, reset on clear).
- animator/press_scale_soft.xml (0.97) applied to ~28 list/card/grid/mini rows; animator/press_scale (0.92) applied to 12 pill buttons
  (like/dislike, share, download, subscribe, chips, icon buttons...). Comment rows intentionally left alone.
- Loader: drawable/progress_dot_ring(.xml, _layers.xml) = stepped dotted ring (Widget.App.ProgressBar), set as theme
  progressBarStyle / Large / LargeInverse, plus 2 explicit layouts switched to it.
- Main tabs: ViewPager page transformer in MainFragment (fade + 5% scale + parallax).
- Settings section labels: small, muted, normal weight.
NOTE: nothing was compiled here. If the build fails, check first: progress_dot_ring (animated-rotate), InfoListAdapter imports, MainFragment lambda.

## DONE in pass 3 (partial, ~30%)
- Splash: stock red + NewPipe triangle removed. Now Nothing red dot on #F2F2F2 (light) / #000 (dark); opening nav bar transparent.
- Notification/status-bar glyph ic_newpipe_triangle_white.xml replaced by ring + dot (same file name, so no code change).
- Player: play/pause = white disc with black icon (bg_player_play), prev/next = translucent discs (bg_player_ctrl), press_scale on all three.
- Search: toolbar field is a tonal pill (bg_search_field); suggestion rows 52dp min, 15sp, softer icon, more padding.
- Bottom sheet (bottom_sheet_video_info): 28dp top corners (bg_sheet_top), title in NType82.
- Haptics: CLOCK_TICK on main tab switch (MainFragment.onTabSelected).
NOTE: still not compiled here. Check first: bg_sheet_top / bg_search_field use ?attr/surface_bright_color and ?attr/toolbar_element_color,
player.xml padding on the 3 buttons (icon size in landscape / popup), toolbar_search_layout margins vs clear button.

## DONE in pass 4 (user request)
- Monet/dynamic colour removed (values-v31, accent presets, overlay). Palette = black/grey/white + ice (accent_ice_dark #CFE8F5, accent_ice_light #2F4A5C). Red removed everywhere (also Compose Color.kt + ServiceTheme).
- Nav pill rebuilt: grey capsule, selected tab = darker capsule with icon + name (nav_pill_tab.xml, ScrollableTabLayout widens selected tab, MainFragment sets custom tab views). Not compiled here.

## TODO (pass 4 = remaining)
1. Player: seekbar in fullscreen, popup/queue panels, fast-seek overlay, play/pause disc inside popup mode.
2. Search: filter chips, empty/no-results state in Ndot.
3. Drawer/account header, long-press menus, dialog list rows, download dialog + downloads (mission_item) screens.
4. App icon check (mipmaps still stock?), shimmer/placeholder thumbnails in Nothing grey.
5. Channel page header, playlist header, subscription feed headers: spacing/typography (NType82 titles).
6. More haptics (switches, chips), then final Light/Dark/Black consistency pass.

## Pass 5: morphing floating nav (Google Phone / Material 3 style)
- New views/MorphingNavPill (tabs: selected = accent chip with icon + label, others icon only; width, colour,
  label alpha/scale all come from one fraction per tab; spring ~300 ms) and views/FloatingNavBar (pill + round
  action buttons + ViewPager sync). Swiping the pages morphs the chip under the finger; tapping springs it.
- fragment_main.xml (+ land) now use FloatingNavBar (id main_nav_bar). MainFragment adds round Downloads / History
  buttons when they are not already tabs. Removed NavTabView and nav_pill_indicator. ScrollableTabLayout is unused now.
- Chip colour = theme colorAccent (FloatingNavBar#setChipColor to override). NOT compiled here.

## Motion switched to Material 3 (user request)
- Fragment + activity transitions = M3 Shared axis X (300ms, 30dp, fade out 90ms / fade in 210ms after 90ms); back = mirrored (screen_pop_*, frag_pop_*).
- Dialogs = M3 (fade 150 + scale 0.8->1 250ms in, 100ms fade out). Settings/list entrance = M3 fade through (no stagger).
- New interpolators: standard_accelerate, standard_decelerate. Not compiled.
