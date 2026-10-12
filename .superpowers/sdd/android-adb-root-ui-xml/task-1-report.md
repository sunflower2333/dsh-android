# Task 1 report — Native capability manager and process runner

## Changed files

- `android/app/src/main/java/io/github/sunflower2333/dsh/AndroidCapabilityManager.kt`
  adds opt-in ADB and root capability state, private ADB import, strict argv and
  serial validation, `su -c` execution, cleaned child environments, bounded
  stdout/stderr capture, timeout termination, stable JSON errors, and process
  cancellation on capability revoke.
- `android/app/src/test/java/io/github/sunflower2333/dsh/AndroidCapabilityManagerTest.kt`
  covers status redaction, validation before spawn, root command construction,
  timeout cancellation, and private executable import permissions.

## Verification

`git diff --check` passed with no output.

Attempted focused Gradle test command:

```text
ANDROID_HOME=/workspace/android-sdk ANDROID_SDK_ROOT=/workspace/android-sdk \
ANDROID_USER_HOME=/workspace/.android GRADLE_USER_HOME=/workspace/.gradle \
/workspace/.gradle/wrapper/dists/gradle-8.9-bin/90cnw93cvbtalezasaz0blq0a/gradle-8.9/bin/gradle \
:app:testDebugUnitTest --tests '*AndroidCapabilityManagerTest' --no-daemon -x verifyRuntimeAssets
```

The build reached dependency resolution but could not download
`org.jetbrains.kotlin:kotlin-stdlib:2.0.21` because this environment cannot
resolve `dl.google.com` or `repo.maven.apache.org`. The normal command also
requires the repository's generated runtime assets (`src/main/assets/runtime`
and arm64 native libraries), which are absent in this checkout.

## Concerns

Gradle unit tests need to be rerun in an environment with the Android/Kotlin
dependencies and generated runtime assets available. Real ADB, USB/wireless
pairing, and root authorization remain device-level checks.

Follow-up hardening before handoff verifies that root availability only reports
an executable injected command or an executable `su` found on PATH, non-zero
child exits return only stable metadata (without raw command output), and the
clock is injectable for deterministic JVM tests. `status()` has no unused
context parameter.

The production default clock now uses pure-Java `System.nanoTime()` converted
to milliseconds, so capability-manager tests do not depend on Android clock
stubs. A `Clock` injection point remains available for deterministic tests.
