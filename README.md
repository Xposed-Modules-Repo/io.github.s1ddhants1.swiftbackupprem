# ⚡ SwiftBackupPrem

<p align="center">
  <b>An advanced Xposed / LSPosed module for <a href="https://play.google.com/store/apps/details?id=org.swiftapps.swiftbackup">Swift Backup</a> that unlocks Premium features and enables isolated, custom Firebase backend integration.</b>
</p>

<p align="center">
  <a href="https://github.com/s1ddhants1/SwiftBackupPrem/actions/workflows/ci.yml"><img src="https://img.shields.io/github/actions/workflow/status/s1ddhants1/SwiftBackupPrem/ci.yml?style=for-the-badge&logo=githubactions&logoColor=white&label=CI" alt="CI Status" /></a>
  <a href="https://github.com/s1ddhants1/SwiftBackupPrem/releases"><img src="https://img.shields.io/github/v/release/s1ddhants1/SwiftBackupPrem?style=for-the-badge&color=6366f1&logo=android" alt="Release Version" /></a>
  <a href="https://t.me/SwiftBackupPrem"><img src="https://img.shields.io/badge/Telegram-Join%20Chat-2CA5E0?style=for-the-badge&logo=telegram&logoColor=white" alt="Telegram Support Group" /></a>
  <a href="https://github.com/s1ddhants1/SwiftBackupPrem/blob/main/LICENSE"><img src="https://img.shields.io/github/license/s1ddhants1/SwiftBackupPrem?style=for-the-badge&color=10b981" alt="License" /></a>
  <img src="https://img.shields.io/badge/Android-8.1%2B%20(API%2027--37)-f59e0b?style=for-the-badge&logo=android" alt="Android API Support" />
  <img src="https://img.shields.io/badge/Framework-LSPosed%20%2F%20Xposed-8b5cf6?style=for-the-badge" alt="Xposed / LSPosed" />
  <img src="https://img.shields.io/badge/UI-Jetpack%20Compose%20M3-06b6d4?style=for-the-badge&logo=jetpackcompose" alt="Jetpack Compose Material 3" />
</p>

---

## Table of Contents

- [Features](#-features)
- [Compatibility & Prerequisites](#-compatibility--prerequisites)
- [Installation & Activation](#-installation--activation)
- [Custom Firebase Setup Guide](#-custom-firebase-setup-guide)
  - [Why Use Your Own Firebase Instance?](#why-use-your-own-firebase-instance)
  - [Step 1: Create a Firebase Project](#step-1-create-a-firebase-project)
  - [Step 2: Set Up Realtime Database & Security Rules](#step-2-set-up-realtime-database--security-rules)
  - [Step 3: Configure Authentication](#step-3-configure-authentication)
  - [Step 4: Register Android App & OAuth Client](#step-4-register-android-app--oauth-client)
  - [Step 5: Enable Google Drive API & OAuth Scopes](#step-5-enable-google-drive-api--oauth-scopes)
  - [Step 6: Import or Enter Credentials in SwiftBackupPrem](#step-6-import-or-enter-credentials-in-swiftbackupprem)
- [Guide: Migrating & Accessing Backups from Default Firebase](#guide-migrating--accessing-backups-from-default-firebase)
- [Automated Backup Rebuild & Restore](#automated-backup-rebuild--restore)
- [Configuration Export & Migration](#configuration-export--migration)
- [Building from Source](#building-from-source)
- [Community & Support](#community--support)
- [Frequently Asked Questions (FAQ)](#frequently-asked-questions-faq)
- [Credits & Acknowledgements](#credits--acknowledgements)
- [License & Disclaimer](#license--disclaimer)

---

## Features

- **Premium Toggle & Unlocking**: Enables all Swift Backup Premium functionality without needing Google Play Store licensing.
- **Disable Telemetry & Tracking**: Blocks Firebase Analytics, Crashlytics, Sessions, Installations, and Google DataTransport tracking calls for maximum privacy.
- **Custom Firebase Backend (Anti-Ban & Privacy)**: Directs Swift Backup to use your personal Firebase instance for user authentication and cloud synchronization metadata.
- **Google Drive Full Access & Cloud Restore**: Upgrades Google Drive OAuth scopes to discover backups across accounts, automatically fetches & decodes cloud `.extra` metadata, and indexes cloud backups without relying on original Firebase catalog state.

### Additional features

- **Modern Material 3 UI**: Clean user interface built with Jetpack Compose, Material 3 design guidelines, edge-to-edge display, dynamic theme adaptation, and responsive layouts.
- **Guided Custom Firebase Setup**: In-app Setup with 1-click clipboard helpers (package name, SHA-1 signing fingerprint) and helpful links to [Firebase](https://firebase.google.com/) and [Google Cloud](https://console.cloud.google.com/).
- **One-Tap JSON Import**: Automatically parses and fills credentials directly from `google-services.json` files.
- **Configuration Export & Import**: Easily backup or migrate your setup across devices using `sbp_config.json`.
- **Quick Process Controls**: One-tap Root Force Stop and Launch shortcuts directly inside the app.

## How it works

- **Dynamic DexKit Bytecode Scanning**: Utilizes [DexKit](https://github.com/LuckyPray/DexKit) to dynamically locate obfuscated classes and methods at runtime across versions, ensuring compatibility with newer app updates.

## Compatibility & Prerequisites

| Requirement                 | Details                                                                                                                                                                                                                                                                                                 |
| :-------------------------- | :------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------ |
| **Root Solution**           | [Magisk](https://github.com/topjohnwu/Magisk), [KernelSU](https://github.com/tiann/KernelSU), or [APatch](https://github.com/bmax121/APatch)                                                                                                                                                            |
| **Xposed / Hook Framework** | Modern [LibXposed](https://github.com/libxposed) (API 101 / 102+) compatible frameworks:<br>• [LSPosed](https://github.com/LSPosed/LSPosed) (v2.0.0+)<br>• [Vector](https://github.com/JingMatrix/Vector)<br>• LSPosed variants (LSPosed-Irena, etc)<br>• Other LibXposed-compliant ART hooking loaders |
| **Android Version**         | Android 8.1 (API 27) up to Android 17 (API 37+)                                                                                                                                                                                                                                                         |
| **Target Application**      | [Swift Backup](https://play.google.com/store/apps/details?id=org.swiftapps.swiftbackup) (`org.swiftapps.swiftbackup`)                                                                                                                                                                                   |
| **Supported App Versions**  | v4.2.3, v4.2.5, v5.0.4, v5.1.0, and newer releases                                                                                                                                                                                                                                                      |

---

## Installation & Activation

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

### Module Activation (LSPosed / LibXposed)

1. Open **LSPosed Manager** (or your active LibXposed framework manager).
2. Navigate to the **Modules** tab and tap **SwiftBackupPrem**.
3. Toggle **Enable module**.
4. Ensure the scope includes **Swift Backup** (`org.swiftapps.swiftbackup`).
5. Open the **SwiftBackupPrem** app to configure custom Firebase credentials (recommended) or launch Swift Backup directly.

---

## Custom Firebase Setup Guide

### Why Use Your Own Firebase Instance?

By default, Swift Backup authenticates against the app developer's Firebase project. If the developer blocks or bans your account on their Firebase instance then access to the account will be revoked and you will not be able to restore your backups.

Connecting your own personal Firebase project gives you complete isolation, ensures data privacy, and prevents remote bans.

---

### Step 1: Create a Firebase Project

1. Go to the [Firebase Console](https://console.firebase.google.com/).
2. Click **Add project** (or **Create a project**).
3. Enter a project name (e.g., `SwiftBackup-Personal`) and continue.
4. Google Analytics can be disabled (optional) to speed up creation.
5. Click **Create project** and wait for provisioning to finish.

<details>
<summary>View Step 1 Screenshots</summary>
<br>
<p align="center">
  <img src="Screenshots/step1_01_project_name_prompt.webp" alt="Enter project name" width="700" /><br>
  <em>1. Enter project name</em><br><br>
  <img src="Screenshots/step1_02_project_name_entered.webp" alt="Confirm project name" width="700" /><br>
  <em>2. Confirm project name</em><br><br>
  <img src="Screenshots/step1_03_google_analytics_toggle.webp" alt="Google Analytics option" width="700" /><br>
  <em>3. Configure Analytics and click Create project</em><br><br>
  <img src="Screenshots/step1_04_project_provisioning.webp" alt="Provisioning project" width="700" /><br>
  <em>4. Provisioning Firebase project</em><br><br>
  <img src="Screenshots/step1_05_project_ready.webp" alt="Project ready" width="700" /><br>
  <em>5. Project is ready</em>
</p>
</details>

---

### Step 2: Set Up Realtime Database & Security Rules

1. In your Firebase project sidebar, go to **Databases & Storage > Realtime Database**.
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
6. Copy your **Realtime Database URL** from the _Data_ tab (e.g., `https://your-project-id-default-rtdb.firebaseio.com/`).

<details>
<summary>View Step 2 Screenshots</summary>
<br>
<p align="center">
  <img src="Screenshots/step2_01_navigate_realtime_database.webp" alt="Navigate to Realtime Database" width="700" /><br>
  <em>1. Select Databases &amp; Storage &gt; Realtime Database</em><br><br>
  <img src="Screenshots/step2_02_database_location.webp" alt="Select Database Location" width="700" /><br>
  <em>2. Choose database region / location</em><br><br>
  <img src="Screenshots/step2_03_start_locked_mode.webp" alt="Start in locked mode" width="700" /><br>
  <em>3. Select Start in locked mode and Enable</em><br><br>
  <img src="Screenshots/step2_04_rules_tab_default.webp" alt="Rules tab default" width="700" /><br>
  <em>4. Switch to the Rules tab</em><br><br>
  <img src="Screenshots/step2_05_rules_paste_and_publish.webp" alt="Paste security rules" width="700" /><br>
  <em>5. Replace rules and click Publish</em><br><br>
  <img src="Screenshots/step2_06_rules_published_success.webp" alt="Rules published" width="700" /><br>
  <em>6. Security rules published successfully</em><br><br>
  <img src="Screenshots/step2_07_database_url_copy.webp" alt="Copy Database URL" width="700" /><br>
  <em>7. Copy your Realtime Database URL from the Data tab</em>
</p>
</details>

---

### Step 3: Configure Authentication

1. In the Firebase sidebar, navigate to **Security > Authentication**.
2. Select the **Sign-in method** tab.
3. Under _Additional providers_, select **Google**.
4. Toggle **Enable**, enter a **Public-facing name for project** of your choice, choose a **Project support email**, and click **Save**.

<details>
<summary>View Step 3 Screenshots</summary>
<br>
<p align="center">
  <img src="Screenshots/step3_01_navigate_authentication.webp" alt="Select Google Sign-in provider" width="700" /><br>
  <em>1. Navigate to Authentication &gt; Sign-in method and select Google</em><br><br>
  <img src="Screenshots/step3_02_google_provider_dialog.webp" alt="Configure Google Sign-in dialog" width="700" /><br>
  <em>2. Google Sign-in provider configuration dialog</em><br><br>
  <img src="Screenshots/step3_03_enable_google_provider.webp" alt="Enable Google provider" width="700" /><br>
  <em>3. Toggle Enable, enter public-facing name and support email, then Save</em><br><br>
  <img src="Screenshots/step3_04_google_provider_enabled.webp" alt="Google Sign-in Enabled" width="700" /><br>
  <em>4. Google provider enabled successfully</em>
</p>
</details>

---

### Step 4: Register Android App & OAuth Client

#### 1. Register Android App in Firebase Console

1. In Firebase Console, go to the **Project Overview** page from the sidebar.
2. Under "Select a platform to get started", click the **Android** icon (Add app).
3. Enter the package details:
   - **Android package name**: `org.swiftapps.swiftbackup` _(or tap "Copy Package" in SwiftBackupPrem custom firebase setup screen)_
   - **App Nickname**: `SwiftBackupPersonal` _(or any name you like)_
4. Click **Register app**.
5. Click **Download google-services.json** to save the configuration file.
6. Click **Next** through the remaining setup steps, then click **Continue to console**.
7. On the Project Overview page, click the newly registered app card and select the **Gear icon (Project Settings)**.
8. Scroll down to the **Your apps** section, click **Add fingerprint**, paste your **SHA-1 fingerprint** _(tap "Copy Fingerprint" in the custom firebase setup screen from the module)_, and click **Save**.
9. _(Optional)_ Click the **Data privacy** tab on Project Settings and uncheck **Firebase Service Data Sharing**.

<details>
<summary>View Firebase App Registration Screenshots</summary>
<br>
<p align="center">
  <img src="Screenshots/step4_01_add_android_app.webp" alt="Add Android App" width="700" /><br>
  <em>1. Click the Android platform icon on Project Overview</em><br><br>
  <img src="Screenshots/step4_02_register_app_details.webp" alt="Register Android App" width="700" /><br>
  <em>2. Enter package name org.swiftapps.swiftbackup and nickname</em><br><br>
  <img src="Screenshots/step4_03_download_google_services_json.webp" alt="Download google-services.json" width="700" /><br>
  <em>3. Download google-services.json configuration file</em><br><br>
  <img src="Screenshots/step4_04_continue_to_console.webp" alt="Continue to console" width="700" /><br>
  <em>4. Skip SDK setup and continue to console</em><br><br>
  <img src="Screenshots/step4_06_project_overview_app_card.webp" alt="App registered on Overview" width="700" /><br>
  <em>5. App is registered on Project Overview</em><br><br>
  <img src="Screenshots/step4_07_open_project_settings.webp" alt="Open Project Settings" width="700" /><br>
  <em>6. Click the gear icon to open Project Settings</em><br><br>
  <img src="Screenshots/step4_08_project_settings_general.webp" alt="Project Settings General Tab" width="700" /><br>
  <em>7. Project Settings Overview (View in Google Cloud)</em><br><br>
  <img src="Screenshots/step4_09_add_sha1_fingerprint.webp" alt="Add SHA-1 fingerprint" width="700" /><br>
  <em>8. Add SHA-1 fingerprint under Your apps</em><br><br>
  <img src="Screenshots/step4_10_disable_data_sharing.webp" alt="Disable Data Sharing" width="700" /><br>
  <em>9. Optional: Disable Firebase Service Data Sharing</em>
</p>
</details>

#### 2. Configure OAuth 2.0 Client in Google Cloud Console

1. Open the [Google Cloud API Credentials Console](https://console.cloud.google.com/apis/credentials) (or click **View in Google Cloud** on the Firebase Project Settings page).
2. Ensure your Firebase / Google Cloud project is selected in the top project dropdown.
3. In the sidebar, navigate to **APIs & Services > Credentials**.
4. Under **OAuth 2.0 Client IDs**, edit the auto-generated **Android client for org.swiftapps.swiftbackup**
5. Under **Advanced settings**, check **Enable custom URI scheme** (click **Yes** in the confirmation popup).
6. Click **Save**.
7. Copy the generated **Client ID** string (e.g., `xxxxxxxxxxxx-xxxxxxxxxxxxxxxx.apps.googleusercontent.com`).
<details>
<summary>View Google Cloud OAuth Client Screenshots</summary>
<br>
<p align="center">
  <img src="Screenshots/step4_11_gcp_dashboard.webp" alt="Google Cloud Console Dashboard" width="700" /><br>
  <em>1. Google Cloud Console Dashboard</em><br><br>
  <img src="Screenshots/step4_12_gcp_navigate_credentials.webp" alt="Navigate to Credentials" width="700" /><br>
  <em>2. Navigate to APIs &amp; Services &gt; Credentials</em><br><br>
  <img src="Screenshots/step4_13_gcp_edit_oauth_client.webp" alt="Edit Android OAuth Client" width="700" /><br>
  <em>3. Under OAuth 2.0 Client IDs, edit the auto-generated Android client</em><br><br>
  <img src="Screenshots/step4_15_gcp_enable_custom_uri_scheme.webp" alt="Enable Custom URI Scheme" width="700" /><br>
  <em>4. Under Advanced settings, enable Custom URI scheme</em><br><br>
  <img src="Screenshots/step4_16_gcp_copy_client_id.webp" alt="Copy Client ID" width="700" /><br>
  <em>5. Copy your generated OAuth Client ID</em>
</p>
</details>

---

### Step 5: Enable Google Drive API & OAuth Scopes

If you plan to use Google Drive for cloud backups:

#### 1. Enable Google Drive API

1. Visit the [Google Cloud Drive API Console](https://console.cloud.google.com/apis/library/drive.googleapis.com).
2. Select your Firebase / Google Cloud project at the top.
3. Click **Enable** to allow Swift Backup to interact with Google Drive via your project.

#### 2. Add Google Drive OAuth Scope

1. Open the [Google Cloud OAuth Scopes Console](https://console.cloud.google.com/auth/scopes).
2. Ensure your project is selected at the top.
3. Navigate to **Data Access** > click **Add or remove scopes**.
4. In the filter box, search for and enable:
   ```
   https://www.googleapis.com/auth/drive.file
   ```
   _(This provides safe, per-file access for files created or opened by Swift Backup without requiring broad drive permissions)._
5. Click **Update**, then click **Save** (or **Save and continue**).

---

### Step 6: Import or Enter Credentials in SwiftBackupPrem

1. Open **SwiftBackupPrem** on your device.
2. Enable **Custom firebase app**.
3. Choose either method:
   - **Method A (Automatic)**: Tap **Import google-services.json** and pick the downloaded JSON file. Then paste your **OAuth Client ID** into the Client ID field.
   - **Method B (Manual Wizard)**: Follow the guided setup in the app to review and confirm all fields.
4. Tap **Finish & Save**.
5. Tap **Force Stop** at the bottom to kill any running Swift Backup instances, then tap **Open App**.
6. Sign in to Swift Backup with your Google account.

> [!NOTE]
> **Firebase Cloud Storage is Optional (Skip if on Spark Plan)**
>
> Firebase Cloud Storage now requires a paid **Blaze Plan** (linked Cloud Billing account). **You can safely skip enabling Cloud Storage in Firebase Console.**
> Swift Backup does **not** store your backup files (APKs, app data, call logs) inside Firebase Storage. Firebase is only used for authentication and metadata synchronization. Your actual backups are stored in your configured cloud provider (Google Drive, WebDAV, Nextcloud, SMB, etc.) or local storage.

---

## Migrating & Accessing Backups from Default Firebase

Install Swift Backup with default Firebase and login to pull your UID from `/data/data/org.swiftapps.swiftbackup/shared_prefs/com.google.firebase.auth.api.Store.*.xml`:

```bash
su -c 'grep -o "GET_TOKEN_RESPONSE\.[^\"]*" /data/data/org.swiftapps.swiftbackup/shared_prefs/com.google.firebase.auth.api.Store.*.xml | cut -d. -f2'
```

> [!IMPORTANT]
> If your account has been banned by the developer of SwiftBackup then you will not be able to fetch your UID if you uninstalled the app post ban as the /data/data/org.swiftapps.swiftbackup/shared_prefs/ directory will be deleted and hence all the cloud backups are not decryptable and thus lost.
> This is the only way to fetch your UID so don't uninstall the app until you have your UID saved safely somewhere.

### Verify UID with Backups (optional)

Swift Backup takes the MD5 hash of your Firebase UID and uses the first 16 characters for the folder name:

```bash
echo -n "example uid" | md5sum | cut -c 1-16
```

**Output**: `example16char` -> `/sdcard/SwiftBackup/accounts/example16char/`  
or in case of cloud folder: `Swift Backup (example16char)`

---

### Create the User with this UID in your Custom Firebase Project

1. Install Firebase tools using your preferred package manager (e.g., via Node.js):

   ```bash
   npm install -g firebase-tools
   ```

2. Run `firebase login` to connect your account:

   ```bash
   firebase login
   ```

3. Create a `users.json` file:

   ```json
   {
     "users": [
       {
         "localId": "example uid",
         "email": "examplemail@gmail.com",
         "emailVerified": true,
         "displayName": "examplename"
       }
     ]
   }
   ```

4. View your Project ID

   ```bash
   firebase projects:list
   ```

5. Run the import command:

   ```bash
   firebase auth:import users.json --project exampleprojectID
   ```

6. Re-login in Swift Backup.

---

## Automated Backup Rebuild & Restore

When the **Cloud Backup Restore** toggle is turned on in SwiftBackupPrem, the module enables automated cloud backup restoration:

1. **Direct In-App Cloud Restore**: Indexes all backups directly from your cloud folder, decodes `.extra` metadata files and indexes them in real-time across the app (Single App Details, Cloud Sync tab, and Batch Restore).
2. **Local Cloud Backup Rebuild**: When cloud backup files (`.app`, `.dat`, `.splits`, `.extdat`, `.extra`) are downloaded or copied to your device storage:
   - Swift Backup typically requires a `<packageName>.xml` metadata file which is absent in raw cloud files.
   - The module detects missing metadata, decrypts the `.extra` payload on the fly using your active Firebase UID key (Conceal AES-GCM-256 + Zstandard), and generates the `<packageName>.xml`.
   - The backups immediately appear with all components (APK, App Data, External Data, Splits) and are 100% restorable.

> [!WARNING]
> **Scope Disclaimer & Security Notice**:
>
> - Standard Swift Backup operates under the safe, per-file `https://www.googleapis.com/auth/drive.file` scope configured in [Step 5](#step-5-enable-google-drive-api--oauth-scopes).
> - Enabling **Google Drive Full Access & Cloud Restore** dynamically expands the runtime OAuth scope to `https://www.googleapis.com/auth/drive` (Full Drive Access) in order to query, discover, and index backups created across different devices, past Firebase projects, or manual cloud transfers.
> - Because `.../auth/drive` is classified as a sensitive scope by Google, Google may display a standard _"Google hasn't verified this app"_ warning during initial sign-in. This is expected for personal developer projects—click **Advanced > Go to Swift Backup (unsafe)** to proceed safely.

---

## Configuration Export & Migration

- **Export Config**: Tap the top-right menu (⋮) > **Export Config** to save your active configuration to a JSON file (`sbp_config.json`).
- **Import Config**: On a new device or fresh ROM install, tap **Import Config** to restore your settings in a single click.

---

## Building from Source

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

## Community & Support

Join the official Telegram group for discussion, support, release updates, and assistance with Custom Firebase configuration:

<p align="center">
  <a href="https://t.me/SwiftBackupPrem">
    <img src="https://img.shields.io/badge/Join%20Telegram%20Group-SwiftBackupPrem-2CA5E0?style=for-the-badge&logo=telegram&logoColor=white" alt="Join Telegram Group" />
  </a>
</p>

- **Group Link**: [https://t.me/SwiftBackupPrem](https://t.me/SwiftBackupPrem)
- **Get Help**: Ask troubleshooting questions or share setup tips.
- **Releases & APKs**: Get direct download links and release notifications directly inside Telegram.

---

## Frequently Asked Questions (FAQ)

<details>
<summary><b>Q: I see "LSPosed Module Not Enabled" in the app.</b></summary>
<p>Make sure you have:</p>
<ol>
  <li>Enabled <b>SwiftBackupPrem</b> inside LSPosed Manager.</li>
  <li>Added <b>Swift Backup</b> (<code>org.swiftapps.swiftbackup</code>) to the module scope.</li>
  <li>Force stopped Swift Backup or rebooted your device.</li>
</ol>
</details>

<details>
<summary><b>Q: Why do I need a Custom Firebase project? Will I get banned without it?</b></summary>
<p>By default, Swift Backup authenticates with the official developer's Firebase backend. The official server performs periodic license checks, anti-tampering verification, and telemetry detection. If unauthorized or modified app usage is detected, the developer can disable/ban your account on their Firebase instance, revoking your access to Swift Backup and your backup metadata.</p>
<p>Connecting your own personal Firebase backend gives you <b>100% isolation</b>: authentication and metadata stay on your private cloud where no external server can revoke your account.</p>
</details>

<details>
<summary><b>Q: Do I need a paid Firebase Blaze plan or Cloud Storage?</b></summary>
<p><b>No!</b> The 100% free <b>Firebase Spark plan</b> is completely sufficient. Swift Backup only utilizes Firebase Authentication and Realtime Database for account identity and sync metadata. Your actual backup archives (APKs, app data, etc.) are stored on your personal cloud provider (e.g., Google Drive, WebDAV, Nextcloud), not Firebase Storage.</p>
</details>

<details>
<summary><b>Q: Google Sign-In fails with Error Code 10 or Error Code 12500.</b></summary>
<p>This indicates an OAuth mismatch, incorrect Client ID, or missing API configuration:</p>
<ol>
  <li><b>Check Client ID Type:</b> In SwiftBackupPrem settings, ensure you entered the <b>Android OAuth Client ID</b>, not the Web Client ID.</li>
  <li><b>Verify SHA-1 Fingerprint:</b> Use the <b>Copy Fingerprint</b> helper in SwiftBackupPrem's Guided Setup and ensure it matches the SHA-1 added to your Android OAuth Client and Firebase Android App settings.</li>
  <li><b>Custom URI Scheme:</b> Ensure <b>Enable custom URI scheme</b> is checked in Google Cloud Console > Credentials > Android OAuth Client.</li>
  <li><b>Enable Google Drive API:</b> Verify that <b>Google Drive API</b> is enabled under APIs &amp; Services in Google Cloud Console.</li>
</ol>
</details>

<details>
<summary><b>Q: Google Drive shows "Google hasn't verified this app" during sign-in.</b></summary>
<p>This is expected. When <b>Google Drive Full Access &amp; Cloud Restore</b> is enabled, the module requests the full <code>https://www.googleapis.com/auth/drive</code> scope so Swift Backup can discover and rebuild backups created across past accounts or ROM installs.</p>
<p>Because your Google Cloud project is personal and unverified, Google shows a standard security notice. Click <b>Advanced > Go to Swift Backup (unsafe)</b> to proceed.</p>
</details>

<details>
<summary><b>Q: My account was banned on the default Firebase backend. Can I recover my old backups?</b></summary>
<p><b>Yes, provided you still have your old Firebase UID key.</b></p>
<p>Swift Backup encrypts backup archives (<code>.dat</code>, <code>.extra</code>) using AES-256-GCM + Zstandard with your Firebase <code>UID</code> as the decryption key. If you extract your old UID (see the <a href="#migrating--accessing-backups-from-default-firebase">Migration Guide</a>) and create a user with that exact same UID in your Custom Firebase project, SwiftBackupPrem will be able to decrypt and restore all your previous backups.</p>
</details>

<details>
<summary><b>Q: I uninstalled or wiped Swift Backup after being banned. Can I still recover my old backups?</b></summary>
<p>Unfortunately, <b>no</b>. When you uninstall or clear data for Swift Backup, the local <code>/data/data/org.swiftapps.swiftbackup/shared_prefs/</code> directory containing the cached Firebase authentication token is deleted. Because the official server has disabled your account, you cannot log in to retrieve the original UID. Without the original UID key, the AES-256-GCM encrypted data cannot be decrypted.</p>
<p><i>Recommendation: Always backup your Firebase UID or export your SwiftBackupPrem configuration (<code>sbp_config.json</code>) to safe storage.</i></p>
</details>

<details>
<summary><b>Q: How do I verify that my extracted UID matches my backup folder?</b></summary>
<p>Compute the MD5 hash of your raw UID string (for example, using an online MD5 tool or <code>echo -n "YOUR_UID" | md5sum</code>). Compare the <b>first 16 hexadecimal characters</b> of the MD5 hash with the name of the backup folder on your storage or Google Drive. If they match, you have the exact UID needed to restore those backups.</p>
</details>

<details>
<summary><b>Q: <code>firebase auth:import</code> fails with "No hash algorithm specified" or project error.</b></summary>
<p>Ensure you run <code>firebase projects:list</code> to obtain your exact <b>Project ID</b> (not the display name). Use:</p>
<pre><code class="language-bash">firebase auth:import users.json --project YOUR_PROJECT_ID
</code></pre>
<p>Alternatively, you can create the user using the Firebase Admin Python SDK script provided in the Migration Guide.</p>
</details>

<details>
<summary><b>Q: Why do uninstalled apps show as package names (e.g. <code>com.whatsapp</code>) or missing icons during Cloud Restore?</b></summary>
<p>When backups are indexed directly from cloud metadata files (<code>.extra</code>) for apps not currently installed on your device, Swift Backup falls back to displaying the package identifier recorded in the backup headers. Once restored or installed locally, Android resolves the full display name and application icon normally.</p>
</details>

<details>
<summary><b>Q: Does Cloud Backup Restore support Call Logs, SMS, and Wallpapers?</b></summary>
<p>The automated Cloud Backup Rebuild and Restore engine specifically decodes and decrypts application archives (<code>.app</code>, <code>.dat</code>, <code>.splits</code>, <code>.extdat</code>, <code>.extra</code>). System data like Call Logs, SMS, and Wallpapers are restored through standard local backup paths.</p>
</details>

<details>
<summary><b>Q: Can SwiftBackupPrem be used with cloned or work profile instances of Swift Backup?</b></summary>
<p>Yes. Ensure the LSPosed module scope covers the cloned instance or secondary user profile, and verify that root access and storage permissions are properly granted to that profile space.</p>
</details>

<details>
<summary><b>Q: How does DexKit work in this module?</b></summary>
<p>Swift Backup obfuscates its classes with ProGuard/R8 across different releases. Instead of hardcoding static class names and signatures that break on every update, DexKit inspects bytecode structures dynamically at runtime to locate the required hooks automatically and caches the results for optimal performance.</p>
</details>

---

## Credits & Acknowledgements

- **[Juby210](https://github.com/Juby210)** — Original creator and author of SwiftBackupPrem.
- **[s1ddhants1](https://github.com/s1ddhants1)** — Maintainer
- **[LuckyPray/DexKit](https://github.com/LuckyPray/DexKit)** — Powerful runtime DEX search and hooking engine.
- **[LSPosed](https://github.com/LSPosed/LSPosed)** — ART hooking framework for modern Android.

---

## License & Disclaimer

This project is licensed under the [MIT License](LICENSE).

**Disclaimer**: This project is intended strictly for personal, educational, and backup management purposes. Swift Backup is developed by SwiftApps. If you enjoy Swift Backup, consider supporting the official developers.
