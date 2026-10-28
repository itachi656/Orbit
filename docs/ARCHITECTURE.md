# How Orbit is built

Orbit is an Android app written in **Kotlin + Jetpack Compose**. There is a single screen (`MainActivity`); themes, menus, search, the store and animations are drawn on top of it. All data is stored locally inside the app.

## Layout

```
app/src/main/java/dev/orbit/launcher/
├── OrbitApp.kt              entry point: creates Prefs, Library, GameLauncher, ArtFetcher, Store
├── I18n.kt                  translations (uk → en / ru); tr("text", args)
├── UsbAttachActivity.kt     autostart when a USB controller is connected
├── PinShortcutActivity.kt   "Add to home screen" from other apps → game in Orbit
│
├── data/                    DATA
│   ├── Models.kt            Game, LibraryFile, Session (for statistics)
│   ├── Library.kt           library: library.json, rescanning, play time
│   ├── Scanner.kt           finds ROMs in folders, multi-disc games, Android games
│   ├── Systems.kt           systems (NES…Switch, PC): extensions, folders, emulator order
│   ├── EsdeEmulators.kt     emulator launch profiles (generated from ES-DE)
│   └── Prefs.kt             all settings (SharedPreferences)
│
├── launch/
│   └── GameLauncher.kt      how a game is started: emulator Intent, SAF access,
│                            FileProvider, Moonlight, shortcuts, .m3u, emulator choice by file type
│
├── art/
│   └── ArtFetcher.kt        artwork: own files → Steam → SteamGridDB → libretro
│                            (fuzzy NameMatch), image validation and repair
│
├── store/
│   └── Store.kt             store: sources, catalogs (Orbit / Homebrew Hub / Universal-DB),
│                            cache, downloads with progress, zip extraction
│
├── input/
│   └── Gamepad.kt           buttons, D-pad, stick, triggers → Btn events with auto-repeat
│
└── ui/                      INTERFACE
    ├── MainActivity.kt      the only screen; input, permissions, brightness/volume, battery
    ├── HomeController.kt    the UI "brain": state, tabs, button handling, all menus
    ├── HomeScreen.kt        screen root, backgrounds, tiles, top bar, button hints
    ├── Layouts.kt           theme layouts: Row, XMB, Carousel, Grid, CRT, Channels, Blades
    ├── Themes.kt            theme definitions (colors, font, layout)
    ├── Overlays.kt          side menus and forms
    ├── Search.kt            search and the on-screen keyboard (EN/UK/RU)
    ├── QuickMenu.kt         quick menu (Guide button)
    ├── StoreScreen.kt       store screen
    ├── LaunchFx.kt          game launch animations
    ├── BootIntro.kt         launcher boot animation
    ├── ConsoleIcons.kt      vector console silhouettes
    ├── Sounds.kt            synthesized UI sounds
    └── Music.kt             background music (synthesized ambient or user file)

tools/orbit_share.py         "My PC": shares games from a computer as a store source
```

## How it works

### From a file to a tile
1. `Scanner` walks `ROMs/<folder>` for every system in `Systems` and creates `Game` entries. The library merges them with existing data, so play time, favorites and artwork are kept.
2. `ArtFetcher` finds artwork for games without a cover and stores the file paths in `Game`.
3. `HomeController.buildTabs` sorts games into tabs (Home, collections, systems, Android, Cloud).
4. The theme (`Themes`) picks a layout from `Layouts`, which draws those tabs.

### From a button press to a running game
1. `MainActivity.dispatchKeyEvent` → `Gamepad` → `HomeController.onButton(Btn)`.
2. Whatever is on top handles the button: boot intro → quick menu → search → form → menu → store → home screen.
3. On *Play*, `GameLauncher.check` first verifies the game can start (emulator installed, folder access). Then the launch animation plays, and `GameLauncher.launch` builds the `Intent` from the emulator profile.
4. When the user returns to Orbit, `onReturnFromGame` records the session and play time.

### Emulator profiles
`EsdeEmulators.kt` is generated from ES-DE's `es_systems.xml` and `es_find_rules.xml`. Template values:
- `{ROM}` — file path;
- `{SAF}` — document URI inside the granted folder;
- `{PROVIDER}` — FileProvider URI;
- `{RA_CORE:x}` — RetroArch core;
- `{INJECT}` — contents of the game file;
- `{RESUME}` — DuckStation resume.

### Data
- `files/library.json` — games and sessions;
- `files/art/` — artwork;
- `files/store/` — store sources and catalog cache;
- `SharedPreferences "orbit"` — settings.

## How to add…

- **A system:** a `sys(...)` line in `Systems.kt` and profiles in `EsdeEmulators.kt`; optionally an icon in `ConsoleIcons.kt`.
- **A theme:** a `ThemeSpec(...)` in `Themes.kt`. Reuse an existing layout, or add a new one in `Layouts.kt` and its button handling in `HomeController.onButton`.
- **A translation:** wrap the text in code as `tr("…")` and add the translation to both dictionaries in `I18n.kt`.
- **A store source:** a JSON catalog in Orbit's format (see README); no code changes needed.

## Building

```bash
./gradlew assembleRelease     # app/build/outputs/apk/release/app-release.apk
./gradlew assembleDebug       # debug build
```

Requires JDK 17 and Android SDK 35. Release builds are currently signed with the debug key.
