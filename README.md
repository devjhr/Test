<h1 style="text-align:center; width:100%; color:red;font-size: 90px;">
  AndroidCS IDE
</h1>

# Andro Studio

A powerful Android IDE built for Android, featuring a multi-tab code editor, terminal emulator, layout editor, and live Compose UI preview — all running directly on your Android device.

---

## Features

### Code Editor
- Multi-tab code editor powered by [Sora Editor](https://github.com/Rosemoe/sora-editor)
- Syntax highlighting for multiple languages
- Language Server Protocol (LSP) support via `language-server` module
- Auto-completion, diagnostics, and code navigation

### Terminal
- Full-featured terminal emulator based on [Termux](https://github.com/termux/termux-app)
- Built-in shell environment
- Support for running build commands directly from the IDE

### Layout Editor
- Drag-and-drop UI layout editor
- Real-time preview of XML layouts
- View hierarchy and property inspector

### Compose UI Preview
- Live preview of Jetpack Compose `@Preview` functions
- Headless Kotlin compiler — compiles and renders without a desktop IDE
- Refresh button to re-compile and update preview instantly
- Supports dark/light theme switching

### Build System
- Gradle-based project build support
- Configurable build options (cache, parallel, lint, test)
- APK signing support
- Build log viewer

---

## Architecture

```
Andro-Studio/
├── app/                        # Main application module
├── termux/
│   ├── terminal-emulator/      # Terminal emulator core
│   ├── terminal-view/          # Terminal UI view
│   └── termux-shared/          # Shared Termux utilities
├── layout-editor/              # Drag-and-drop layout editor
├── vectormaster/               # Vector drawable rendering
├── language-server/            # LSP client/server integration
├── tree-view/                  # File tree view component
└── compose-preview/            # Live Compose UI preview module
    ├── android-stubs/          # Android API stubs for headless compile
    ├── common/                 # Shared utilities and constants
    ├── build-tools/            # Kotlin compiler + DEX pipeline
    ├── resources/              # Styles, themes, drawables
    └── utils/                  # Android utility extensions
```

---

## Compose Preview Module

The `compose-preview` module enables live rendering of Jetpack Compose previews on-device without Android Studio.

**How it works:**
1. Reads a `.kt` source file from device storage
2. Compiles it using a headless `KotlinEnvironment` (kotlinc-embeddable)
3. Converts compiled classes to DEX format using R8/D8
4. Loads and renders the `@Preview` composable dynamically via reflection

**Usage from your app:**
```java
Intent intent = new Intent(context, EditorActivity.class);
intent.putExtra(EditorActivity.EXTRA_FILE_PATH, "/path/to/YourFile.kt");
startActivity(intent);
```
If no path is provided, it loads the default `Playground.kt` file.

---

## Tech Stack

| Component | Library |
|-----------|---------|
| Code Editor | [Sora Editor](https://github.com/Rosemoe/sora-editor) |
| Terminal | [Termux](https://github.com/termux/termux-app) |
| Kotlin Compiler | kotlinc-embeddable (KodTik fork) |
| DEX Compiler | R8 / D8 |
| UI | Jetpack Compose + Material3 + View system |
| Image Loading | Coil 3 |
| 3D Rendering | SceneView |
| AI | Google Generative AI |
| Build | Gradle 9.x + AGP 9.x |

---

## Requirements

- Android 8.0+ (API 26+)
- compileSdk 36
- NDK 29.0.14206865

---

## Building

### GitHub Actions (Recommended)
The project uses GitHub Actions for CI builds due to its size.

Workflows available in `.github/workflows/`:
- `debug-apk.yml` — builds a debug APK
- `release-apk.yml` — builds a signed release APK

Trigger manually from the **Actions** tab in your repository.

### Local Build
```bash
./gradlew assembleDebug
```
> ⚠️ Requires at least 4GB RAM. Building on low-memory devices may fail.

---

## License

This project is for personal/educational use. Third-party components are subject to their respective licenses:
- Termux — [GPL-3.0](https://github.com/termux/termux-app/blob/master/LICENSE.md)
- Sora Editor — [LGPL-2.1](https://github.com/Rosemoe/sora-editor/blob/main/LICENSE)

