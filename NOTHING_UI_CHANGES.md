# Nothing / Music UI port

Design source: MissingCore/Music (Nothing-inspired). Ported to NewPipe's Android XML.

- Fonts: Geist (body), Ndot 77 Latin subset (accent), NType82 (headline), Geist Mono (technical).
- Colors: pure black dark theme, #121212/#1C1C1C surfaces, #D71921 accent (all themes).
- Toggles: 44x24 pill switch, white thumb (styles_nothing.xml, Widget.App.Switch).
- Settings: segmented card lists (SegmentedPreferenceAdapter) with staggered entrance.
- Dialogs: 24dp floating surface, scale+fade animation, accent text buttons.
- Sliders: fat 10dp pill seekbar. Mini player: full pill. Thumbnails: 12dp clipped.
- Transitions: fade + 24dp rise (animator/custom_fade_in.xml).

Not compiled in the authoring environment (no Android SDK): run ./gradlew assembleDebug.
