# Input Control

Input Control 1.5 turns an Android tablet into a remote touchpad, keyboard and media controller for a Windows 11 PC. It is designed for people who want to operate their own PC from a tablet on the same local network. Install the tablet app and the Windows Companion together.

## Contents

- [Project overview](#project-overview)
- [System requirements](#system-requirements)
- [Required downloads](#required-downloads)
- [Step-by-step installation](#step-by-step-installation)
- [Device connection guide](#device-connection-guide)
- [How to use Input Control](#how-to-use-input-control)
- [Configuration](#configuration)
- [Troubleshooting](#troubleshooting)
- [Developer guide](#developer-guide)
- [Frequently asked questions](#frequently-asked-questions)

## Project overview

Swipe on the tablet to move the Windows pointer, tap to click, or switch to the on-screen keyboard for English QWERTY, Korean 두벌식, symbols, a numpad and function keys. Control also shows pinned Windows apps, shortcuts for the foreground app and a media panel. Use the Audio screen for the default Windows playback device's volume and mute state.

The tablet sends input over an authenticated, encrypted LAN connection. Windows applies input to the active desktop and sends connection, audio and supported app information back to the tablet. PC discovery uses local multicast. You can enter an address manually when discovery is unavailable. The PC's existing speakers, headphones or other active Windows playback endpoints are managed by Windows Sound settings.

## System requirements

| Component | Requirement | Evidence or limitation |
| --- | --- | --- |
| Tablet | Android 9 or newer (API 28+) with ARM64 (`arm64-v8a`) or x86-64 | App minimum and packaged ABIs. Existing screenshots are from a Galaxy Tab S8 Ultra. Other models require compatibility checks. |
| Controlled PC | Windows 11 x64, an interactive desktop and a normal Windows user account | Documented target and local build platform. Windows ARM64 and other desktop OS versions have no verified setup. |
| Build environment | Windows 11 x64 for this combined guide. Stable Rust with the MSVC toolchain, Visual Studio C++ Build Tools and Windows SDK for the receiver. JDK 25, Android SDK Platform 37, Build Tools 36.0.0, NDK 27.3.13750724 and SDK Platform-Tools for Android. | Gradle wrapper 9.6.0, Android Gradle Plugin 9.4.1, Kotlin Compose plugin 2.2.10. Android targets API 36 and compiles against 37. The NDK build script also selects macOS and Linux host directories. The alternate Android build hosts were not tested. |
| Runtime and drivers | Android runs the installed APK. The companion runs as a portable Windows EXE. The companion links the Microsoft C++ v14 runtime. Install the x64 redistributable if those DLLs are missing. | USB debugging may require the tablet maker's ADB driver on Windows. The Wi-Fi session requires no project-specific device driver or firmware. |
| Hardware | Touchscreen Android tablet and Windows PC on the same trusted IPv4 subnet. A USB data cable is useful for deploying the tablet app. | The project specifies no minimum RAM, storage or CPU speed beyond OS, ABI and build-tool requirements. |
| Network and permissions | Inbound TCP 47831 to the companion. UDP 5353 multicast for discovery. Android needs LAN access. Internet access is needed to fetch build tools and dependencies. | Running the companion uses a standard account. Adding Windows Firewall rules requires an administrator. Android 16 with restricted local networking can prompt for Nearby devices permission. |
| Connection methods | Wi-Fi LAN with automatic discovery or manual address entry. The PC can use an Ethernet connection on the same subnet. | Bluetooth HID is a placeholder. USB is for deployment and debugging. The project provides no serial or remote Internet control mode. |

## Required downloads

There are currently no packaged GitHub releases. Build both components from a full source copy. The initial GitHub commit contains the guide and its appearance screenshot. If you have the existing project folder, use it for the build steps below.

| Software / File | Purpose | Download Link | Required? |
| --- | --- | --- | --- |
| Input Control APK and Windows EXE | Tablet app and Windows receiver | [Build from source](#step-by-step-installation) | Yes |
| Android Studio and Android SDK | SDK Manager, Android tools and optional IDE deployment | [Android Studio](https://developer.android.com/studio) · [SDK Manager](https://developer.android.com/studio/intro/update#sdk-manager) | SDK packages required to build and install. The IDE is the simplest setup path. |
| JDK 25 for Windows x64 | Runs the project's Gradle daemon | [Eclipse Temurin JDK 25](https://adoptium.net/temurin/releases/?version=25) | Required for Android builds. Gradle can provision the daemon JDK if its configured download is reachable. |
| Stable Rust with Cargo and rustup | Builds the Windows receiver and the Android native libraries | [Rustup installer](https://rustup.rs/) | Yes for source builds |
| Visual Studio Build Tools with MSVC C++ and Windows SDK | Compiles the Windows native components and links the receiver | [Visual Studio downloads](https://visualstudio.microsoft.com/downloads/) | Required to build the receiver |
| Microsoft Visual C++ v14 Redistributable x64 | Supplies runtime DLLs on a Windows PC that needs them | [Microsoft x64 installer](https://aka.ms/vc14/vc_redist.x64.exe) | Conditional |
| Android OEM USB driver | Allows ADB to see a physical tablet when Windows needs a driver | [Android's manufacturer driver directory](https://developer.android.com/studio/run/oem-usb) | Conditional for USB deployment |
| Git for Windows | Clones source and manages contributions | [Git for Windows installation](https://git-scm.com/install/windows) | Required for Git workflows. An existing full source copy is sufficient to build. |

Gradle and Cargo fetch the declared libraries automatically. Use the checked-in wrapper and Cargo lockfiles.

## Step-by-step installation

### 1. Get the source and install the tools

Use your existing `Input_Control` source folder. Once the application source is published to `main`, users with repository access can clone it from PowerShell in the folder where they want to keep the project.

```powershell
git clone --branch main https://github.com/minsmakes/Input_Control.git
Set-Location .\Input_Control
```

1. Install Visual Studio **Build Tools**, select **Desktop development with C++**, and include the x64 MSVC tools and Windows SDK. Install Rust with the stable Windows MSVC toolchain and reopen PowerShell.
2. Open `Input_Control\android-app` in an Android Studio version that supports Android Gradle Plugin 9.4, such as Rabbit 1 or Rabbit 2. Under **Tools → SDK Manager**, install Android SDK Platform 37. Under **SDK Tools**, enable **Show Package Details** and install Build Tools **36.0.0**, **NDK (Side by side) 27.3.13750724** and **Android SDK Platform-Tools**. Copy the Android SDK location shown in the manager. [Android Studio and AGP compatibility](https://developer.android.com/studio/releases)
3. Install a JDK 25 or select it for Gradle in Android Studio. For command-line builds, set `JAVA_HOME` to its installation directory and `ANDROID_HOME` to the SDK location. The examples below apply to the current PowerShell session. Replace the JDK path and change the SDK path if yours differs.

```powershell
$env:JAVA_HOME = 'C:\path\to\your\jdk-25'
$env:ANDROID_HOME = Join-Path $env:LOCALAPPDATA 'Android\Sdk'
rustup target add aarch64-linux-android x86_64-linux-android
```

### 2. Build and start the Windows Companion

From PowerShell in the `Input_Control` repository root:

```powershell
cargo build --release --locked --manifest-path .\windows-receiver\Cargo.toml
.\windows-receiver\target\release\input-control-receiver.exe
```

The companion window shows the PC name and its listener address. Confirm that it says **Listening on** and record the IPv4 address and port. Double-clicking the built EXE also starts the companion. Install the optional Visual C++ runtime if Windows reports a missing runtime DLL. If updating a running copy, quit it from the tray before rebuilding in place, or use the staged update in the [Developer guide](#developer-guide).

### 3. Install and start the tablet app

1. On the tablet, find **Build number** under **About** or **Software information**, tap it seven times, then enable **USB debugging** in **Developer options**. Android menu names vary by manufacturer.
2. Connect the unlocked tablet to your development PC with a USB data cable and approve the debugging prompt for that PC. If Windows cannot see it, try a different data cable or USB port and check the OEM driver directory.
3. Use Android Studio's **Run** button with the **app** configuration and your tablet selected, or use the following PowerShell commands starting at the repository root. For more than one attached device, pick the intended tablet in Android Studio.

```powershell
Set-Location .\android-app
$adb = Join-Path $env:ANDROID_HOME 'platform-tools\adb.exe'
& $adb devices -l
.\gradlew.bat :app:installDebug
```

Check that the tablet is listed as `device` before installing. Gradle builds both native libraries and installs the debug APK. On the tablet, open **Input Control** from the app list and confirm that the header and **⋮** menu appear. Use an existing signing key when updating a copy whose preferences and saved pairing you want to keep. The USB cable can be removed after installation.

## Device connection guide

### Discover and pair a Windows PC

1. Connect the tablet to a trusted Wi-Fi network that can reach the PC on the same IPv4 subnet. Connect the PC using Wi-Fi or Ethernet on that subnet. Guest Wi-Fi may isolate devices. No pairing cable is needed.
2. Start the Windows Companion and check the listener address. On the tablet open **⋮ → Devices → Pair New Device**. The scan runs for about 12 seconds. If needed, tap **Rescan**.
3. Find your PC under **Wi-Fi Devices**. Verify the PC name and address against the companion, then tap **Pair**. The Windows window shows a pairing request with four groups of eight hexadecimal digits. Open the companion from its notification-area icon if it is hidden.
4. Compare the tablet's three groups with the first three groups on the PC. If they match, type the PC's fourth group into **Last 8 hex digits** on the tablet and tap **Confirm match**. Click **Approve** on the PC within the same 60-second request. Click **Reject** on the PC or **Cancel** on the tablet if the request is unfamiliar or the groups differ.
5. Verify that the tablet header says **Connected** with the PC name, and check that Windows shows the connected tablet. In **⋮ → Control**, move the PC cursor and type into an ordinary, non-elevated editor such as Notepad.
6. To reconnect later, select **Connect** beside the saved PC. **Rename** changes the tablet-side display label. A changed Wi-Fi address can require rediscovery or manual entry of the new address.

### Enter the address manually

1. In Windows, copy the listener IPv4 address. If the companion selected the wrong adapter, use `Get-NetIPConfiguration` in PowerShell, quit from the tray, and relaunch with the adapter's address as described under [Configuration](#configuration).
2. On the tablet open **Devices → Manual connection**. Fill **IP or hostname** with the PC's private IPv4 address or a hostname that resolves to it, leave **Port** at `47831`, then tap **Continue**. For example, a PC listening at `192.168.1.25:47831` uses host `192.168.1.25` and port `47831`. Substitute the address shown on your PC.
3. Complete the same first-pairing checks or wait for the saved connection to authenticate. Verify **Connected** and repeat the cursor and typing check.

### Disconnect, revoke access and exit

1. Tap **Disconnect** in **Devices** on Android or in the Windows Companion. The receiver attempts to release held keys and mouse buttons during cleanup. Android also disconnects when the app genuinely moves to the background. Activity recreation on rotation retains the session.
2. To revoke trust, select the tablet under **Trusted devices** in Windows, click **Remove** and confirm. On Android, use **Forget** beside the PC to erase its saved identity. Use both controls if you want a fresh pairing on both sides.
3. Leave the Android app after disconnecting. To stop the receiver, right-click the notification-area icon and select **Quit**. Closing its window normally hides it in the tray. Launching the EXE again opens the existing receiver window in the same Windows session.

## How to use Input Control

Open **⋮ → Control** after pairing. Switch between touchpad and keyboard using the header or the floating keyboard/mouse toggle. Drag the floating toggle to move it. The launcher and media panel remain available in either mode.

| Control | How to use it |
| --- | --- |
| Touchpad | Slide one finger to move the pointer. One tap left-clicks and two fingers tapping right-click by default. Drag two fingers vertically to scroll. Double-tap and hold the second contact to drag with the left button. Lift to drop. Spread or pinch two fingers to send Ctrl+wheel zoom steps to the active PC app. |
| English and Korean keyboard | Tap letters to type in the focused Windows app. Tap **한/영** for Korean 두벌식 and again for English. The keyboard toolbar shows the current Korean syllable. With compatible receivers, Windows displays a provisional character as it forms. Custom editors and those that intercept Backspace can handle this differently. |
| Symbols, numpad and function keys | Tap **?123** for punctuation and **ABC** to return. Hold a number for its shifted symbol. Use the keyboard menu for Numpad and F1–F12 if enabled in Settings. |
| Modifiers and navigation | Tap Shift for the next key or chord. Double-tap for Caps Lock and tap **⇪ Caps** to clear it. Use Ctrl, Alt and Win with other keys. The editing row includes Esc, Tab, Delete and arrows. Space and Enter are on the bottom row. Hold arrows to repeat. Ctrl+arrow moves by word in typical editors. One-shot Shift can extend the selection during an arrow repeat. Backspace repeats after about 400 ms and switches to word deletion after about 1.35 seconds when enabled. |
| Automatic keyboard | Focus an editable field on the PC while **Control** is visible. If both sides enable the feature and Windows exposes the field through UI Automation, the tablet opens the keyboard after the configured delay. It stays open on focus loss. A manual mode change takes precedence until a new editable-focus transition. |
| Launcher | Tap a pinned tile to open or activate the represented PC app. Choose **Edit…** to search installed apps and pin up to five. Use **↑** to reorder, **Remove** to unpin, and **Save** to apply. |
| Context shortcuts | In touchpad mode, open the gear next to the foreground app to edit a shortcut profile. Choose the profile, enter up to four lines such as `New tab = Ctrl+T`, then **Save**. **Restore defaults** restores that profile's preset. Shortcuts depend on the receiving Windows app. |
| Media panel | View the current Windows media session's title, creator, artwork and playback progress when the player supplies them. Use previous, play/pause and next for compatible players. Tap the speaker icon for a pop-up with volume, mute and playback controls. |
| Audio | Open **⋮ → Audio**. Drag the 0–100% slider and release to apply volume, or use **Decrease**, **Increase** and **Mute/Unmute**. View active playback endpoints and the default indicator. Change the default output on the PC in Windows Sound settings. |
| Shortcuts | Open **⋮ → Shortcuts** for media keys, Copy, Paste, Undo, Lock PC and Screenshot. The Lock PC and Screenshot tiles send the Windows key combinations shown on the buttons. **Open browser** is simulated. |
| Feedback | Supported buttons and keys briefly shrink visually when pressed and return to size on release or cancellation. Touch targets stay in place. Touchpad haptics can be turned off. Check connection state in the header and sent/confirmed events, RTT, receiver processing time and failures in **Devices**. |

[Themes and appearance screenshot](docs/screenshots/themes/appearance.png). The full source copy also includes tablet screenshots of the alphabet keyboard, symbols and touchpad under `docs/screenshots/`.

## Configuration

| Where | Options and defaults |
| --- | --- |
| Android → Settings → Input Layout | English default language, QWERTY layout display, automatic keyboard on with 350 ms focus debounce (150/350/700 ms), sustained Backspace word deletion on, numpad and function keys on. |
| Android → Settings → Gesture Mapping | Single tap left-click and two-finger tap right-click (each can be reassigned or disabled), two-finger drag scroll, double-tap drag on, pinch zoom on, zoom sensitivity 1.0. |
| Android → Settings → Touchpad | Pointer and scroll sensitivity 1.0, natural scrolling and haptic feedback on. Sensitivity sliders range from 0.25 to 3.0. |
| Android → Settings → Connection | Wi-Fi preference, auto-reconnect off. The Bluetooth preference has no working Bluetooth transport. Auto-reconnect can restore the last trusted session when returning to the app and retry once after a broken session. Manual disconnect clears the remembered reconnect target. |
| Android → Themes & Appearance | Modern Light for new installs, decorations on. Choose Modern Light, Dark Focus, Minimal Sand, Forest Camp or Tech Blue, set **Show Decorations** and tap **Apply**. Tap **Close** to dismiss the picker. The separate System appearance preset offers System, Light and Dark. |
| Android → Devices | Saved PC identity and endpoint, optional display label up to 40 Unicode code points, and per-PC dashboard pins and shortcut profiles. Device identities are created automatically and managed through Pair, Forget and Remove. |
| Windows Companion → Settings | Start at Windows sign-in off for a fresh setup, launch minimized off, close to notification area on, pairing notifications on, automatic keyboard activation on and System appearance. Settings are stored per Windows user. |
| Windows launch option | Automatic local IPv4 selection by default. Use `--bind` to choose an IPv4 address assigned to a local adapter. The receiver accepts directly attached IPv4 subnet peers. TCP port 47831 is fixed. |

From the repository root in PowerShell, use this example for a PC whose correct local address is `192.168.1.25`. Quit an existing companion first so the new invocation can start with the new address.

```powershell
Get-NetIPConfiguration
.\windows-receiver\target\release\input-control-receiver.exe --bind 192.168.1.25
```

There are no user-supplied keys or runtime configuration files to create. The Windows program uses `COMPUTERNAME` for its PC name and stores its user-scoped DPAPI-protected pairing data and logs under `%LOCALAPPDATA%\Input Control Receiver`. Android protects its private identity with Android Keystore and stores saved peers and preferences in app-private storage. Build-tool variables such as `JAVA_HOME` and `ANDROID_HOME` apply to development.

### Windows Firewall and Android permissions

Discovery uses mDNS/DNS-SD over UDP 5353 with service type `_inputcontrol._tcp.local.`. Control uses TCP 47831. Use the Windows **Private** network profile only on a network you trust. Keep the firewall enabled. If blocked, an administrator can add exact-executable rules from an elevated PowerShell at the repository root. Review existing rules for this EXE because an existing Public allowance also applies on that profile.

```powershell
$receiver = (Resolve-Path .\windows-receiver\target\release\input-control-receiver.exe).Path
New-NetFirewallRule -DisplayName 'Input Control receiver TCP' -Direction Inbound -Action Allow -Program $receiver -Profile Private -RemoteAddress LocalSubnet -Protocol TCP -LocalPort 47831
New-NetFirewallRule -DisplayName 'Input Control discovery mDNS' -Direction Inbound -Action Allow -Program $receiver -Profile Private -RemoteAddress LocalSubnet -Protocol UDP -LocalPort 5353
```

If you move the EXE, update the rules to the new path. Android normally grants LAN access through the declared Internet permission for this target SDK. If a restricted Android 16 device shows a permission error, try **Allow nearby devices** on the Devices screen or grant the permission in Android app settings. [Android local network permissions](https://developer.android.com/privacy-and-security/local-network-permission)

## Troubleshooting

The following are diagnostic steps based on the implementation. Firewall changes and physical-device fixes must be checked on your devices.

| Symptom | Likely cause | Diagnostic step and suggested fix |
| --- | --- | --- |
| Android build or install fails | Wrong JDK, SDK, NDK, Rust targets or unresolved dependency | Check `JAVA_HOME`, SDK Manager and `rustup target list --installed`. From `android-app`, run `.\gradlew.bat --version` and rerun the failed task with `--stacktrace`. The daemon requires JDK 25. |
| Windows receiver will not start | Missing runtime, address already in use, access error or active instance | Check the tray and `%LOCALAPPDATA%\Input Control Receiver\lifecycle.log`. Install the x64 C++ runtime if Windows names a missing DLL. Check the listener with `Get-NetTCPConnection -LocalPort 47831 -State Listen`. |
| Receiver reports error 32 or maintenance cannot replace the EXE | Live executable or trust-store handle | Check the path and operation in `lifecycle.log` or `maintenance.log`. Quit the relevant receiver normally, including in another Windows session if applicable. Preserve the trust store and its guard. |
| Tablet missing from ADB or driver install fails | Charge-only cable, OEM driver, locked screen or pending debugging prompt | Run `adb devices -l` if Platform-Tools is on PATH, or use the full SDK path above. Reconnect unlocked, accept the prompt for your PC and use the vendor's driver if Windows needs it. |
| PC missing from Devices | Receiver stopped, wrong interface, multicast filtered or guest isolation | Verify **Listening on** in Windows, compare the two networks and retry the scan. Try **Manual connection** with the displayed IPv4 address. |
| Manual connection times out or wrong PC appears | Wrong address or port, different subnet, or firewall block | Check the companion's current listener, enter port 47831 and compare IP/subnet settings. Review the Private TCP rule. Verify the PC name and pairing code before approving. |
| Android denies LAN access | Android local network restrictions | On affected Android 16 devices, use **Allow nearby devices** or check Android app permissions. Retry discovery or manual connection. |
| Pairing rejected, expired or identity changed | Code mismatch, request older than 60 seconds, reinstallation or revocation | Verify both devices in person and start a fresh request. The receiver allows three new requests per rolling minute. If either endpoint changed identity, remove the stale trust relationship and pair again. |
| Connection unexpectedly drops | App backgrounded, IP changed, Wi-Fi interrupted or heartbeat expired | Reopen the app, check the current listener and use **Connect** or pair by manual address as appropriate. Keep the tablet app in the foreground for active use. |
| Connected but clicks or keys are ignored | Target lacks focus, is elevated or uses a secure desktop | Test in an ordinary Notepad window with the normal user account. Windows restricts input injection into higher-integrity applications. [Microsoft SendInput reference](https://learn.microsoft.com/en-us/windows/win32/api/winuser/nf-winuser-sendinput) |
| Audio display is empty or playback buttons do nothing | No default output, older build or incompatible player | Check PC Sound settings and start a player that exposes Windows media controls. Install matching versions. |
| Controls appear stuck or the UI stops responding | Dropped session, input overload or an app hang | Check Devices for errors and debug builds' **Settings → About → Input diagnostics**. Disconnect from either side, then reconnect. If needed, quit the companion from its tray and reopen both apps. |

## Developer guide

| Path from `Input_Control` | Contents |
| --- | --- |
| `android-app/` | Kotlin and Jetpack Compose UI, Android manifest, Gradle wrapper, DataStore preferences, LAN discovery, and Compose instrumentation tests. Gradle builds and packages the Rust JNI libraries. |
| `rust-core/` | Rust JNI bridge, event engine, Hangul composition, Wi-Fi client and engine tests. |
| `wifi-protocol/` | Shared Rust message format and encrypted transport using Snow's Noise XX implementation, plus protocol tests. |
| `windows-receiver/` | Rust TCP receiver and Win32 input adapter, C++ Win32 companion, UI Automation, audio and launcher adapters, receiver tests and PowerShell maintenance scripts. |
| `branding/` and `docs/` | Icons, `docs/ARCHITECTURE.md`, `docs/PERFORMANCE.md`, the original specification in `docs/SPECIFICATION.md`, `docs/wireframe.png` and screenshots. The original specification includes historical milestones. |
| `and.java` | Historical implementation notes stored as text with a Java extension. The Android build uses the Kotlin sources under `android-app/`. |

Use the same toolchain as the installation guide. Run the following Cargo commands from the repository root in PowerShell.

```powershell
cargo test --locked --manifest-path .\rust-core\Cargo.toml
cargo test --locked --manifest-path .\wifi-protocol\Cargo.toml
cargo test --locked --manifest-path .\windows-receiver\Cargo.toml
cargo build --release --locked --manifest-path .\windows-receiver\Cargo.toml
```

From `Input_Control\android-app`, build both APK variants, run lint and assemble the instrumentation APK. Run connected tests on a disposable Android emulator using default gesture settings and English as the default language. Gradle's instrumentation runner can remove the installed app or its test package during cleanup.

```powershell
.\gradlew.bat :app:assembleDebug :app:assembleRelease :app:lintDebug :app:assembleDebugAndroidTest
.\gradlew.bat :app:connectedDebugAndroidTest
```

APK outputs are under `android-app/app/build/outputs/apk/`. The debug APK is signed for development. The release build has no production signing configuration in the project. The receiver release executable is at `windows-receiver/target/release/input-control-receiver.exe`. There is no Windows installer or automatic release pipeline in the inspected project.

For the portable update workflow, run these commands from the repository root in PowerShell. Set `$installed` to the exact EXE you launch. If you normally set `--bind`, pass the same address through the helper's `-BindAddress` option. The helper requests a graceful stop, waits for the identified receiver, replaces the file and attempts rollback on launch failure.

```powershell
$installed = (Resolve-Path .\windows-receiver\target\release\input-control-receiver.exe).Path
cargo build --release --locked --manifest-path .\windows-receiver\Cargo.toml --target-dir .\windows-receiver\target\staged
.\windows-receiver\Maintain-Receiver.ps1 -InstalledExe $installed -NewExe .\windows-receiver\target\staged\release\input-control-receiver.exe
```

For a restart without replacement, use `.\windows-receiver\Maintain-Receiver.ps1 -InstalledExe $installed -Restart`. The separate lifecycle test needs a disposable interactive Windows desktop session with no companion running and a local IPv4 address assigned to the test machine. Substitute that address in `.\windows-receiver\tests\lifecycle.ps1 -BindAddress 192.168.1.25`.

To contribute, work on a topic branch and run the relevant Cargo or Android checks. Keep dependencies locked, coordinate protocol changes across sender and receiver, and report the toolchain and devices used to test the change. Use synthetic data in test fixtures and keep local SDK settings, private identity stores, logs and build outputs out of commits.

### Verification for this source copy

- Windows 11 x64 built the receiver in release mode and passed all 18 Rust tests across the three crates. The receiver tests cover loopback pairing, reconnect, revocation and input cleanup. The existing companion was listening on TCP 47831. The lifecycle script was left pending because it requires a desktop session without a running companion.
- With JDK 25, the Android debug, unsigned release and instrumentation APK builds passed. Both app APKs contain ARM64 and x86-64 native libraries. Android debug lint completed with 19 warnings and no errors.
- The debug app installed and launched on an Android 16 x86-64 emulator. All 11 Android instrumentation tests passed there. Physical USB deployment and tablet-to-PC Wi-Fi behavior still need device testing.

### Physical verification checklist

Automated checks and source inspection cover the build and software paths. A real tablet and Windows PC are needed to verify discovery and manual connection across the LAN, code entry and approval, reconnect after rotation or a brief network outage, touch gestures, input into Windows editors, smart keyboard, launcher and media behavior, and cleanup after disconnect or revocation. Verify optional firewall and Android permission behavior on the actual network and Android version.

## Frequently asked questions

**Do I need Bluetooth or a USB cable while using it?** Use the LAN for normal operation. A USB data cable is useful for the initial install via ADB. Bluetooth HID is unavailable.

**Can two tablets control the PC at once?** You can save multiple trusted tablets, but the receiver handles one active connection at a time.

**Do I need a Galaxy Tab?** The build targets ARM64 and x86-64 devices on Android 9 and newer. The included screenshots show a Galaxy Tab S8 Ultra. Check your own tablet's compatibility before relying on it.

**Can I use it from another network?** Use both devices on the same directly attached IPv4 subnet. The receiver filters peers outside that subnet.

**Will updating erase pairing?** An in-place Android update signed with the same key usually retains the app's identity. Run the new Windows EXE under the same account and keep its existing pairing store.

**Why is the companion still running after I close it?** Close to notification area is enabled by default. Open the tray icon's menu and choose **Quit** to stop it.
