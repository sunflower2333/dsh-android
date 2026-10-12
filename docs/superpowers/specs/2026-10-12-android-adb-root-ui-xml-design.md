# Android ADB, Root Shell, and UI XML Design

## Goal

Extend the existing Android branch so DSH users can explicitly opt into ADB and root-shell capabilities from Android settings, expose those capabilities to the Mobile use tool runtime, and request a bounded XML representation of the current foreground UI in addition to accessibility text and screenshots. The feature must preserve the existing grant/session model and fail closed when a capability is unavailable.

The selected ADB model is a user-provided ARM64 `adb` client used for USB or wireless targets. The APK does not pretend that an ordinary app UID is an ADB daemon, and it does not silently fall back to root or accessibility when an ADB request fails.

## Components and data flow

### Settings

The Android settings bridge gains user-gesture-protected commands for importing an `adb` executable, configuring an optional target serial, enabling/disabling ADB, and enabling/disabling root shell. The imported client is copied into app-private storage with restrictive permissions; its persisted location and target metadata contain no credentials. A status snapshot reports only configured, enabled, connected, serial/fingerprint, and root availability fields. Disabling a capability terminates active child processes and clears transient grants.

The Web settings patch adds a security section with separate ADB and root controls, capability status, and an import/configure action. The page never receives a shell token, executable path, or raw command output.

### Native bridge

`MobileProtocol` and `MobileBridge` add bounded operations:

- `adb_status` reports capability state.
- `adb_shell` accepts a session ID, optional target serial, an argv array, and a bounded timeout.
- `root_shell` accepts a session ID, an argv array, and a bounded timeout; the native implementation invokes `su -c` only after root is enabled.
- `ui_xml` accepts a session ID and an explicit source (`accessibility_xml` or `adb_uiautomator`).

All subprocesses use `ProcessBuilder` argument arrays, a cleaned environment without DSH bearer variables, a maximum runtime, and independent stdout/stderr limits. Timeout handling destroys the process and reports a stable error code. Command strings, credentials, and arbitrary native exception text never appear in tool responses or settings status.

ADB commands are scoped to the configured serial and use the imported client. ADB XML includes a target identifier so results from another device cannot be mistaken for the local accessibility observation. ADB UI hierarchy dumps are temporary, bounded, removed after reading, and password nodes are redacted.

### UI XML

`accessibility_xml` serializes the existing `rootInActiveWindow` snapshot. It reuses the accessibility service's foreground-window, display, lock, grant, observation lifetime, depth, node, and byte limits. XML attributes are limited to class, resource ID, package, bounds, enabled/visible/clickable/editable/scrollable/password state, and non-password text/content description. Password text and descriptions are replaced with a redaction marker.

`adb_uiautomator` is explicit and requires ADB to be enabled and connected. It does not silently use the local accessibility service. Both forms return session and observation/target metadata and are rejected when the foreground target or authorization has changed.

### MOBILEUSE tools

The Android-only plugin registers `mobile_adb_status`, `mobile_adb_shell`, `mobile_root_shell`, and `mobile_ui_xml`. Tool schemas validate IDs, argv elements, serial format, timeout, output size, source, and session ownership before dispatch. The existing serial queue, abort handling, error mapping, and user-facing control guidance remain in force. Shell and XML output is marked untrusted and bounded; no tool can grant ADB/root permission or loop on a failed observation.

`mobile_observe` keeps its current default accessibility behavior. UI XML is opt-in through `mobile_ui_xml`, so existing clients and model routes do not change implicitly.

## Error handling and security

ADB and root are independent capability gates. An unavailable executable, missing `su`, denied USB/wireless pairing, locked device, stale session, changed foreground target, timeout, output overflow, or non-zero exit has a stable redacted error. There is no automatic fallback between ADB, root, accessibility, and screenshot paths. Stored ADB target metadata is non-secret; pairing credentials are not accepted by the settings protocol.

The native bridge continues to require same-UID abstract-socket authentication. Capability enablement is a direct user gesture and is revoked on page lifecycle changes, service disconnect, device lock, host restart, or explicit stop. Active subprocesses are cancelled when a capability is revoked.

## Verification

Host-side JavaScript fixtures cover tool registration, schema rejection, session binding, output bounds, timeout/error mapping, capability gating, XML source selection, and password redaction. Kotlin tests cover protocol parsing, settings command policy, subprocess argument validation, status sanitization, XML serialization bounds, and capability revocation.

CI runs the host fixtures before runtime compilation, then the existing Bash/Node runtime build and Gradle unit tests, lint, and APK assembly. The cloud environment's ARM64-only APK cannot be exercised by its x86_64 host without hardware acceleration; local validation therefore includes tool/protocol tests and Gradle checks, while real USB/wireless ADB, root authorization, and Android UI behavior remain device-level checks.
