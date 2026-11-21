# Connect a tablet to Windows

The Android app is the controller. The Windows companion receives commands and injects keyboard and mouse events into the current desktop. It also supplies the app launcher catalog, playback and audio state, and supported text-field focus information. The tablet typically connects over Wi-Fi. The Windows PC can be wired to the same LAN. Bluetooth HID, direct USB input, and remote access over the Internet are outside the supported setup.

## Pair and use

1. Put both devices on the same trusted IPv4 subnet. Avoid guest Wi-Fi and networks that isolate clients. Open the Windows companion and confirm that it shows **Listening on** a private IPv4 address and port **47831**.
2. On the tablet, open **⋮ → Devices → Pair New Device**. The scan uses Android NSD with service type `_inputcontrol._tcp` via multicast DNS on UDP **5353**. It stops after about 12 seconds. Select the PC and tap **Pair**. If no PC appears, use **Manual connection** with the address and port from the companion.
3. Compare the first three code groups on the PC with the three groups on the tablet. If they match, enter the fourth PC group on the tablet and tap **Confirm match**, then **Approve** on the PC within 60 seconds. Reject a mismatch or unexpected pairing request. A wrong code ends the attempt, and repeated new requests are rate-limited.
4. Open **Control** and try pointer movement and typing in a regular Notepad window. Use **한/영** to switch between English and Korean. The Control screen also exposes pinned apps and media controls. Use **Disconnect** to end the session. **Quit** in the Windows tray shuts down the companion.

## Permissions and firewall

- If Windows Defender Firewall prompts, allow the receiver only on a trusted **Private** network. If local policy requires a rule, ask the administrator to allow inbound TCP **47831** for the exact installed executable from the local subnet. Allow local mDNS UDP **5353** where discovery is desired. Manual connection works without multicast discovery. Setup runs per user and leaves firewall policy to Windows or your administrator. Avoid public-profile rules and router port forwarding.
- Android declares `INTERNET` and `CHANGE_WIFI_MULTICAST_STATE` for LAN transport and discovery. If Devices shows a **Nearby devices** prompt on a supported OS, grant it under your device policy. Android 16 local-network restrictions can use `NEARBY_WIFI_DEVICES`. Target 36 retains the legacy LAN access model under Android 17. [Android local network permission](https://developer.android.com/privacy-and-security/local-network-permission)
- If autodetection picks the wrong adapter, quit the companion and start it with `--bind <PC-LAN-IPv4>`. The receiver listens on the selected address and accepts peers from its subnet. Recheck the address after changing networks, adapter, or VPN. Normal integrity-level processes such as Notepad can receive input from a standard-user receiver. Elevated windows and the secure desktop are subject to Windows input isolation.

## Reconnect and recover

The first pairing pins both peers' public keys using a Noise XX session and explicit operator confirmation. The receiver protects its identity with user-scoped DPAPI under `%LOCALAPPDATA%\Input Control Receiver`. Android protects its identity with an Android Keystore wrapping key. A later connection verifies the saved pins before accepting input.

Use **Connect** on a saved peer. If the address changed and discovery does not refresh it, use **Manual connection** with the current address and confirm a new pairing if prompted. With **Auto-reconnect** enabled, a broken session can retry once, and a foreground return can reconnect to the saved peer. Manual disconnect clears the automatic target. A genuine background stop disconnects, while ordinary rotation retains the transport. The receiver releases held input on graceful disconnect and attempts cleanup on connection failure.

If the saved identity changed, verify that the correct PC is running and determine whether its pairing data was reset. Revoke or forget the old entry on both devices as applicable, then repeat full verification. If connection times out, check the listening address, subnet, and private firewall rules. The receiver supports one active controller session. See the [architecture](ARCHITECTURE.md) and the [manual test checklist](RELEASE_CHECKLIST.md).
