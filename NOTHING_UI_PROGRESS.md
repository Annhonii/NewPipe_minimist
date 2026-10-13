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

## TODO (pass 3 = last ~30%)
1. Player UI (player.xml): control buttons background + press feedback, seekbar look, popup/queue panels, fullscreen overlay buttons, fast-seek overlay.
2. Search screen: toolbar search field as a pill, suggestion rows (item_search_suggestion), filter chips, empty/no-results state in Ndot.
3. Drawer/account header, bottom sheets (bottom_sheet_video_info, long-press menus), dialogs list rows, download dialog + downloads (mission_item) screens.
4. Splash screen: Ndot wordmark + dot loader; app icon check.
5. Shimmer/placeholder thumbnails in Nothing grey, empty states: make sure every empty list uses list_empty_view style.
6. Channel page header, playlist header, subscription feed headers: spacing/typography (NType82 titles).
7. Haptics: light tick on switch/tab toggle (View.performHapticFeedback) where missing.
8. Final consistency pass over Light/Dark/Black and fix anything that still looks like stock NewPipe. Update this file.
