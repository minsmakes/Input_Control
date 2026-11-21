# Release checklist

## Before tagging

- [ ] Review the release commit, version properties, Windows package and lockfile version, notes, and any protocol compatibility changes.
- [ ] Confirm Android signing secrets and compare the published production fingerprint. Confirm Windows Public Trust signing, timestamping, and the expected verified publisher.
- [ ] Review resolved dependencies. Refresh the Android lockfile only for an intentional dependency change using `bash ./gradlew --write-locks :app:assembleRelease :app:bundleRelease` from `android-app`, or the Windows wrapper equivalent.
- [ ] Verify the signing environments and tag protections. Push the authorized tag only after review.

## Automated workflow checks

- [ ] Verify release tag, Android code progression, and commit ancestry.
- [ ] Check OSV results, Rust tests, Android lint and instrumentation APK build.
- [ ] Check Authenticode status and timestamp for the receiver, uninstaller and installer. Verify the receiver in the portable ZIP matches the packaged binary.
- [ ] Verify APK and AAB signatures, signer fingerprint, app ID, SDKs, release version, supported ABIs, and 16 KB page alignment.
- [ ] Download the draft assets and verify every SHA-256 entry from the draft.

## Manual installation and interoperability

- [ ] On a clean Windows 11 x64 standard-user profile, download through the intended browser, confirm the publisher and observe SmartScreen with protections enabled. Test Setup, Start Menu and optional desktop shortcuts, startup, and the portable ZIP on a PC without a separately installed redistributable.
- [ ] Update over a previous installer where available. Also test migration from the 1.5.0 standalone receiver. Confirm stored pairings, appearance and settings, sign-in startup, and uninstall cleanup. Test upgrade with the receiver open and after quitting from the tray.
- [ ] On a clean Android test device, install through Chrome and through My Files as permitted by device policy. On Samsung, record Auto Blocker and Play Protect behavior with normal protections. Verify fresh install and an in-place APK upgrade from the published signer, retaining settings and pairing. Test a Play-delivered build if evaluating store distribution.
- [ ] On a trusted physical LAN, verify discovery and manual IPv4 connection, wrong code and rejection, first approval, pinned reconnect, background and rotation behavior, and recovery after changing networks. Verify pointer, keyboard in both languages, app launcher and media controls. Check firewall Private profile and rejection from an unpaired tablet.
- [ ] Optionally run the receiver's `windows-receiver/tests/lifecycle.ps1` in a disposable Windows session with a private LAN IP, no other companion running, and the test receiver path supplied. Run Android instrumentation in a dedicated test profile or emulator.
- [ ] Review the draft's exact assets and signing identity. Publish manually after approval.
