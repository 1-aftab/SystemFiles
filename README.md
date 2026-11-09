# System Files Launcher 📂

[![Build APK](https://github.com/aftablone100-create/SystemFiles/actions/workflows/build.yml/badge.svg)](https://github.com/YOUR_USERNAME/SystemFiles/actions)
![Platform](https://img.shields.io/badge/Platform-Android-green.svg)
![Target SDK](https://img.shields.io/badge/Target%20SDK-35-blue.svg)
![License](https://img.shields.io/badge/License-MIT-orange.svg)

A lightweight, minimal Android file manager with full storage access. It asks the device for **All files access**, then lets every user profile browse, move, copy, paste, rename and delete files anywhere on shared storage — no third-party file manager or bloat involved.

---

## 🚀 Features

* **All files access:** Requests `MANAGE_EXTERNAL_STORAGE` (Android 11+) or the legacy read/write permissions (Android 6–10) so the whole file system is reachable, for every user profile that grants it.
* **Full file operations:** Copy, move, paste (with automatic `name (1)` de-duplication), rename, delete and create folders — single items or multi-select, recursively for folders.
* **Built-in browser:** Starts in internal storage, shows folders first, supports the device root, hidden files and file details (size, modified date).
* **Fast and dependency-free:** Plain `android.widget` UI on top of `java.io.File`, no third-party libraries.
* **Safe by design:** Confirmation before deleting, guarded against copying/moving a folder into itself, per-file error reporting, and operations run off the main thread.
* **Optional native picker:** The original behaviour (launching the system document picker) is still available from the ⋮ menu.
* **Modern Target:** Compiled with Android SDK 35 (Android 15) and Kotlin JVM target 17.

---

## 📁 Project Structure

```text
SystemFiles/
├── .github/
│   └── workflows/
│       └── build.yml               # Automated APK CI/CD pipeline
├── app/
│   ├── build.gradle.kts        # App-level build configurations
│   └── src/main/
│       ├── AndroidManifest.xml
│       ├── java/com/example/systemfiles/
│       │   ├── MainActivity.kt # Browser UI, permissions, file actions
│       │   └── FileOperations.kt # Recursive copy / move helpers
│       └── res/                # App resources and vector icons
├── build.gradle.kts            # Project-level build configuration
├── gradle.properties
└── settings.gradle.kts
```

## 🔐 Granting access

The first time the app opens it explains why it needs full storage access and opens the matching system screen:

* **Android 11+:** Settings → Apps → System Files → **All files access** → Allow management of all files.
* **Android 6–10:** A normal storage permission prompt.

Access is granted per Android user, so each additional user profile on the device must grant it once for itself.

# 🛠️ Build from Source

* You can build this project directly from raw source code either locally on your machine or automatically in the cloud using GitHub Actions.

## Automated Cloud Build (GitHub Actions)

* → Fork or clone this repository to your GitHub account.

* → Go to the Actions tab in your repository.

* → Select the Build APK workflow.

* → Click Run workflow -> Run workflow.

* → Once completed, click on the workflow run and download the SystemFiles-debug artifact.
