# Release and signing

Input_Control uses a native Rust/C++ Windows receiver and a Kotlin/Compose Android controller with a Rust JNI library. Cargo lockfiles cover the Rust components. The Gradle 9.6.0 wrapper JAR and distribution both use pinned SHA-256 checksums. The Android release classpaths use Gradle dependency locking. CI checks resolved libraries against OSV.

## Release versions and assets

Set `versionName` and increase `versionCode` in [version.properties](../release/version.properties). Match `versionName` to the Windows receiver version in `Cargo.toml` and refresh its lockfile. Update [release notes](../release/NOTES.md). The first packaging release is 1.5.1 with Android version code 8. The published 1.5.0 uses code 7 and is the migration baseline. The packaging change keeps the existing Wi-Fi protocol.

| Asset | Purpose |
| --- | --- |
| `InputControl-vX.Y.Z-windows-x64-setup.exe` | Per-user Windows 11 x64 installer |
| `InputControl-vX.Y.Z-windows-x64-portable.zip` | Extractable Windows receiver |
| `InputControl-vX.Y.Z-android.apk` | Signed universal APK for ARM64 and x86-64 |
| `InputControl-vX.Y.Z-android.aab` | Signed App Bundle for Play Console |
| `SHA256SUMS.txt` | Digests of the four downloadable assets |

## Windows

Inno Setup packages the existing native executable in a small per-user installer. It handles shortcuts, version metadata, app replacement, and uninstall. WiX/MSI would add component management best suited to managed enterprise deployment. MSIX would add package identity and deployment requirements for a desktop companion that already uses per-user registry settings and LAN listening. The receiver has no existing framework-native installer. Check [Inno Setup licensing](https://jrsoftware.org/inno-setup/purchase) if distributing commercially.

The installer reuses a stable application ID and the existing receiver filename. It keeps the DPAPI pairing store and companion preferences through upgrade and uninstall. Uninstall removes an existing sign-in entry if it points to the installed executable. The portable ZIP contains the same executable, built with static MSVC runtime linkage. No device driver or external runtime installer is part of the package.

Updates are manual through GitHub Releases. The existing `Maintain-Receiver.ps1` is a local developer maintenance tool that checks product metadata and replaces a file supplied by the caller. It is outside the installer and ZIP. Verify a downloaded release's signature and checksum before using it.

For a local unsigned smoke build, run the Windows build command from the [README](../README.md#developer-guide), then run these commands in PowerShell 7 from the repository root.

```powershell
$inno = Join-Path $env:TEMP 'InputControl-Inno'
./release/Install-Inno.ps1 -Destination $inno
./release/Package-Windows.ps1 -Compiler "$inno/ISCC.exe" `
  -Receiver windows-receiver/target/x86_64-pc-windows-msvc/release/input-control-receiver.exe `
  -OutputDirectory dist/windows-local
```

The v1.5.0 GitHub receiver is unsigned. For production, provision a validated **Public Trust** Azure Artifact Signing account and certificate profile. Eligibility depends on the individual or organization and region. Use the provider's verified legal identity. Keep **M. Chung** as product metadata. If Azure eligibility is unavailable, use a supported CA-issued signing certificate with a hardware-backed or CA-managed key and configure its signing tool for the same three executables. [Microsoft signing options](https://learn.microsoft.com/en-us/windows/apps/package-and-deploy/code-signing-options)

1. Create a Microsoft Entra application or workload identity with GitHub OIDC federation scoped to this repository's `windows-signing` environment. Assign the **Artifact Signing Certificate Profile Signer** role at the narrowest supported scope for the public-trust certificate profile.
2. In GitHub's `windows-signing` environment, configure `AZURE_CLIENT_ID`, `AZURE_TENANT_ID`, `AZURE_SUBSCRIPTION_ID`, `ARTIFACT_SIGNING_ENDPOINT`, `ARTIFACT_SIGNING_ACCOUNT`, `ARTIFACT_SIGNING_PROFILE`, and `WINDOWS_SIGNER_SUBJECT`. The last value is the exact certificate subject returned by `(Get-AuthenticodeSignature -LiteralPath <known-signed-file>).SignerCertificate.Subject` for that verified profile.
3. The workflow signs the receiver, signs the Inno-generated uninstaller, embeds it, and signs the installer. Each signature must validate under Windows Authenticode policy, carry a trusted timestamp, and match the configured subject. The ZIP contains the signed receiver. Keep signing credentials and keys outside the repository and release assets.

Authenticode identifies the publisher and verifies integrity. SmartScreen reputation can still trigger warnings for new downloads. Test the actual downloaded files on a clean machine with its normal protections enabled. [Microsoft SmartScreen guidance](https://learn.microsoft.com/windows/apps/package-and-deploy/smartscreen-reputation)

## Android

Package `io.inputcontrol` uses min SDK 28, target SDK 36, compile SDK 37, build-tools 36.0.0 and NDK 27.3.13750724. Gradle produces release-mode unsigned APK and AAB build inputs. The packaging script uses the existing production PKCS#12 keystore outside the working tree, compares its public fingerprint to the published 1.5.0 APK, and checks both signed output files against that same pin. Keep a secure backup of the existing keystore and password. Loss of the app signing key breaks the ordinary GitHub APK update path.

In GitHub's `android-signing` environment, set the variable `ANDROID_KEY_ALIAS` and secrets `ANDROID_KEYSTORE_BASE64` (base64 of the existing keystore), `ANDROID_STORE_PASSWORD`, and `ANDROID_KEY_PASSWORD`. Paste values directly into GitHub's secrets UI or use its secure CLI input. Base64 is an encoding. The job materializes the keystore in its temporary directory, removes it after use, and uploads only the APK and AAB. Store credentials in a password manager or secret store and avoid command-line password values.

Build locally from `android-app` with `./gradlew.bat :app:assembleRelease :app:bundleRelease :app:lintRelease` on Windows or the equivalent `bash ./gradlew` on Linux. From the repository root in PowerShell 7, supply `ANDROID_KEYSTORE_PATH`, `ANDROID_KEY_ALIAS`, `ANDROID_STORE_PASSWORD`, and `ANDROID_KEY_PASSWORD` in the process environment, then run `./release/Package-Android.ps1 -Sdk <Android-SDK-path> -Bundletool <bundletool-1.18.3-jar> -OutputDirectory dist/android`. Clear credential variables afterward. Use the official [bundletool 1.18.3 release](https://github.com/google/bundletool/releases/tag/1.18.3). The script verifies its SHA-256.

To check an APK, run `apksigner verify --verbose --print-certs <downloaded.apk>` from build-tools and compare the signer SHA-256 with [the published release pin](../release/android-signing.properties). Production updates require the same package and signer and a higher code. The installed debug build must be removed before switching channels, which clears its local data. A signed APK remains subject to Samsung Auto Blocker, Android's per-source install permission, and Play Protect. [Samsung Auto Blocker](https://www.samsung.com/us/support/answer/ANS10003636/) · [Google Play Protect](https://support.google.com/android/answer/2812853)

### Google Play

- Register and verify the developer account and complete any account-specific closed testing and device verification requirements. Set the developer display name to **M. Chung** if available and complete legal verification with the actual identity. Create the app under `io.inputcontrol` after checking package ownership and developer verification for installations outside Play.
- Configure Play App Signing with the **existing app signing key** if Play installs should upgrade GitHub installs. Check the Play **app signing** fingerprint against the GitHub pin. The current AAB is signed with the existing key. Before opting into a separate Play upload key, configure bundle signing with that upload key while retaining the existing key for GitHub APK signing. Verify both fingerprints independently. [Play App Signing](https://developer.android.com/studio/publish/app-signing) · [Android developer verification](https://developer.android.com/developer-verification)
- Target 36 meets the currently documented Android 16 submission floor for standard apps. Recheck when submitting. Before moving to target 37, implement and test Android 17's LAN permission model. The build verifies 16 KB-compatible native segments, APK alignment and bundle configuration. Test the APKs delivered by Play on relevant devices. [Target API policy](https://support.google.com/googleplay/android-developer/answer/11926878?hl=en-GB_ALL) · [16 KB page sizes](https://developer.android.com/guide/practices/page-sizes)
- Complete the listing, screenshots, support contact, privacy policy, Data safety, content rating, and applicable app-content declarations. Document local pairing data, Windows app and media metadata sent to the paired tablet, input handling, and actual retention. Start with **Internal testing** or the applicable closed track. Verify GitHub-to-Play and Play-to-GitHub updates on a test device before public submission. [Play Console requirements](https://support.google.com/googleplay/android-developer/answer/9859152)

## GitHub workflow and approval

Protect `v*` tags and limit tag creation to authorized maintainers. Restrict the signing environments to release tags. Configure required reviewers where your GitHub plan supports them. Use a reviewed commit reachable from `main`, confirm both signing environments are configured, then explicitly create and push the matching tag.

```sh
git tag -a v1.5.1 -m "Input_Control 1.5.1"
git push origin refs/tags/v1.5.1
```

The tag runs [the release workflow](../.github/workflows/release.yml) on separate Windows and Linux runners. A matching version, an increasing Android code, tests, known-vulnerability scan, valid signatures, and complete checksummed assets are required to create a **draft**. The workflow never publishes a release. Review the draft and execute the [release checklist](RELEASE_CHECKLIST.md), then publish manually in GitHub Releases when approved. If creation or upload fails after a draft exists, inspect the draft and remove or repair only the incomplete draft before retrying. Never move or replace a published tag. [GitHub environment protection availability](https://docs.github.com/en/actions/how-tos/deploy/configure-and-manage-deployments/manage-environments)
