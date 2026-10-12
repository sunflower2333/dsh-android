# Android ADB, Root Shell, and UI XML Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add explicit Android ADB/root-shell capabilities and bounded UI XML observations to DSH Mobile use, with settings controls, host/native tests, and CI coverage.

**Architecture:** A new native capability manager owns imported ADB execution, `su -c` execution, process limits, and capability state. The existing authenticated mobile bridge exposes those operations and serializes the local accessibility tree as XML; the Android-only JS plugin validates and exposes the operations. Settings uses the existing user-gesture web-message port and a file picker for the user-provided ADB client.

**Tech Stack:** Kotlin Android API 30+, Android WebView/WebMessage, `ProcessBuilder`, `AccessibilityNodeInfo`, JavaScript ESM plugin patching, Node `node:test`, Gradle/JVM tests.

**Spec:** `docs/superpowers/specs/2026-10-12-android-adb-root-ui-xml-design.md`

## Global Constraints

- ADB is opt-in and uses a user-provided ARM64 client for USB or wireless targets.
- ROOT shell is opt-in and invokes only `su -c`; it never silently falls back to another capability.
- All subprocesses use argv arrays, clear DSH bearer variables, enforce time/output limits, and return redacted stable errors.
- UI XML is explicit, bounded, and password-redacted; default `mobile_observe` behavior remains unchanged.
- Existing Mobile session/grant, lifecycle, same-UID bridge authentication, and serial queue rules remain authoritative.
- Repository files, package manifests, lockfiles, and generated runtime assets remain unchanged unless the task explicitly owns the source file.

## Review Focus

- A malformed argv/serial or shell metacharacter must be rejected before spawning; covered by `MobileProtocolTest` and JS schema tests in Task 2/Task 5.
- A disabled or revoked capability must kill active work and never execute; covered by `AndroidCapabilityManagerTest` in Task 1 and `MobileProtocolTest` in Task 2.
- A timeout or oversized stdout/stderr must terminate/limit the child and return a stable error; covered by `AndroidCapabilityManagerTest` in Task 1 and JS transport fixtures in Task 5.
- A password node or XML containing invalid/oversized text must be redacted/truncated without leaking data; covered by `MobileObservationTreeTest` in Task 3 and JS response tests in Task 5.
- An ADB target different from the local accessibility window must carry an explicit target ID and never be silently substituted; covered by `MobileProtocolTest`/`MobileUseControllerTest` in Task 2 and host fixtures in Task 5.

### Task 1: Native capability manager and process runner

**Files:**
- Create: `android/app/src/main/java/io/github/sunflower2333/dsh/AndroidCapabilityManager.kt`
- Create: `android/app/src/test/java/io/github/sunflower2333/dsh/AndroidCapabilityManagerTest.kt`
- Modify: `android/app/src/main/AndroidManifest.xml` only if the file picker or foreground service declaration needs an owned capability declaration.

**Interfaces:**
- Produces `AndroidCapabilityManager.status(context): JSONObject` with only `adbConfigured`, `adbEnabled`, `adbConnected`, `adbTarget`, `rootAvailable`, and `rootEnabled` fields.
- Produces `setAdbEnabled`, `setRootEnabled`, `importAdb(uri)`, `executeAdb(sessionId, serial, argv, timeoutMs)`, `executeRoot(sessionId, argv, timeoutMs)`, `cancelAll`, and `serializeXml` entry points used by later tasks.
- `executeAdb` and `executeRoot` return bounded `{ok,value|error}` JSON with exit code, stdout, stderr, and `truncated` fields; they never return arbitrary exception text.

- [ ] **Step 1: Write failing tests** for disabled capability rejection, argv/serial validation, root `su -c` construction, output limits, timeout cancellation, DSH environment removal, status sanitization, and capability revocation cancelling a child.
- [ ] **Step 2: Run `./gradle ... :app:testDebugUnitTest --tests '*AndroidCapabilityManagerTest'`** with the direct Gradle 8.9 binary; verify the new tests fail for missing types/behavior.
- [ ] **Step 3: Implement the manager** with app-private `files/tools/adb` import, restrictive file mode, `ProcessBuilder` argv execution, bounded stream readers, monotonic timeout, `destroyForcibly`, and explicit state gates.
- [ ] **Step 4: Run the focused tests** and verify all capability-manager assertions pass.
- [ ] **Step 5: Commit** `feat(android): add bounded adb and root capability runner`.

### Task 2: Mobile protocol and native dispatch

**Files:**
- Modify: `android/app/src/main/java/io/github/sunflower2333/dsh/MobileProtocol.kt`
- Modify: `android/app/src/main/java/io/github/sunflower2333/dsh/MobileUseController.kt`
- Modify: `android/app/src/main/java/io/github/sunflower2333/dsh/MobileBridge.kt`
- Modify: `android/app/src/test/java/io/github/sunflower2333/dsh/MobileProtocolTest.kt`
- Create or modify: `android/app/src/test/java/io/github/sunflower2333/dsh/MobileCapabilityDispatchTest.kt`

**Interfaces:**
- Add operations `/v1/mobile/adb_status`, `/v1/mobile/adb_shell`, `/v1/mobile/root_shell`, and `/v1/mobile/ui_xml`.
- `adb_shell` fields: `sessionId`, optional `serial`, `argv` (1–32 bounded strings), optional `timeoutMs` (100–15000).
- `root_shell` fields: `sessionId`, `argv`, optional `timeoutMs`.
- `ui_xml` fields: `sessionId`, `source` (`accessibility_xml` or `adb_uiautomator`), optional `serial` for the latter.
- Dispatch requires the current Mobile grant for shell/XML operations; status is read-only and does not grant control.

- [ ] **Step 1: Add failing parser/dispatch tests** for exact paths, allowed fields, malformed values, stale sessions, capability gates, and target metadata.
- [ ] **Step 2: Run the focused Kotlin tests** and confirm failures.
- [ ] **Step 3: Implement sealed commands, strict parsers, and `MobileUseController` dispatch** to the capability manager, retaining existing lifecycle/lock/revocation behavior.
- [ ] **Step 4: Run `MobileProtocolTest` and `MobileCapabilityDispatchTest`** and verify pass.
- [ ] **Step 5: Commit** `feat(android): expose capability operations through mobile bridge`.

### Task 3: Accessibility UI XML serialization

**Files:**
- Modify: `android/app/src/main/java/io/github/sunflower2333/dsh/MobileAccessibilityService.kt`
- Modify: `android/app/src/main/java/io/github/sunflower2333/dsh/MobileObservationTree.kt`
- Modify: `android/app/src/test/java/io/github/sunflower2333/dsh/MobileObservationTreeTest.kt`
- Create: `android/app/src/test/java/io/github/sunflower2333/dsh/MobileUiXmlTest.kt`

**Interfaces:**
- Add `serializeAccessibilityXml(root: AccessibilityNodeInfo, limits: XmlLimits): XmlSnapshot` with max depth 40, max nodes 1000, max output 256 KiB, and `truncated` metadata.
- Preserve class/resource-id/package/bounds/enabled/visible/clickable/editable/scrollable/password attributes; replace password text and description with `[REDACTED]`.
- `MobileAccessibilityService` consumes the current foreground root and returns session, observation, window/display, XML, and truncation metadata under the existing grant checks.

- [ ] **Step 1: Write failing serializer tests** for hierarchy/escaping, bounds/state attributes, password redaction, depth/node/byte truncation, and malformed surrogate handling.
- [ ] **Step 2: Run focused serializer tests** and verify failures.
- [ ] **Step 3: Implement bounded XML serialization** using `XmlSerializer` or equivalent escaping, reusing existing traversal limits and disposing node copies.
- [ ] **Step 4: Run `MobileObservationTreeTest` and `MobileUiXmlTest`** and verify pass.
- [ ] **Step 5: Commit** `feat(android): serialize bounded accessibility UI XML`.

### Task 4: Settings controls and ADB client import

**Files:**
- Modify: `android/app/src/main/java/io/github/sunflower2333/dsh/AndroidSettingsPolicy.kt`
- Modify: `android/app/src/main/java/io/github/sunflower2333/dsh/AndroidSettingsBridge.kt`
- Modify: `android/app/src/main/java/io/github/sunflower2333/dsh/MainActivity.kt`
- Modify: `scripts/patch-android-settings-ui.mjs`
- Modify: `scripts/android-settings-ui.js` only if the existing helper needs new status/action plumbing.
- Modify: `android/app/src/test/java/io/github/sunflower2333/dsh/AndroidSettingsPolicyTest.kt`
- Modify: `scripts/test-android-settings-ui.mjs`

**Interfaces:**
- Add gesture-protected commands `open-adb-file`, `set-adb-enabled`, and `set-root-enabled`; `status` includes capability fields without paths/tokens.
- MainActivity handles an `ACTION_OPEN_DOCUMENT` result, copies the selected executable to `files/tools/adb`, applies mode 0700, and publishes a fresh settings status.
- Settings UI renders separate ADB/root rows, connection/availability state, import button, and enable/disable buttons in English and Chinese.

- [ ] **Step 1: Add failing Kotlin and JS settings tests** for user-gesture enforcement, picker cancellation, status redaction, independent toggles, and capability revocation.
- [ ] **Step 2: Run the focused settings tests** and verify failures.
- [ ] **Step 3: Implement native settings commands/picker flow and patch the settings section** without weakening existing trust/origin checks.
- [ ] **Step 4: Run `AndroidSettingsPolicyTest` and `node scripts/test-android-settings-ui.mjs <fixture>`** and verify pass.
- [ ] **Step 5: Commit** `feat(android): add adb and root settings controls`.

### Task 5: MOBILEUSE JavaScript tools and host fixtures

**Files:**
- Modify: `scripts/android-mobile-tools.mjs`
- Modify: `scripts/test-android-mobile-tools.mjs`
- Modify: `scripts/patch-android-mobile-tools.mjs` only if plugin registration needs a schema revision.
- Modify: `android/mobile-details.md`

**Interfaces:**
- Register `mobile_adb_status`, `mobile_adb_shell`, `mobile_root_shell`, and `mobile_ui_xml` only when `DSH_ANDROID=1`.
- Validate argv, serial, timeout, source, session binding, bounded response fields, XML target identity, and stable `MOBILE_*` error codes.
- Keep `mobile_observe` unchanged by default; `mobile_ui_xml` is explicit.

- [ ] **Step 1: Add failing Node tests** for registration, malformed input, capability disabled, queue/abort/timeout, output limits, XML source/target identity, password redaction, and no token leakage.
- [ ] **Step 2: Run `node scripts/test-android-mobile-tools.mjs <fixture>`** and verify failures.
- [ ] **Step 3: Implement transport actions, validators, tool definitions, error mapping, and bounded untrusted output handling.
- [ ] **Step 4: Run the full mobile tool fixture suite** and verify pass.
- [ ] **Step 5: Commit** `feat(android): expose adb root and ui xml mobile tools`.

### Task 6: CI/build documentation and reusable setup

**Files:**
- Modify: `.github/workflows/android.yml`
- Modify: `android/README.md`
- Modify: `android/SANDBOX.md`
- Modify: `android/mobile-details.md`
- Modify: `scripts/stage-android-runtime.sh` only if the staged package needs the new plugin helper.

**Interfaces:**
- CI runs new host tests before runtime compilation and retains existing Gradle unit/lint/assemble/sign checks.
- Documentation states ARM64 client/import requirements, opt-in security behavior, ADB target scope, root availability, UI XML limits, and that this cloud host cannot provide a real ARM64 emulator.

- [ ] **Step 1: Add CI/documentation checks** for the new host test commands and required runtime environment variables (`GRADLE_USER_HOME`, Android SDK/NDK paths where applicable).
- [ ] **Step 2: Run the host checks and Markdown/script syntax checks** to verify the documented commands.
- [ ] **Step 3: Implement CI/docs updates** without adding fake runtime assets or weakening `verifyRuntimeAssets`.
- [ ] **Step 4: Run the complete available verification:** all host JS tests, Kotlin unit tests with a real staged runtime if available, lint, and assemble; record emulator limitation separately.
- [ ] **Step 5: Commit** `ci(android): verify adb root and ui xml integrations`.

### Task 7: Branch review and upstream submission

**Files:**
- No new source files; review all commits and generated/untracked outputs.

- [ ] **Step 1: Run `git diff origin/android...HEAD`, `git status --short`, and the complete required checks.**
- [ ] **Step 2: Remove only unintended generated files and confirm the repository checkout is clean except committed changes.**
- [ ] **Step 3: Commit any final fixes with focused messages.**
- [ ] **Step 4: Push the validated commits to the configured `android` branch remote and verify the remote commit.**
- [ ] **Step 5: Report CI workflow status/URL and distinguish local checks, remote CI, and unavailable device-level emulator/root checks.**
