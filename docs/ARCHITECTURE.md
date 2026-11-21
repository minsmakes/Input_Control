# Input Control architecture

MainActivity hosts the Control, Devices, Audio, Shortcuts and Settings screens and RemoteModel. Preferences, keyboard layouts and gesture mappings remain in the v1.0 Android project. Devices uses WifiDevices, a lifecycle-aware coordinator for Android discovery, identity storage and the native Wi-Fi worker. A bounded 12-second NSD scan resolves compatible services serially, deduplicates endpoints, handles lost services and ends when the user leaves Devices. The saved peer list supports a return connection while discovery is stopped. Discovery names and addresses are hints. Only an authenticated welcome supplies the connected PC name.

## Identity and pairing

The shared wifi-protocol crate uses Snow’s Noise_XX_25519_ChaChaPoly_SHA256 implementation with a version-specific prologue. Both endpoints prove possession of persistent X25519 keys. The encrypted XX payload channel initially permits only enrollment or a pinned reconnect. First pairing binds the operator’s decision to 128 bits of the handshake transcript hash. The PC and tablet compare the first 96 bits, and the user copies the remaining 32 bits into the tablet. Using the verification value requires approval of the same request in the Windows companion. Expiry is 60 seconds. One incorrect entry terminates the request, and at most three new requests can begin in a rolling minute. The verification string changes with the handshake. [Noise XX and channel binding](https://noiseprotocol.org/noise.html)

For reconnect, Android checks the receiver’s full static public key before sending CONNECT. The receiver checks the authenticated client’s full static key against its trusted-peer store. Both checks precede READY. The authorized receiver session accepts the validated input protocol. Unknown opcodes, malformed sizes, invalid deltas, unexpected sequence numbers and failed authentication terminate it.

Android stores its private identity encrypted with an AES-GCM wrapping key in Android Keystore. Public PC pins and last known endpoints are app-private. Windows stores its static identity and trusted public keys in a user-scoped DPAPI blob under %LOCALAPPDATA%\Input Control Receiver. Writes use a flushed replacement file and atomic rename. An exclusive account-local lock prevents concurrent receiver processes from overwriting the store. The companion supports local revocation and closes the affected session; the tablet can forget individual receivers.

## Input path and lifecycle

Compose gestures → preallocated Kotlin FIFO → existing JNI dispatch → Rust input engine → bounded Wi-Fi sender → authenticated Windows receiver → SendInput.

At capture, Kotlin stamps each event with the current Wi-Fi session ID. The existing native dispatch consumes it on the input worker. Dispatch forwards each new event in sequence. Plain printable keys use committed Unicode scalars. In negotiated extended sessions, the sender transmits one mutable Hangul scalar and its replacements. Matching commits acknowledge the visible character. Older peers receive committed text only. Enter, navigation, function keys and modifier chords use physical key transitions. Pending text commits before a shortcut or Enter. Local events from an earlier session cannot be routed to a new peer. The existing Hangul composer, physical key state and input processing continue operating offline.

A background thread owns the TCP socket, handshake and transport state. Kotlin polls native state off the UI thread and changes the header after the receiver has acknowledged authorization. Backgrounding the app closes the socket and releases input. Configuration changes retain the socket. A foreground reconnect uses saved pins. A broken session may retry once when auto-reconnect is enabled. Manual disconnect clears the remembered auto-reconnect target.

Each encrypted message has a two-byte big-endian length with a 256-byte ciphertext cap. Each input payload carries a one-byte opcode, a u64 per-session sequence starting at one, and two signed 32-bit fields. The allowlist covers relative movement, left/right transitions, vertical wheel units, physical USB keyboard usages, committed Unicode scalars and release-all. Negotiated sessions also accept Hangul preedit updates and the audio command allowlist. The native sender preserves fractional gesture deltas between packets. The receiver tracks its own injected buttons and keys and attempts release on disconnect, malformed input, timeout, revocation and graceful shutdown. A forced process kill or Windows secure-desktop transition can prevent cleanup.

Windows translates physical keys to set-1 scan codes with extended flags where required. It sends Unicode as UTF-16 down/up pairs using KEYEVENTF_UNICODE. Mouse opcodes and the Noise handshake are unchanged. Bluetooth HID remains a future milestone.


## Smart keyboard and privacy (v1.3)

The optional encrypted FOCUS_HELLO exchange negotiates focus support before the existing enrollment/reconnect exchange. A legacy client can still start directly with PAIR or CONNECT. If an older receiver closes during negotiation, the client opens a fresh Noise session and performs the original checks, including the same pinned-identity check. No focus data or input is accepted before authorization.

An extended, authenticated heartbeat carries the tablet model and focus subscription flag. Its reply appends a validated three-state focus classification and transition counter to the existing metrics. The counter distinguishes movement between editable fields without sending a field identifier. After focus-change events and authenticated left-button releases, the worker samples the current UIA element after a brief settling interval. Repeated samples of the same editable element preserve the manual tablet override. The heartbeat delivers changes even when the tablet is idle. The Windows UIA observer is removed when focus support is disabled or the session ends. Peer labels and Windows companion preferences use HKCU; cryptographic trust remains in the existing DPAPI store.

Rust forwards the current dispatch batch synchronously and clears it. It keeps aggregate metrics, pressed-input state and the active Hangul syllable. Neither the tablet diagnostics nor the Control screen contains committed text, key identifiers or a typing history.

## Rotation and audio

The retained AndroidViewModel owns the authenticated transport. Configuration-change stops release held input while preserving the active composition and socket. Genuine background stops disconnect. Windows remains the owner of the editing cursor and selection.

After explicit pairing or pinned reconnect, peers supporting the extended handshake can exchange bounded audio commands and endpoint snapshots. Windows subscribes to Core Audio volume and device-change notifications on the receiver worker. Callbacks mark the snapshot dirty. The next encrypted heartbeat carries the actual level, mute flag and up to 32 active output labels, followed by the normal acknowledgment. Volume dragging submits one change on release. System media controls use standard Windows media keys. Endpoint selection remains in Windows Sound settings.

## Control dashboard

After peer authentication, capability level 3 extends the existing heartbeat with two bounded, chunked JSON snapshots. One describes the installed application catalog. The other carries the foreground executable basename and the current Windows system media session. Older peers keep their existing input capabilities. Windows samples media and foreground state on a dedicated worker about every two seconds. Artwork is scaled and cached per track. The AppsFolder catalog refreshes every five minutes and contains at most 256 entries.

Launch requests contain only a positive numeric catalog ID. The receiver resolves it to an enumerated shell item in the current session and launches its item ID list. Unknown IDs are ignored. Android stores pin order and shortcut profiles under the paired PC’s identity. Profile actions use validated physical key combinations over the existing ordered input channel.
