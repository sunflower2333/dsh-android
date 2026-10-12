# Task 2 report

Implemented mobile protocol and native dispatch for the capability operations:

- Added exact `/v1/mobile/adb_status`, `/v1/mobile/adb_shell`, `/v1/mobile/root_shell`, and `/v1/mobile/ui_xml` path mappings and sealed commands.
- Added strict schemas for session IDs, optional serials, bounded argv (1–32), timeout (100–15000 ms), XML source, and unknown-field rejection.
- Updated bridge JSON parsing to accept only top-level arrays needed by capability argv while retaining strict object parsing and authentication.
- Routed shell operations through the shared `AndroidCapabilityManager` after existing accessibility, lock, and current-grant checks. ADB responses include requested target metadata; UI XML ADB source uses bounded `uiautomator dump`.
- Routed accessibility XML to `MobileAccessibilityService.execute` for Task 3's implementation.
- Expanded protocol tests for exact paths, schemas, malformed values, and untrusted-value redaction.
- Rejected shell metacharacters in `argv` at protocol validation (before capability dispatch), including quotes, backslash, control characters, and command separators. The bridge parser still accepts top-level arrays because `argv` requires them; operation schemas reject arrays on every other field before execution, while structured host events retain their existing array support.

Validation:

- `git diff --check` — passed.
- `./gradlew :app:testDebugUnitTest --tests io.github.sunflower2333.dsh.MobileProtocolTest` — blocked before Gradle startup because the wrapper could not download Gradle 8.9 (`UnknownHostException: services.gradle.org`).

Concerns:

- Accessibility XML returns the service's existing unsupported-action response until Task 3 adds its `UiXml` service implementation; dispatch is intentionally wired to that service queue now.
