#!/usr/bin/env node
// Fast, package-free checks for the Android mobile capability contract.
// The full host integration test runs after the real DSH package is built;
// these checks run first so malformed Android tool/settings fixtures fail
// before the expensive native runtime builds begin.
import { readFile } from "node:fs/promises";
import { resolve } from "node:path";

const root = resolve(new URL("..", import.meta.url).pathname);
const files = new Map();
async function source(path) {
  const value = await readFile(resolve(root, path), "utf8");
  files.set(path, value);
  return value;
}
function requireText(file, text, message = `${file} is missing ${JSON.stringify(text)}`) {
  const value = files.get(file);
  if (!value.includes(text)) throw new Error(message);
}
function requireAll(file, values) {
  for (const value of values) requireText(file, value);
}

await Promise.all([
  source("scripts/android-mobile-tools.mjs"),
  source("scripts/android-settings-ui.js"),
  source("scripts/patch-android-settings-ui.mjs"),
  source("android/app/src/main/java/io/github/sunflower2333/dsh/MobileProtocol.kt"),
  source("android/app/src/main/java/io/github/sunflower2333/dsh/AndroidCapabilityManager.kt"),
  source("android/app/src/main/java/io/github/sunflower2333/dsh/AndroidSettingsBridge.kt"),
]);

requireAll("scripts/android-mobile-tools.mjs", [
  '"adb_status"', '"adb_shell"', '"root_shell"', '"ui_xml"',
  'accessibility_xml', 'adb_uiautomator', 'argvSchema', 'xmlSchema',
]);
requireAll("scripts/android-settings-ui.js", [
  '"open-adb-file"', '"set-adb-enabled"', '"set-root-enabled"', '"set-adb-target"',
  "adbConfigured", "rootAvailable", "adbTarget",
]);
requireAll("scripts/patch-android-settings-ui.mjs", [
  'data-dsh-android-capabilities', 'androidSecurity.heading', 'androidSecurity.importAdb',
  'androidSecurity.enableRoot', 'androidSecurity.targetPlaceholder',
]);
requireAll("android/app/src/main/java/io/github/sunflower2333/dsh/MobileProtocol.kt", [
  'ADB_STATUS', 'ADB_SHELL', 'ROOT_SHELL', 'UI_XML',
  'adb_status', 'adb_shell', 'root_shell', 'ui_xml',
]);
requireAll("android/app/src/main/java/io/github/sunflower2333/dsh/AndroidCapabilityManager.kt", [
  'setAdbEnabled', 'setRootEnabled', 'setAdbTarget', 'importAdb', 'executeAdb', 'executeRoot',
]);
requireAll("android/app/src/main/java/io/github/sunflower2333/dsh/AndroidSettingsBridge.kt", [
  '"open-adb-file"', '"set-adb-enabled"', '"set-root-enabled"', '"set-adb-target"',
  '"adbConfigured"', '"rootAvailable"', '"adbTarget"',
]);

console.log("Android mobile capability and settings fixtures are present.");
