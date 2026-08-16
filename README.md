# ⚡ SwiftBackupPrem

<p align="center">
  <b>An advanced Xposed / LSPosed module for <a href="https://play.google.com/store/apps/details?id=org.swiftapps.swiftbackup">Swift Backup</a> that unlocks Premium features and enables isolated, custom Firebase backend integration.</b>
</p>

<p align="center">
  <a href="https://github.com/s1ddhants1/SwiftBackupPrem/releases"><img src="https://img.shields.io/github/v/release/s1ddhants1/SwiftBackupPrem?style=for-the-badge&color=6366f1&logo=android" alt="Release Version" /></a>
  <a href="https://github.com/s1ddhants1/SwiftBackupPrem/blob/main/LICENSE"><img src="https://img.shields.io/github/license/s1ddhants1/SwiftBackupPrem?style=for-the-badge&color=10b981" alt="License" /></a>
  <img src="https://img.shields.io/badge/Android-8.1%2B%20(API%2027--37)-f59e0b?style=for-the-badge&logo=android" alt="Android API Support" />
  <img src="https://img.shields.io/badge/Framework-LSPosed%20%2F%20Xposed-8b5cf6?style=for-the-badge" alt="Xposed / LSPosed" />
  <img src="https://img.shields.io/badge/UI-Jetpack%20Compose%20M3-06b6d4?style=for-the-badge&logo=jetpackcompose" alt="Jetpack Compose Material 3" />
</p>

---

## 📖 Table of Contents
- [✨ Features](#-features)
- [📱 Compatibility & Prerequisites](#-compatibility--prerequisites)
- [🚀 Installation & Activation](#-installation--activation)
- [🔥 Custom Firebase Setup Guide](#-custom-firebase-setup-guide)
  - [Why Use Your Own Firebase Instance?](#why-use-your-own-firebase-instance)
  - [Video Walkthrough](#video-walkthrough)
  - [Step 1: Create a Firebase Project](#step-1-create-a-firebase-project)
  - [Step 2: Set Up Realtime Database & Security Rules](#step-2-set-up-realtime-database--security-rules)
  - [Step 3: Configure Authentication](#step-3-configure-authentication)
  - [Step 4: Register Android App & OAuth Client](#step-4-register-android-app--oauth-client)
  - [Step 5: Enable Google Drive API](#step-5-enable-google-drive-api)
  - [Step 6: Import or Enter Credentials in SwiftBackupPrem](#step-6-import-or-enter-credentials-in-swiftbackupprem)
- [🔄 Backup, Export & Migration](#-backup-export--migration)
- [🛠️ Building from Source](#️-building-from-source)
- [❓ Frequently Asked Questions (FAQ)](#-frequently-asked-questions-faq)
- [🤝 Credits & Acknowledgements](#-credits--acknowledgements)
- [⚖️ License & Disclaimer](#️-license--disclaimer)

---

## ✨ Features

- 🔓 **Premium Toggle & Unlocking**: Enables all Swift Backup Premium functionality on-demand without needing Google Play Store licensing, with active runtime state enforcement.
- 🚫 **Disable Telemetry & Tracking**: Selectively blocks Firebase Analytics, Crashlytics, Sessions, Installations, and Google DataTransport tracking calls for maximum privacy.
- 🛡️ **Custom Firebase Backend (Anti-Ban & Privacy)**: Directs Swift Backup to use your personal Firebase instance for user authentication and cloud synchronization metadata, eliminating reliance on the developer's shared backend.
- ⚡ **Dynamic DexKit Bytecode Scanning**: Utilizes [DexKit](https://github.com/LuckyPray/DexKit) to dynamically locate obfuscated classes and methods at runtime across versions, ensuring robust compatibility with newer app updates.
- 🎨 **Modern Material 3 UI**: Clean user interface built with Jetpack Compose, edge-to-edge display, dynamic theme adaptation, and responsive layouts.
- 🧙 **Interactive 5-Step Guided Wizard**: In-app wizard with 1-click clipboard helpers (package name, SHA-1 signing fingerprint) and direct links to Firebase and Google Cloud consoles.
- 📥 **One-Tap JSON Import**: Automatically parses and fills credentials directly from standard `google-services.json` files.
- 💾 **Configuration Export & Import**: Easily backup or migrate your custom Firebase setup across devices using `sbp_config.json`.
- 📦 **Automated Module & Config Archiving**: Automatically mirrors and backs up the module APK and `google-services.json` into your backup storage directory (`sbp/`) whenever Swift Backup creates an APK backup.
- ⚡ **Quick Process Controls**: One-tap Root Force Stop and Launch shortcuts directly inside the app.

---

## 📱 Compatibility & Prerequisites

| Requirement | Details |
| :--- | :--- |
| **Root Solution** | Magisk, KernelSU, or APatch |
| **Xposed Framework** | [LSPosed](https://github.com/LSPosed/LSPosed) (Zygisk / Riru) v1.9.0+ or compatible framework |
| **Android Version** | Android 8.1 (Oreo MR1 / API 27) up to Android 15 / 16 (API 37+) |
| **Target Application** | [Swift Backup](https://play.google.com/store/apps/details?id=org.swiftapps.swiftbackup) (`org.swiftapps.swiftbackup`) |
| **Tested App Versions** | v4.2.3, v4.2.5, v5.0.4, v5.1.0, and newer releases |

---

## 🚀 Installation & Activation

### Method 1: Obtainium (Recommended)

Automatically download and receive updates by adding SwiftBackupPrem to [Obtainium](https://github.com/ImranR98/Obtainium):

<p>
  <a href="https://apps.obtainium.imranr.dev/redirect?r=obtainium://add/https%3A%2F%2Fgithub.com%2Fs1ddhants1%2FSwiftBackupPrem">
    <img src="https://raw.githubusercontent.com/ImranR98/Obtainium/main/assets/graphics/badge_obtainium.png" alt="Get it on Obtainium" height="80">
  </a>
</p>

Or add the repository URL manually in Obtainium:
```
https://github.com/s1ddhants1/SwiftBackupPrem
```

### Method 2: Manual Download

1. **Download**: Grab the latest APK from the [Releases](https://github.com/s1ddhants1/SwiftBackupPrem/releases) page.
2. **Install**: Install the APK on your rooted Android device.

---

### Module Activation (LSPosed)

1. Open **LSPosed Manager**.
2. Navigate to the **Modules** tab and tap **SwiftBackupPrem**.
3. Toggle **Enable module**.
4. Ensure the scope includes **Swift Backup** (`org.swiftapps.swiftbackup`).
5. Perform a quick reboot (or soft reboot) of your device.
6. Open the **SwiftBackupPrem** app to configure custom Firebase credentials (recommended) or launch Swift Backup directly.

---

## 🔥 Custom Firebase Setup Guide

### Why Use Your Own Firebase Instance?

By default, Swift Backup authenticates against the app developer's Firebase project. If the developer blocks or bans your account on their Firebase instance, cloud authentication and cloud sync in Swift Backup will stop working. 

Connecting your own personal Firebase project gives you complete isolation, ensures data privacy, and prevents remote bans.

---

### Video Walkthrough

Watch the complete step-by-step video guide below for visual reference:

> **[▶ Watch Firebase Setup Video Guide](https://user-images.githubusercontent.com/31005896/203136303-36079018-3199-4863-864b-40293342f262.mp4)**

---

### Step 1: Create a Firebase Project

1. Go to the [Firebase Console](https://console.firebase.google.com/).
2. Click **Add project** (or **Create a project**).
3. Enter a project name (e.g., `SwiftBackup-Personal`) and continue.
4. Google Analytics can be disabled (optional) to speed up creation.
5. Click **Create project** and wait for provisioning to finish.

---

### Step 2: Set Up Realtime Database & Security Rules

1. In your Firebase project sidebar, go to **Build > Realtime Database**.
2. Click **Create Database**, select a region close to you (e.g., `United States` or `Belgium`), and choose **Start in locked mode**.
3. Once created, switch to the **Rules** tab at the top.
4. Replace the existing rules with the following user-isolated security rules:

```json
{
  "rules": {
    "users": {
      "$uid": {
        ".read": "$uid === auth.uid",
        ".write": "$uid === auth.uid"
      }
    }
  }
}
```

5. Click **Publish** to save the rules.
6. Copy your **Realtime Database URL** from the *Data* tab (e.g., `https://your-project-id-default-rtdb.firebaseio.com/`).

---

### Step 3: Configure Authentication

1. In the Firebase sidebar, navigate to **Build > Authentication**.
2. Click **Get Started**, then select the **Sign-in method** tab.
3. Under *Additional providers*, select **Google**.
4. Toggle **Enable**, choose a Project support email, and click **Save**.

> [!NOTE]
> **Firebase Cloud Storage is Optional (Skip if on Spark Plan)**
> 
> Firebase Cloud Storage now requires a paid **Blaze Plan** (linked Cloud Billing account). **You can safely skip enabling Cloud Storage in Firebase Console.**
> Swift Backup does **not** store your backup files (APKs, app data, call logs) inside Firebase Storage. Firebase is only used for authentication and metadata synchronization. Your actual backups are stored in your configured cloud provider (Google Drive, WebDAV, Nextcloud, SMB, etc.) or local storage.

---

### Step 4: Register Android App & OAuth Client

1. In Firebase Console, go to **Project Settings** (gear icon ⚙️ > *Project settings*).
2. Under the *Your apps* section, click the **Android** icon (Add app).
3. Enter the package details:
   - **Android package name**: `org.swiftapps.swiftbackup` *(Tap "Copy Package" in SwiftBackupPrem wizard)*
   - **Debug signing certificate SHA-1**: Paste your Swift Backup app SHA-1 fingerprint *(Tap "Copy Fingerprint" in SwiftBackupPrem wizard)*
4. Click **Register app**, then download the `google-services.json` file.
5. Click through the remaining setup steps until finished.

> [!IMPORTANT]
> **Custom URI Scheme Requirement (Google Cloud Console)**
> 
> Since October 2023, Google disables Custom URI schemes by default for new OAuth clients.
> 1. Open the [Google Cloud API Credentials Console](https://console.cloud.google.com/apis/credentials).
> 2. Select your Firebase project at the top.
> 3. Under **OAuth 2.0 Client IDs**, edit the auto-generated **Android client for org.swiftapps.swiftbackup**.
> 4. Ensure **Enable custom URI scheme** is checked / enabled.
> 
> <p align="center">
>   <img src="https://github.com/Juby210/SwiftBackupPrem/assets/31005896/8049f7e2-26db-418b-9611-171be77b61f1" alt="Enable custom URI scheme" width="600" />
> </p>
> 
> 5. Copy the **Client ID** string (e.g., `xxxxxxxxxxxx-xxxxxxxxxxxxxxxx.apps.googleusercontent.com`).

---

### Step 5: Enable Google Drive API

If you plan to use Google Drive for cloud backups:

1. Visit the [Google Cloud Drive API Console](https://console.cloud.google.com/apis/library/drive.googleapis.com).
2. Select your Firebase project.
3. Click **Enable** to allow Swift Backup to interact with Google Drive via your project.

---

### Step 6: Import or Enter Credentials in SwiftBackupPrem

1. Open **SwiftBackupPrem** on your device.
2. Enable **Custom firebase app**.
3. Choose either method:
   - **Method A (Automatic)**: Tap **Import google-services.json** and pick the downloaded JSON file. Then paste your **OAuth Client ID** into the Client ID field.
   - **Method B (Manual Wizard)**: Follow the interactive 5-step guided wizard in the app to review and confirm all fields.
4. Tap **Finish & Save**.
5. Tap **Force Stop** at the bottom to kill any running Swift Backup instances, then tap **Open App**.
6. Sign in to Swift Backup with your Google account.

---

## 🔄 Backup, Export & Migration

### Configuration Import & Export
- **Export Config**: Tap the top-right menu (⋮) > **Export Config** to save your active configuration to a JSON file (`sbp_config.json`).
- **Import Config**: On a new device or fresh ROM install, tap **Import Config** to restore your settings in a single click.

### Automatic Backup Archiving
When Swift Backup performs an APK backup, SwiftBackupPrem automatically copies:
- The currently installed `SwiftBackupPrem.apk`
- The active `google-services.json` configuration

into your backup directory under `.../sbp/` so your module and setup are preserved with your backups.

---

## 🛠️ Building from Source

### Prerequisites
- JDK 17 or higher
- Android SDK with Platform 37 (`compileSdk 37`)
- Android NDK (`25.1.8937393` or higher) & CMake `3.22.1+`

### Build Steps

1. Clone the repository:
   ```bash
   git clone https://github.com/s1ddhants1/SwiftBackupPrem.git
   cd SwiftBackupPrem
   ```

2. Build debug APK:
   ```bash
   ./gradlew assembleDebug
   ```

3. Build optimized release APK:
   ```bash
   ./gradlew assembleRelease
   ```

The built APK will be located in `app/build/outputs/apk/release/app-release.apk`.

---

## ❓ Frequently Asked Questions (FAQ)

<details>
<summary><b>Q: I see "LSPosed Module Not Enabled" in the app.</b></summary>
<p>Make sure you have enabled SwiftBackupPrem inside LSPosed Manager, added Swift Backup (<code>org.swiftapps.swiftbackup</code>) to the module scope, and rebooted your device.</p>
</details>

<details>
<summary><b>Q: Google Sign-In gives Error Code 10 / 12500.</b></summary>
<p>This indicates an OAuth mismatch or missing permission:</p>
<ol>
  <li>Verify that the <b>SHA-1 fingerprint</b> entered in Firebase Console matches your installed Swift Backup app (use the "Copy Fingerprint" button in SwiftBackupPrem).</li>
  <li>Ensure <b>Enable custom URI scheme</b> is checked in Google Cloud Console OAuth Client settings.</li>
  <li>Ensure <b>Google Drive API</b> is enabled in Google Cloud Console if using Google Drive cloud backup.</li>
  <li>Wait 5–10 minutes after creating OAuth credentials for Google's servers to propagate changes.</li>
</ol>
</details>

<details>
<summary><b>Q: Do I need a paid Firebase Blaze plan for Cloud Storage?</b></summary>
<p>No! The free <b>Spark plan</b> is 100% sufficient. Swift Backup only utilizes Firebase for authentication and metadata. You can leave Cloud Storage unconfigured.</p>
</details>

<details>
<summary><b>Q: How does DexKit work in this module?</b></summary>
<p>Swift Backup obfuscates its classes with ProGuard/R8 across different releases. Instead of hardcoding static class names that break on every update, DexKit inspects bytecode structures dynamically at runtime to locate the required hooks automatically.</p>
</details>

---

## 🤝 Credits & Acknowledgements

- **[Juby210](https://github.com/Juby210)** — Original creator and author of SwiftBackupPrem.
- **[s1ddhants1](https://github.com/s1ddhants1)** — Maintainer, Jetpack Compose Material 3 UI overhaul, guided setup wizard, and modernizations.
- **[LuckyPray/DexKit](https://github.com/LuckyPray/DexKit)** — Powerful runtime DEX search and hooking engine.
- **[LSPosed](https://github.com/LSPosed/LSPosed)** — ART hooking framework for modern Android.

---

## ⚖️ License & Disclaimer

This project is licensed under the [MIT License](LICENSE).

**Disclaimer**: This project is intended strictly for personal, educational, and backup management purposes. Swift Backup is developed by SwiftApps. If you enjoy Swift Backup, consider supporting the official developers.

