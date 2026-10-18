# DeepSeek Harness Android

This is a small native Android client for the existing DeepSeek Harness web UI.
It requires Android 11 (API 30) or newer on an ARM64 device.
`MainActivity` hosts the UI in a loopback-only `WebView`; `DshService` starts DSH in an Android
foreground service and keeps a resident notification while the app is in the
background. The service does not expose a LAN listener and does not enable
Full Access or change DSH's approval policy.

## Android navigation and configuration

On phone-sized screens, settings, menus and the sidebar fill the usable app
viewport. Android Back first dismisses the keyboard, then closes the current
menu or settings page, then the sidebar. These actions use DSH's own React
controllers; they do not navigate away from the conversation. The native root
reserves system-bar, display-cutout and keyboard insets, including when Android
15 enforces edge-to-edge rendering for target SDK 35.

Selecting a sidebar destination closes the full-screen sidebar after DSH has
opened that destination. Appearance keeps DSH's persisted Light, Dark and
System preference. System follows Android's actual night mode, including live
changes; native pages and system-bar colors match the selected appearance.

**Open configuration file** prepares the active Web profile's
`cordis.patch.yml` and opens Android's editor/viewer chooser. Only this file can
be granted through the app's content provider. When no text editor or viewer is
installed, Android's document picker lets the user save a configuration copy;
this fallback does not edit or replace the active configuration. Cancelling the
picker returns to the still-open settings page.

Native navigation patches are staged only into the APK. The Termux package
keeps its existing configuration-file opener. The Android settings preparation
checks can be run from the repository root with a prepared DSH package:

```bash
node scripts/test-android-settings.mjs /path/to/dsh-package
```

## Background tasks and conversation notifications

Active conversation notifications include a live, bounded preview of the
assistant's streamed thinking or response (240 Unicode characters, coalesced
at one update per second). Tool arguments and user prompts are not included.
Changing from thinking to answering replaces the preview; a finished or
disconnected conversation loses its ongoing notification. The notification
keeps its per-session counters and opens the exact conversation when tapped.
Private lock-screen content uses a generic public version.

Live previews are requested automatically; DSH has no app-level switch for
them. On Android 16, including ColorOS 16, ongoing notifications request
promotion through Android's Live Updates API, the public Fluid Cloud entry
point. Xiaomi HyperOS and vivo OriginOS receive the same standard ongoing
notification with bounded content updates, which lets each system apply its
own island/atomic-notification policy when eligible. OEM eligibility and the
user's system notification policy remain authoritative. No media playback,
overlay, vendor whitelist bypass or root permission is used. Labels follow
DSH's selected English/Chinese language.

Switching apps or closing the chat Activity does not stop the foreground DSH
service. The server owns the active conversation, not the WebView. Its native
notification distinguishes running tasks, requests waiting for a response and
an idle host. A bounded partial wake lock is held only while the server reports
active work; waiting for an answer, idle state, lost heartbeats and service
shutdown release it.

DSH's existing question and approval events also produce Android notifications.
Tap a request to open its original conversation through DSH's own navigation;
eligible single text questions also offer Android's inline reply input. Sending
that input answers the exact live question through DSH's question service.
Requests with multiple questions and action approvals still open DSH; a text reply
cannot approve a command. Expired, cancelled or already-answered requests cannot
answer a different question. Generic attention text keeps prompts, command
arguments and credentials out of the notification payload. Completed turns can
notify while the chat is in the background.

Models can also call `notify_user` with a short title and message. This uses the
normal DSH tool runtime and targets the initiating conversation, including when
a child agent calls it. It respects Android notification settings and a
per-conversation rate limit, and reports whether Android accepted the notice.
This tool does not require a phone-control grant.
The optional `request_reply: true` argument waits for a text answer and returns
it to the calling root agent through the normal tool result. Its default is
false. This option does not grant tool permissions or promote a child agent's
request into a different conversation.

Each active conversation has a separate status notification and a row in
**Settings → Background & notifications**. Summaries distinguish session turns,
steps and cumulative token consumption from the latest completed model call's
uncached input, output, total, cache read/write counts and average output rate.
Context usage comes from DSH's context meter. Missing usage, timing, cache or
capacity data is shown as unavailable; absent cache counts are not treated as
zero and no token counts are estimated from text.

Open **Settings → Background & notifications** in the DSH Web UI for live
service/task status, notification permission and battery optimization status.
These sections use DSH's existing components, theme and selected interface
language. Their buttons open Android's system notification and battery settings;
they do not open a separate native settings page. Android 13 and newer request
notification permission only after the user presses the notification button.
App-owned Android notifications, reply actions and operation messages also
follow DSH's effective Chinese or English setting, with English as the fallback.
Changing DSH's language updates them without restarting its service or chat.
Android's system settings and document picker use the system's language.
Battery exceptions are an explicit system choice. A system force-stop or
process kill interrupts in-flight work; saved conversations remain available,
and the app does not automatically replay interrupted tool actions.

## Workspaces

On a fresh installation, DSH creates its default workspace at
`<app data>/files/Documents/deepseek-harness/default-workspace`.
The directory browser starts in the app's Documents directory. Selecting a
different workspace creates or reuses that workspace's session; it does not
move an existing conversation's working directory. Cancellation leaves the
previous selection intact.

The workspace backend uses ordinary file paths under Android's app permissions.
The DSH workspace chooser also offers **Choose a phone folder**, which opens
Android's system folder picker. Shared-storage projects require the Android 11+
**All files access** special permission, requested only after this explicit
choice. Local primary and removable-storage folders are mapped to verified real
filesystem paths; cloud providers, protected Android data/obb folders, volume
roots and unmappable providers are rejected with a DSH-language error.
Cancellation and permission refusal preserve the prior session and workspace.
The app-private directory option needs no storage permission.

A single-folder SAF URI grant does not authorize Node/Bash POSIX path access;
this version uses All files access for shared-storage workspaces and does not
claim folder-only or cloud-workspace support. Android's permission does not
change DSH's selected file-tool write policy or command approvals. Attachment
import and document export keep their separate Android pickers. See
[WORKSPACES.md](WORKSPACES.md) for the supported paths and verification steps.

New-file creation keeps atomic no-replace rename where supported. Shared
storage that rejects rename flags uses an exclusive create, followed by copying
and checking the staged bytes. Existing names are never overwritten, but other
processes may see the new file before copying finishes. Cancellation, I/O
failure, or a crash can leave a partial file; read it before repairing or
retrying. Failure cleanup does not delete the public destination. Existing-file
version checks and replacements retain their original path.

## File access and command approvals

The Android APK keeps DSH's selected file-tool write restrictions. Android's
ordinary app UID is an app boundary; it cannot confine Bash to a workspace like
DSH's desktop command sandbox. In a restricted mode, each Bash invocation
requests DSH's existing single-command approval before execution. Rejection or
cancellation starts no command. Approval affects that command only and leaves
the selected file policy unchanged. No default Full Access is enabled.

The Android permission panel describes file access rather than claiming an
available command sandbox. See [SANDBOX.md](SANDBOX.md) for the actual boundary
and verification commands.

## Mobile use

DSH's Settings page includes a **Mobile use** section.
Enable **DSH phone control** in Android Accessibility Settings, then return to
the same DSH settings section and choose **Allow this task**. Enabling the accessibility service alone leaves
control paused. The DSH settings section, notification's **Pause control** action, and
`mobile_stop` revoke the current grant. DSH host restarts, service disconnects,
and a locked display require another native grant.

The Mobile use settings section remains inside DSH's Web UI. Its status rows,
Allow this task, Pause and feedback toggle use the existing DSH controls and
current DSH language. The accessibility button opens Android's own settings.
Control changes require an explicit user gesture; the model tools cannot grant
themselves control through the WebView.

The APK registers `mobile_status`, `mobile_list_apps`, `mobile_open_app`,
`mobile_observe`, `mobile_click`, `mobile_type`, `mobile_swipe`, `mobile_scroll`, `mobile_back`,
and `mobile_stop` in DSH's existing
tool runtime. Observation supplies bounded accessibility nodes. Image-capable
models can explicitly request a real Android screenshot as a DSH image attachment.
Observation defaults to real accessibility text, hierarchy, bounds and available
actions for every model; text-only operation does not need a screenshot.
Android 11 screenshots require at least 1100 ms between captures.

Phone control operates on the foreground window. `mobile_open_app` brings an
enabled launcher app to the foreground; it cannot click a hidden background
window. Native click and swipe feedback shows where the action occurred without
intercepting touches. The DSH settings section lets the user turn feedback off,
and observation excludes the feedback layer. See [mobile-details.md](mobile-details.md).

Launching an enabled app requires the current native grant and its validated
launcher package, without requiring a prior screen observation. Node clicks,
text input and node scrolling revalidate the actual target and support a
five-minute observation lifetime. Back and coordinate gestures have the same
five-minute model-wait allowance; coordinate clicks and swipes additionally
require an unchanged screen revision. The lifetime uses a monotonic clock, so
system time corrections cannot expire or renew a binding. Errors distinguish
time expiry, a consumed/replaced binding, an agent/session mismatch, a changed
window/screen and a changed target without disclosing native error text. Observe again after an action or
stale-observation error. Text input
replaces the selected editable field; password text is hidden and password
input is refused. A successful action reports Android's acceptance, so a new
observation is needed to confirm its visible result. Pause prevents further
actions; it cannot undo input or a gesture already dispatched to Android.

The native bridge uses an abstract Unix socket, same-UID peer validation and an
ephemeral bearer passed only to the launched Node process. It exposes no TCP
control listener or WebView JavaScript authorization method. APK staging adds
the plugin to its isolated package copy; Termux retains its existing tool set.
DSH's existing tool approval policy continues to apply.

### ADB, Root Shell and UI XML

The same **Mobile use** settings section contains optional ADB and Root Shell
capabilities, each disabled by default. Import a device-compatible
Android/Bionic **ARM64 dynamic PIE** `adb` client through Android's file picker,
then enable ADB. DSH copies it into app-private storage and runs it through the
system linker with private `HOME` and `TMPDIR` directories. Capability settings
do not retain the source URI or pairing codes; the ADB client can store its own
keys and known-host records in that private `HOME`. The target field stores a
bounded USB or wireless serial reported by the client; an empty field uses the
client's default target.

An authorized command that needs an ADB server starts an app-owned foreground
server on an app-private filesystem Unix socket in an owner-verified mode-0700
directory, avoiding the imported client's self-launch path. TCP and abstract
socket endpoints are rejected. Importing, enabling and querying status do not connect to a
device. `mobile_adb_status` starts no process; its connected state records the
latest device-command verification, rather than continuously probing the
device. Client version and connect acknowledgements alone do not establish
that state. Disabling a capability stops active child processes; replacing
the client stops its old managed server and clears the
connection state.

`mobile_adb_shell` and `mobile_root_shell` require the current control grant and
accept bounded argument arrays without a shell string or inherited DSH token
environment. Select the ADB target through `serial`; global server and endpoint
overrides are rejected, and `start-server`/`kill-server` operate on the managed
server. Root Shell requires an executable `su` and the user's explicit enable
switch. DSH then creates an isolated shell through `libsu` 6.0.0, which delegates
authorization to the installed root manager, including Magisk and KernelSU.
A capability setting never grants Accessibility control.

`mobile_ui_xml` explicitly selects one bounded XML source. `accessibility_xml`
serializes the current foreground Accessibility tree, while `adb_uiautomator`
pushes a source-generated DEX helper JAR to a unique path on the selected ADB
target and makes it read-only. The helper runs through `adb shell -T -n` and
`app_process`, preserving remote exit status and separate stderr. It reads real
active-window nodes with `FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES` (flag 1),
avoiding stock `uiautomator dump`'s default suppression of existing services.
Push, chmod, capture and authorized cleanup share one 12-second budget and retain
the original grant and target-configuration checks. ADB XML fails closed above
60 KiB, 256 node visits, depth 48, 8192 characters per text/description or 512 per
identifier field. Password text and descriptions are blank and screen text
remains untrusted data. Gradle generates the helper from Java sources and runs
its encoder checks with the normal JVM test task; no generated binary is tracked.
Same-device Accessibility/grant preservation and complete DSH ADB/XML and
Magisk workflows remain unverified on a real device or emulator.

The host integration checks use the real DSH tool runtime and an isolated
socket fixture:

```bash
node scripts/test-android-mobile-tools.mjs /path/to/dsh-package
node scripts/test-android-host-events.mjs /path/to/dsh-package
node scripts/test-android-sandbox.mjs /path/to/dsh-package
node scripts/test-android-workspace.mjs /path/to/dsh-package
```

The Android workflow first runs the package-free source fixture check, then
runs these package integration checks before compiling Node. It runs native
JVM tests and Android lint before assembling and signing the APK. The host
fixtures validate protocol, bounds, redaction and stable error mapping; Android
behavior is checked separately on ARM64 hardware or a software emulator. An
independent app-UID probe on the local ARM64 software emulator has exercised
TCP device state, shell commands and UI XML through a standard ADB reverse
tunnel to the emulator's real adbd. The complete DSH APK has started locally,
but end-to-end validation of its ADB/XML paths is still pending. The new flag-1
helper has not run on that emulator. USB transport, wireless TLS pairing and root-manager
authorization have not been validated by that probe.

The optional `scripts/test-support/nim-test-relay.py` and
`verify-mobile-nim.py` are bounded test helpers for a separately configured
provider and conversation. Credentials come from private runtime configuration;
they are never included in the APK. A fixture test does not establish that a
real model request or Android accessibility action succeeded.

## Runtime bundle

The Android runtime packaging step must provide all of these files before an
APK is built:

```text
app/src/main/assets/runtime/manifest.json
app/src/main/assets/runtime/runtime.zip
app/src/main/jniLibs/arm64-v8a/libdsh_node.so
app/src/main/jniLibs/arm64-v8a/libc++_shared.so
app/src/main/jniLibs/arm64-v8a/libdsh_esbuild.so
app/src/main/jniLibs/arm64-v8a/libdsh_bash.so
```

The Gradle `verifyRuntimeAssets` task deliberately fails when these files are
missing. It must never produce an APK containing an empty or fake runtime.

`manifest.json` uses schema version 1:

```json
{
  "schemaVersion": 1,
  "version": "24.18.0",
  "node": {
    "executable": "libdsh_node.so",
    "entrypoint": "runtime/dsh.cjs",
    "arguments": ["web", "--no-open", "--host", "127.0.0.1", "--port", "0"]
  },
  "web": { "host": "127.0.0.1", "port": 0, "path": "/" },
  "bundleSha256": "<SHA-256 of runtime.zip>"
}
```

`runtime.zip` is extracted into app-private storage and is checked for path
traversal before any entry is written. The entrypoint is checked after
extraction. The bundle checksum also identifies the installation, so APK
updates replace DSH files even when Node's version is unchanged. Node, Bash and
esbuild execute from Android's extracted native library directory; modern
Android does not permit execution from writable app data.
Private command-name symlinks expose `node`, `bash` and `esbuild` on PATH and
are rebound after APK updates. DSH's Bash executor runs GNU Bash rather than
Android's system shell. Bash's license and source information are bundled.
Node's complete third-party license notices and the NDK libc++ notices are
included in APK assets alongside the upstream icon license.
The Android root-shell integration uses the Apache-2.0 `libsu` core library.
No startup pass walks or chmods the JavaScript dependency tree; executable
permissions are checked on the package-manager-installed native commands.
Android staging omits optional JavaScript source maps, Windows debug symbols
and Koffi compiler objects. Type declarations, declaration maps, licenses and
plugin documents remain included; the full Termux package retains all files.

Opening the app starts DSH automatically and reveals its real WebView UI.
The official desktop icon is used for the launcher and standard Android launch window;
there is no native start screen, spinner, or retry button. Startup failures
appear in a bottom message whose details can be selected or copied. Sanitized
process and WebView logs are retained in the private cache directory.
The desktop PNG is copied from
[`apps/desktop/resources/icon-windows.png`](https://github.com/deepseek-ai/deepseek-harness/blob/5badb15009ae1756c3afe0ae0cef1faafc290ccc/apps/desktop/resources/icon-windows.png).
The notification uses the official `FishLogo` silhouette. The upstream MIT
license is also included in APK assets.

The launcher enables `--expose-internals` for DSH's main and Worker module
resolvers, since its upstream native resolver helper has no Bionic support.
The default workspace lives under the app-private Documents directory.
The app remembers the successfully bound local port so restarting DSH preserves
the WebView origin, current session and unsent draft. A genuine port conflict
allows one automatic-port fallback; server-side session history stays on disk.
Each process still supplies a fresh authenticated launch URL. The single Activity
receives repeated launcher and notification opens without creating another view,
and hides plugin bootstrap content during a fresh host reload.

The native launcher supplies its Android App UID to attachment validation,
because Android Node does not expose `process.getuid()`. Every checked private
directory must have that owner, stay within the canonical app data directory,
and contain no symbolic link. File and directory fsync barriers remain enabled.

The browser bundles target Android 11's WebView 83. Pinned compatibility
libraries load before DSH's bootstrap, with their licenses included in the
runtime. Its URL feature check covers DSH resource addresses, so old WebViews
use complete URL and URLSearchParams implementations for file previews and
subagent links. Newer WebViews keep their native implementations. File inputs use
Android's system document picker. Session exports use Android's Save As picker
and retain DSH's local authentication without forwarding it through redirects.
Rotation preserves the existing WebView and pending picker callbacks.
Attachment persistence syncs the app-owned directory chain up to Android's
private data directory, using canonical paths. Android 11 forbids hard links
for app processes, so Android publishes complete files with
`renameat2(RENAME_NOREPLACE)` and creates filename aliases by bounded copying.
File and directory sync, atomic publication without overwriting existing files,
and digest-verified deduplication remain enabled. Termux keeps its original
hard-link publication. Attachment filenames are bounded
and leave a separate 44px area for the Remove button.
When the keyboard leaves a short viewport, the chat header and composer use a
compact layout while keeping Chat, Trajectory and the workspace panel available.
Attachment menus fit inside the actual conversation area and scroll their
remaining options. Pane tabs reserve space for their 44px Close buttons and
support horizontal touch scrolling when several tabs are open.

Document previews also adapt to WebView 83's CSS support. Image and PDF zoom
controls use physical positioning when logical insets are unavailable. PDF text
layers preserve selectable glyph positions using supported dimensions and
transforms. Excel's stylesheet uses selectors scoped to its preview container
when native CSS `@scope` is unavailable; newer WebViews retain native scoping.
Excel waits for its Worker to finish initialization before sending the workbook
and starting the existing parse deadline. Initialization has a separate bounded
deadline, and success, errors or cancellation release the Worker and Blob URL.
Workbooks that omit an optional default row height use a visible 20px default;
explicit row heights and hidden rows remain unchanged.

Android 11 does not provide DSH's desktop Bash sandbox. The default workspace
policy remains enabled; a Bash command that requires unsandboxed execution
must request DSH's existing single-command approval. The app does not silently
grant full access or label the Android app UID as workspace isolation.

Generate the runtime from the real package and Node build before assembling an
APK (the staging script fails if either input is missing):

```bash
ANDROID_NDK_HOME=/path/to/ndk \
  ../scripts/build-android-node.sh
ANDROID_NDK_HOME=/path/to/ndk \
  ../scripts/build-android-bash.sh
../scripts/stage-android-runtime.sh   # consumes dist/dsh-termux.tgz
```

`dist/dsh-termux.tgz` is produced by the existing `scripts/build-termux.sh`
flow. The staged package includes the native `node-pty`/`koffi` artifacts from
that build; it is not a placeholder runtime.

## Build

Use JDK 17 or newer, Android SDK 35, and Gradle 8.9 (AGP 8.7.3). From this directory:

```bash
./gradlew --no-daemon --max-workers=3 :app:testDebugUnitTest :app:lintDebug :app:assembleDebug
```

The unit tests cover loopback URL filtering, ZIP traversal rejection, runtime
bundle identity, sanitized startup diagnostics, and authenticated export
streaming with redirect rejection and cancellation. The
APK tasks intentionally stop at `verifyRuntimeAssets` when the real bundle is
not staged. A device or emulator is still required to validate Android process
lifetime, WebView JavaScript behavior, notification permission, and
foreground-service survival across app switching.

For a GitHub build, open a successful **Build DSH Android** run from the
`android` branch and download its `dsh-android-<run number>` artifact from the
Artifacts section. GitHub requires a signed-in account for artifact downloads.
Unzip the artifact to obtain the signed APK and its SHA-256 file.
The Android workflow defaults to the verified DSH `0.2.0-rc.2` and esbuild
`0.28.2`; its manual version input can select another upstream DSH release.
CI currently generates a fresh testing signing key for each run. To install an
APK from a different run, export any needed sessions and uninstall the previous
CI build first; Android does not allow updates signed by a different key.

For the real WebUI, run a disposable DSH service with a temporary HOME and
save its authenticated launch URL in a private text file. With Python Playwright
and Chromium installed:

```bash
python ../scripts/verify-live-android-ui.py \
  --url-file /path/to/private-test-url.txt \
  --output-dir /path/to/ui-results \
  --chromium /usr/bin/chromium
```

This verifies the actual DSH page at phone widths, menus, and saved theme/language
settings. It does not substitute for APK Activity/WebView or model-request tests.

For controlled chat and Bash approval tests, start the loopback-only Messages
fixture and use another disposable DSH HOME:

```bash
python ../scripts/test-support/messages-fixture-server.py --help
python ../scripts/test-support/verify-controlled-chat.py \
  --url-file /path/to/private-test-url.txt \
  --fixture-file /path/to/fixture/server.json \
  --output-dir /path/to/chat-results \
  --chromium /usr/bin/chromium
```

This drives DSH's real provider settings, streams, Stop button, queue,
single-command approval, rejection, and saved conversations. Responses come
from the fixture; it does not verify real DeepSeek inference. APK lifecycle,
IME, file dialogs, and native addons must also be tested on Android.

The actual onboarding and upstream-model-picker components can be checked with
an isolated 500-model catalog and no real credentials. After installing Python
Playwright and Chromium, run from the repository root against the fully staged
APK package (the fixture does not modify that package):

```bash
python3 scripts/test-android-model-dialogs.py /path/to/staged/dsh \
  --fixture-dir /tmp/dsh-model-dialogs-qa
```

`--prepare-only` produces the same production-component fixture for WebView
testing. Browser resizing checks layout; physical Android IME dismissal and
touch gestures still need device/emulator verification. OEM island/atomic
notification presentation must be checked on compatible ColorOS 16, HyperOS,
or OriginOS devices.
