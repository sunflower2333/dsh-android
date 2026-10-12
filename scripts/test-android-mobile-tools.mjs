#!/usr/bin/env node
// HOST integration tests against the real installed DSH services, with a fixture native socket.
// Attachment bytes use the real Linux store; Android durable-root/libc publication requires ARM64 QA.
import assert from "node:assert/strict";
import { after, test } from "node:test";
import http from "node:http";
import { performance } from "node:perf_hooks";
import { randomBytes, randomUUID, createHash } from "node:crypto";
import { mkdtemp, mkdir, readFile, writeFile, symlink, copyFile, rm } from "node:fs/promises";
import { join, resolve } from "node:path";
import { tmpdir } from "node:os";
import { pathToFileURL } from "node:url";
import { patchAndroidMobileTools } from "./patch-android-mobile-tools.mjs";

const packageRoot = process.argv[2];
if (!packageRoot) throw new Error("usage: node scripts/test-android-mobile-tools.mjs <dsh-package-directory>");
const work = await mkdtemp(join(tmpdir(), "dsh-mobile-tools-test-"));
await symlink(resolve(packageRoot, "node_modules"), join(work, "node_modules"));
await copyFile(new URL("./android-mobile-tools.mjs", import.meta.url), join(work, "helper.mjs"));
// Keep the helper and all SDK services in one module namespace, including when
// the builder preserves the composed SDK's symlinks to retain native overrides.
const moduleAt = name => import(pathToFileURL(join(work, "node_modules/@deepseek-ai", name, "lib/index.js")));
const [{ Context }, { SystemPrompt }, { ToolRuntime }, { LocalAttachmentStore }, { createScope }, { LlmRuntime, LlmAdapter, createUserMessage, HarnessError }, plugin] = await Promise.all([
  moduleAt("cordis"), moduleAt("dsh-system-prompt"), moduleAt("dsh-tools"), moduleAt("dsh-attachment-local"), moduleAt("dsh-scope"), moduleAt("dsh-llm"), import(pathToFileURL(join(work, "helper.mjs"))),
]);
const { LIMITS, createMobileTransport } = plugin;
const oldEnv = Object.fromEntries(["DSH_ANDROID", "DSH_ANDROID_MOBILE_SOCKET", "DSH_ANDROID_MOBILE_TOKEN"].map(key => [key, process.env[key]]));
const resources = [];
after(async () => {
  for (const close of resources.reverse()) await close();
  for (const [key, value] of Object.entries(oldEnv)) { if (value === undefined) delete process.env[key]; else process.env[key] = value; }
  await rm(work, { recursive: true, force: true });
});
const sleep = ms => new Promise(resolve => setTimeout(resolve, ms));
const TOKEN = randomBytes(32).toString("hex"); // Fixture credentials only, never printed.
const png = Buffer.from("iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAAC0lEQVR4nGNgAAIAAAUAAXpeqz8AAAAASUVORK5CYII=", "base64");
const names = ["mobile_status", "mobile_observe", "mobile_click", "mobile_type", "mobile_scroll", "mobile_swipe", "mobile_back", "mobile_stop", "mobile_list_apps", "mobile_open_app", "mobile_adb_status", "mobile_adb_shell", "mobile_root_shell", "mobile_ui_xml"];
const SESSION = randomUUID();
const APPS = [{ packageName: "com.android.settings", label: "Settings" }, { packageName: "io.github.fixture.notes", label: "Notes" }];
const XML = '<?xml version="1.0"?><hierarchy rotation="0"><node class="android.widget.Button" password="false" text="Open" content-desc="Open"/></hierarchy>';
function capabilityStatus() { return { adbConfigured: true, adbEnabled: true, adbConnected: true, adbTarget: null, rootAvailable: true, rootEnabled: true }; }
function shellResult(action, args) { return { exitCode: 0, stdout: action === "ui_xml" ? XML : "fixture-output", stderr: "", truncated: false, ...(action === "ui_xml" ? { source: "adb_uiautomator", target: args.serial ?? null } : action === "adb_shell" ? { target: args.serial ?? null } : {}) }; }
function appList(sessionId = SESSION) { return { sessionId, apps: APPS, truncated: false, foregroundOnly: true }; }
function status(active = true) { return { enabled: true, connected: true, active, sessionId: active ? SESSION : null, reason: active ? "user_grant" : "user_paused", currentPackage: "fixture.screen" }; }
function observation(args) {
  return { sessionId: args.sessionId, observationId: randomUUID(), sampledAtMs: Date.now(), display: { widthPx: 360, heightPx: 640, rotation: 0 },
    window: { id: 1, packageName: "fixture.screen" }, nodes: [
      { id: "n0", className: "Button", viewId: "fixture:id/button", packageName: "fixture.screen", bounds: { left: 0, top: 0, right: 100, bottom: 44 }, text: "Open", clickable: true, editable: false, password: false, enabled: true, visible: true, scrollable: false, parentId: "n3", actions: ["click"] },
      { id: "n1", className: "EditText", viewId: "fixture:id/input", packageName: "fixture.screen", bounds: { left: 0, top: 44, right: 100, bottom: 88 }, text: "old", clickable: true, editable: true, password: false, enabled: true, visible: true, scrollable: false, parentId: "n3", actions: ["click", "set_text", "focus"] },
      { id: "n2", className: "EditText", viewId: "fixture:id/password", packageName: "fixture.screen", bounds: { left: 0, top: 88, right: 100, bottom: 132 }, text: "fixture-secret", description: "fixture-secret", clickable: true, editable: true, password: true, enabled: true, visible: true, scrollable: false, parentId: "n3", actions: ["click", "set_text"] },
      { id: "n3", className: "ScrollView", viewId: "fixture:id/list", packageName: "fixture.screen", bounds: { left: 0, top: 0, right: 360, bottom: 640 }, clickable: false, editable: false, password: false, enabled: true, visible: true, scrollable: true, parentId: null, actions: ["scroll_forward", "scroll_backward"] },
    ], truncated: false, screenshotRequested: args.screenshot, screenshot: args.screenshot ? { mimeType: "image/png", base64: png.toString("base64"), widthPx: 1, heightPx: 1, capturedAtMs: Date.now() } : null };
}
function androidClippedObservation(args, omitClippedNodes) {
  // Geometry reproduced from the actual h28 Android 11 Settings response:
  // 720x1280, window 1127, 50 nodes; nodes 31..49 had top > bottom.
  // Other node data is fixture data; no captured screen text is retained.
  const value = observation(args);
  value.sampledAtMs = 9938035;
  value.display = { widthPx: 720, heightPx: 1280, rotation: 0 };
  value.window = { id: 1127, packageName: "com.android.settings" };
  value.nodes = Array.from({ length: 31 }, (_, index) => ({
    id: `n${index}`, className: index === 30 ? "android.widget.EditText" : "android.widget.TextView",
    viewId: `com.android.settings:id/fixture_${index}`, packageName: "com.android.settings",
    bounds: { left: 0, top: index * 40, right: 720, bottom: index * 40 + 40 },
    text: `Fixture node ${index}`, description: `Fixture description ${index} 🌻`,
    clickable: true, editable: index === 30, password: false,
  }));
  if (!omitClippedNodes) {
    const captured = [
      [144, 1298, 688], [0, 1344, 720], [144, 1344, 267], [144, 1344, 584],
      [0, 1344, 720], [144, 1344, 259], [144, 1344, 429], [0, 1344, 720],
      [144, 1344, 280], [144, 1344, 386], [0, 1344, 720], [144, 1344, 325],
      [144, 1344, 681], [0, 1344, 720], [144, 1344, 250], [144, 1344, 573],
      [0, 1344, 720], [144, 1344, 471], [144, 1344, 492],
    ];
    value.nodes.push(...captured.map(([left, top, right], index) => ({
      id: `n${index + 31}`, className: "android.widget.TextView", viewId: "", packageName: "com.android.settings",
      bounds: { left, top, right, bottom: 1280 }, clickable: false, editable: false, password: false,
    })));
  }
  return value;
}
async function bridge(handler) {
  const socket = "dsh-mobile-test-" + randomBytes(12).toString("hex");
  const calls = [];
  const server = http.createServer(async (req, res) => {
    const parts = [];
    for await (const chunk of req) parts.push(chunk);
    const call = { path: req.url, method: req.method, auth: req.headers.authorization, args: JSON.parse(Buffer.concat(parts).toString("utf8")) };
    calls.push(call);
    if (call.auth !== `Bearer ${TOKEN}`) { res.writeHead(401); res.end(JSON.stringify({ ok: false, error: { code: "unauthorized", message: TOKEN } })); return; }
    try {
      const result = await handler(call, res);
      if (result !== undefined && !res.destroyed) { res.writeHead(200, { "Content-Type": "application/json" }); res.end(JSON.stringify(result)); }
    } catch { if (!res.destroyed) { res.writeHead(500); res.end(JSON.stringify({ ok: false, error: { code: "internal", message: TOKEN } })); } }
  });
  await new Promise((resolve, reject) => { server.once("error", reject); server.listen("\0" + socket, resolve); });
  resources.push(() => new Promise(resolve => { server.closeAllConnections(); server.close(resolve); }));
  return { calls, server, env: { DSH_ANDROID_MOBILE_SOCKET: socket, DSH_ANDROID_MOBILE_TOKEN: TOKEN } };
}
async function runtime(handler, { modalities = ["text"], android = true, modelInfoError = false } = {}) {
  const native = await bridge(handler ?? (call => {
    const action = call.path.split("/").at(-1);
    return { ok: true, value: action === "observe" ? observation(call.args) : action === "status" || action === "stop" ? status(action !== "stop") : action === "adb_status" ? capabilityStatus() : ["adb_shell", "root_shell", "ui_xml"].includes(action) ? shellResult(action, call.args) : action === "list_apps" ? appList(call.args.sessionId) : { performed: true, action, observationId: call.args.observationId, ...(action === "open_app" ? { sessionId: call.args.sessionId, packageName: call.args.packageName, foregroundOnly: true } : {}) } };
  }));
  Object.assign(process.env, native.env, { DSH_ANDROID: android ? "1" : "0" });
  const ctx = new Context();
  await ctx.plugin(SystemPrompt, {});
  await ctx.plugin(ToolRuntime, {});
  await ctx.plugin(LocalAttachmentStore, { dshHome: join(work, "home-" + randomUUID()) });
  const resolutions = [];
  await ctx.plugin(LlmRuntime);
  class FixtureAdapter extends LlmAdapter {
    async resolveModel(provider, model, signal) {
      signal?.throwIfAborted(); resolutions.push({ provider, model });
      if (modelInfoError) throw new Error("fixture metadata unavailable");
      return { provider, id: model, name: model, ...(modalities === null ? {} : { inputModalities: modalities }) };
    }
    async *stream() { throw new Error("No external model requests are permitted by this fixture."); }
  }
  const adapter = new FixtureAdapter();
  ctx.llm.registerAdapter(["fixture-provider", "fallback"], adapter);
  const fiber = ctx.plugin(plugin);
  await fiber;
  // The APK-only plugin has captured the authenticated fixture UDS transport.
  // This HOST is Linux: use the actual SDK's Linux attachment publication, not
  // its Android app-owned boundary/ARM64 Koffi path. No SDK bytes or refs are replaced.
  process.env.DSH_ANDROID = "0";
  const presetKey = { id: "standard-fixture" };
  const preset = createScope(ctx, presetKey);
  const agent = { id: randomUUID(), options: { provider: "fallback", model: "fallback-model" }, session: { requestHeader: () => ({ config: { provider: "fixture-provider", model: "fixture-model" } }) } };
  const scoped = createScope(preset.ctx, agent, { parent: presetKey });
  resources.push(async () => { await scoped.dispose(); await preset.dispose(); await ctx.fiber.dispose(); });
  const execute = (name, args = {}, signal = new AbortController().signal, owner = agent) => ctx.tools.execute({ name, arguments: args, callId: randomUUID(), signal, agent: owner });
  const observe = async (options = {}) => {
    const result = await execute("mobile_observe", { sessionId: SESSION, ...options });
    assert.equal(result.isError, false, JSON.stringify(result.error)); return result.value;
  };
  return { ctx, agent, execute, observe, native, resolutions, fiber, adapter };
}
function code(result) { return result.error?.info?.code; }

test("the helper and actual SDK share HarnessError identity and preserve structured safe error codes", async () => {
  await assert.rejects(createMobileTransport({})("status", {}), error => {
    assert.ok(error instanceof HarnessError);
    assert.equal(error.code, "MOBILE_UNAVAILABLE");
    return true;
  });
  const r = await runtime();
  const result = await r.execute("mobile_click", { sessionId: SESSION, observationId: "missing", nodeId: "n0" });
  assert.equal(result.isError, true);
  assert.deepEqual(result.error.info, { name: "HarnessError", code: "MOBILE_STALE_OBSERVATION" });
  assert.equal(r.native.calls.length, 0);
  assert.ok(!JSON.stringify(result).includes(TOKEN));
});

test("APK patch uses the real web-app plugin entry, is idempotent, and preserves canonical input", async () => {
  const originalPath = resolve(packageRoot, "node_modules/@deepseek-ai/dsh-web-app/lib/index.js");
  const before = await readFile(originalPath, "utf8");
  const fixture = join(work, "patched-package");
  const lib = join(fixture, "node_modules/@deepseek-ai/dsh-web-app/lib");
  await mkdir(lib, { recursive: true });
  await writeFile(join(fixture, "node_modules/@deepseek-ai/dsh-web-app/package.json"), '{"type":"module"}');
  await writeFile(join(lib, "index.js"), before);
  await patchAndroidMobileTools(fixture);
  const first = await readFile(join(lib, "index.js"), "utf8");
  const helper = await readFile(join(lib, "android-mobile-tools.js"));
  await patchAndroidMobileTools(fixture);
  assert.equal(await readFile(join(lib, "index.js"), "utf8"), first);
  assert.deepEqual(await readFile(join(lib, "android-mobile-tools.js")), helper);
  assert.equal(await readFile(originalPath, "utf8"), before);
  assert.equal(first.match(/ctx\.plugin\(AndroidMobileTools\)/g).length, 1);
  const mobileLine = '  if (process.env.DSH_ANDROID === "1") ctx.plugin(AndroidMobileTools);\n';
  const hostLine = '  if (process.env.DSH_ANDROID === "1") ctx.plugin(AndroidHostEvents);\n';
  for (const lines of [hostLine + mobileLine, mobileLine + hostLine]) {
    const composed = first.replace(hostLine, "").replace(mobileLine, lines);
    await writeFile(join(lib, "index.js"), composed);
    await patchAndroidMobileTools(fixture);
    assert.equal(await readFile(join(lib, "index.js"), "utf8"), composed);
  }
  await writeFile(join(lib, "index.js"), first.replace(mobileLine, mobileLine + mobileLine));
  await assert.rejects(patchAndroidMobileTools(fixture), /Unsupported/);
  await writeFile(join(lib, "index.js"), first);
  if (first.includes('import * as AndroidHostEvents from "./android-host-events.js";'))
    await copyFile(new URL("./android-host-events.mjs", import.meta.url), join(lib, "android-host-events.js"));
  const entry = await import(pathToFileURL(join(lib, "index.js")));
  let captured;
  const sentinel = new Error("fixture-capture");
  process.env.DSH_ANDROID = "1";
  assert.throws(() => entry.apply({ plugin(module) {
    if (module.name === "android-mobile-tools") { captured = module; throw sentinel; }
  } }, {}), error => error === sentinel);
  assert.equal(captured.name, "android-mobile-tools");
  captured = undefined;
  process.env.DSH_ANDROID = "0";
  assert.throws(() => entry.apply({ plugin(module) { captured = module; } }, {}));
  assert.equal(captured, undefined);
  await writeFile(join(lib, "index.js"), before.replace("function apply(ctx, config) {", "function apply_changed(ctx, config) {"));
  await assert.rejects(patchAndroidMobileTools(fixture), /Unsupported/);
});
test("real DSH prompt/tool registry exposes all fourteen tools through standing preset and agent scopes without secrets", async () => {
  const r = await runtime();
  const assembly = await r.ctx.systemPrompt.assemble({ scope: r.agent });
  assert.deepEqual(assembly.tools.map(tool => tool.name).filter(name => names.includes(name)).sort(), [...names].sort());
  assert.match(JSON.stringify(assembly), /tools:android-mobile/);
  const publicSurface = JSON.stringify(assembly);
  assert.ok(!publicSurface.includes(TOKEN) && !publicSurface.includes(r.native.env.DSH_ANDROID_MOBILE_SOCKET));
  for (const name of names) {
    const tool = r.ctx.tools.get(name, r.agent);
    assert.ok(tool); assert.equal(tool.isConcurrencySafe({}), false);
    assert.ok(!JSON.stringify(tool.parameters).includes("token"));
  }
  let policyCount = 0;
  r.ctx.on("tools/pre-execute", (_exec, _next) => { policyCount++; return { kind: "deny", reason: "fixture existing policy" }; });
  const denied = await r.execute("mobile_status");
  assert.equal(denied.isError, true); assert.equal(policyCount, 1); assert.equal(r.native.calls.length, 0);
});

test("ADB/root/UI XML tools enforce bounded argv, source/target identity, redaction and stable native errors", async () => {
  const r = await runtime(call => {
    const action = call.path.split("/").at(-1);
    if (action === "adb_status") return { ok: true, value: capabilityStatus() };
    if (action === "ui_xml" && call.args.source === "accessibility_xml") return { ok: true, value: {
      sessionId: call.args.sessionId, source: "accessibility_xml", xml: '<?xml version="1.0"?><hierarchy><node password="true" text="fixture-secret" content-desc="fixture-secret"/></hierarchy>', truncated: false, nodes: 1,
      observationId: randomUUID(), windowId: 1, packageName: "fixture.screen",
    } };
    if (action === "ui_xml") return { ok: true, value: { ...shellResult(action, call.args), source: "adb_uiautomator", target: call.args.serial ?? null } };
    if (action === "root_shell") return { ok: true, value: shellResult(action, call.args) };
    if (action === "adb_shell") return { ok: true, value: shellResult(action, call.args) };
    return { ok: false, error: { code: "INTERNAL", message: TOKEN } };
  });
  assert.deepEqual((await r.execute("mobile_adb_status")).value, capabilityStatus());
  const shell = await r.execute("mobile_adb_shell", { sessionId: SESSION, serial: "usb-1", argv: ["shell", "getprop"], timeoutMs: 1000 });
  assert.equal(shell.isError, false, JSON.stringify(shell.error));
  assert.deepEqual(shell.value, { exitCode: 0, stdout: "fixture-output", stderr: "", truncated: false, target: "usb-1" });
  assert.deepEqual(r.native.calls.at(-1).args, { sessionId: SESSION, serial: "usb-1", argv: ["shell", "getprop"], timeoutMs: 1000 });
  const omitted = await r.execute("mobile_adb_shell", { sessionId: SESSION, serial: null, argv: ["shell", "getprop"] });
  assert.equal(omitted.isError, false, JSON.stringify(omitted.error));
  assert.deepEqual(r.native.calls.at(-1).args, { sessionId: SESSION, argv: ["shell", "getprop"] });
  const root = await r.execute("mobile_root_shell", { sessionId: SESSION, argv: ["id"] });
  assert.equal(root.isError, false); assert.equal(root.value.stdout, "fixture-output");
  const accessibility = await r.execute("mobile_ui_xml", { sessionId: SESSION, source: "accessibility_xml" });
  assert.equal(accessibility.isError, false, JSON.stringify(accessibility.error));
  assert.match(accessibility.value.xml, /\[REDACTED\]/); assert.ok(!accessibility.value.xml.includes("fixture-secret"));
  const adbXml = await r.execute("mobile_ui_xml", { sessionId: SESSION, source: "adb_uiautomator", serial: "usb-1" });
  assert.equal(adbXml.isError, false, JSON.stringify(adbXml.error)); assert.equal(adbXml.value.target, "usb-1"); assert.equal(adbXml.value.nodes, 1);
  const adbXmlDefault = await r.execute("mobile_ui_xml", { sessionId: SESSION, source: "adb_uiautomator", serial: null });
  assert.equal(adbXmlDefault.isError, false, JSON.stringify(adbXmlDefault.error));
  assert.deepEqual(r.native.calls.at(-1).args, { sessionId: SESSION, source: "adb_uiautomator" });
  const calls = r.native.calls.length;
  for (const args of [
    { sessionId: SESSION, argv: [] }, { sessionId: SESSION, argv: ["shell", "echo;id"] },
    { sessionId: SESSION, argv: ["shell"], timeoutMs: 99 }, { sessionId: SESSION, argv: ["shell"], serial: "usb/1" },
  ]) { assert.equal(code(await r.execute("mobile_adb_shell", args)), "MOBILE_INVALID_REQUEST"); }
  assert.equal(r.native.calls.length, calls);
  const malformed = await runtime(call => ({ ok: true, value: { sessionId: call.args.sessionId, source: "accessibility_xml", xml: "<hierarchy><node>", truncated: false, nodes: 1 } }));
  assert.equal(code(await malformed.execute("mobile_ui_xml", { sessionId: SESSION, source: "accessibility_xml" })), "MOBILE_INVALID_RESPONSE");
  const errors = await runtime(() => ({ ok: false, error: { code: "ADB_DISABLED", message: TOKEN } }));
  const disabled = await errors.execute("mobile_adb_shell", { sessionId: SESSION, argv: ["shell"] });
  assert.equal(code(disabled), "MOBILE_ADB_DISABLED"); assert.ok(!JSON.stringify(disabled).includes(TOKEN));
});

test("the actual DSH AgentLoop discovers, dispatches and logs mobile tools through its model request/result cycle", async () => {
  const r = await runtime(undefined, { modalities: ["text", "image"] });
  const [{ AgentRegistry }, { SessionStore }, { SessionProjectionRegistry }, { AgentLoop }] = await Promise.all([
    moduleAt("dsh-agent"), moduleAt("dsh-session"), moduleAt("dsh-session-projection"), moduleAt("dsh-agent-loop"),
  ]);
  await r.ctx.plugin(AgentRegistry);
  await r.ctx.plugin(SessionStore);
  await r.ctx.plugin(SessionProjectionRegistry);
  await r.ctx.plugin(AgentLoop, {});
  const requests = [];
  const errors = [];
  r.ctx.on("agent/error", event => errors.push(event.error ?? event));
  r.adapter.stream = async function* (request) {
    requests.push(request);
    const step = requests.length;
    assert.deepEqual(request.tools.map(tool => tool.name).filter(name => names.includes(name)).sort(), [...names].sort());
    const previous = request.messages.filter(message => message.role === "tool").at(-1);
    const value = previous === undefined ? undefined : JSON.parse(previous.content.find(block => block.type === "text").text.split("\n")[0]);
    const calls = [
      ["mobile_status", {}], ["mobile_observe", { sessionId: value?.sessionId, screenshot: true }],
      ["mobile_click", { sessionId: value?.sessionId, observationId: value?.observationId, nodeId: "n0" }],
      ["mobile_observe", { sessionId: SESSION, screenshot: true }], ["mobile_stop", {}],
    ];
    if (step <= calls.length) {
      if (step === 3 || step === 5) assert.ok(previous.content.some(block => block.type === "image" && block.attachment.attachmentId));
      const [name, args] = calls[step - 1];
      const id = randomUUID();
      const block = { type: "tool-call", id, name, arguments: JSON.stringify(args) };
      yield { type: "block-start", index: 0, blockType: "tool-call" };
      yield { type: "tool-call-delta", index: 0, id, name, argumentsDelta: block.arguments };
      yield { type: "block-end", index: 0, block };
      yield { type: "finish", reason: { kind: "tool-calls" } };
    } else {
      assert.equal(step, 6);
      yield { type: "block-start", index: 0, blockType: "text" };
      yield { type: "text-delta", index: 0, text: "Fixture mobile task stopped." };
      yield { type: "block-end", index: 0, block: { type: "text", text: "Fixture mobile task stopped." } };
      yield { type: "finish", reason: { kind: "stop" } };
    }
  };
  const agent = await r.ctx.agentLoop.create("mobile-loop-" + randomUUID(), { provider: "fixture-provider", model: "fixture-model" });
  assert.equal(r.ctx.agents.get(agent.id), agent);
  agent.followup(createUserMessage({ content: [{ type: "text", text: "Fixture only: observe, click Open, verify, then stop." }] }));
  let timer;
  try { await Promise.race([agent.whenIdle(), new Promise((_, reject) => { timer = setTimeout(() => { agent.cancel({ kind: "user" }); reject(new Error("Fixture AgentLoop did not complete within 5 seconds.")); }, 5000); })]); }
  finally { clearTimeout(timer); }
  assert.deepEqual(errors, []);
  assert.equal(requests.length, 6);
  const log = agent.session.snapshotEvents();
  assert.deepEqual(log.filter(event => event.type === "tool/call").map(event => event.data.name), ["mobile_status", "mobile_observe", "mobile_click", "mobile_observe", "mobile_stop"]);
  assert.equal(log.filter(event => event.type === "tool/result").length, 5);
  assert.deepEqual(r.native.calls.map(call => call.path.split("/").at(-1)), ["status", "observe", "click", "observe", "stop"]);
  assert.ok(!JSON.stringify(log).includes(TOKEN) && !JSON.stringify(log).includes(png.toString("base64")));
});
test("a text-only provider drives the actual AgentLoop through launcher, node typing/click/scroll/back without any image", async () => {
  let opened = false, text = "old", screenState = "Open";
  const r = await runtime(call => {
    const action = call.path.split("/").at(-1);
    if (action === "status" || action === "stop") return { ok: true, value: status(action !== "stop") };
    if (action === "list_apps") return { ok: true, value: appList(call.args.sessionId) };
    if (action === "observe") {
      const value = observation(call.args);
      if (opened) { value.window = { id: 2, packageName: "io.github.fixture.notes" }; value.nodes.forEach(node => { node.packageName = "io.github.fixture.notes"; }); }
      value.nodes[0].text = screenState; value.nodes[1].text = text;
      assert.equal(call.args.screenshot, false);
      return { ok: true, value };
    }
    if (action === "open_app") opened = true;
    if (action === "type") text = call.args.text;
    if (action === "click") screenState = "Opened";
    if (action === "scroll") screenState = "Scrolled";
    if (action === "back") screenState = "Returned";
    return { ok: true, value: { performed: true, action, observationId: call.args.observationId,
      ...(action === "open_app" ? { sessionId: call.args.sessionId, packageName: call.args.packageName, foregroundOnly: true } : {}) } };
  }, { modalities: ["text"] });
  const [{ AgentRegistry }, { SessionStore }, { SessionProjectionRegistry }, { AgentLoop }] = await Promise.all([
    moduleAt("dsh-agent"), moduleAt("dsh-session"), moduleAt("dsh-session-projection"), moduleAt("dsh-agent-loop")
  ]);
  await r.ctx.plugin(AgentRegistry); await r.ctx.plugin(SessionStore); await r.ctx.plugin(SessionProjectionRegistry); await r.ctx.plugin(AgentLoop, {});
  const requests = [], errors = []; r.ctx.on("agent/error", event => errors.push(event.error ?? event));
  r.adapter.stream = async function* (request) {
    requests.push(request); const step = requests.length;
    assert.ok(!request.messages.some(message => message.content.some(block => block.type === "image")), "text-only model receives no image");
    const previous = request.messages.filter(message => message.role === "tool").at(-1);
    const value = previous ? JSON.parse(previous.content.find(block => block.type === "text").text) : undefined;
    const binding = { sessionId: SESSION, observationId: value?.observationId };
    if ([5, 7, 9, 11, 13].includes(step)) {
      assert.ok(previous.content.some(block => block.type === "text" && block.text.includes("Android accessibility layout")));
      assert.equal(value.window.packageName, "io.github.fixture.notes");
      assert.equal(value.nodes[2].text, undefined); assert.equal(value.nodes[2].description, undefined);
    }
    if (step === 7) assert.equal(value.nodes[1].text, "Fixture mobile task");
    if (step === 9) assert.equal(value.nodes[0].text, "Opened");
    if (step === 11) assert.equal(value.nodes[0].text, "Scrolled");
    if (step === 13) assert.equal(value.nodes[0].text, "Returned");
    const calls = [
      ["mobile_status", {}], ["mobile_list_apps", { sessionId: value?.sessionId }],
      ["mobile_open_app", { sessionId: SESSION, packageName: "io.github.fixture.notes" }],
      ["mobile_observe", { sessionId: SESSION }], ["mobile_type", { ...binding, nodeId: "n1", text: "Fixture mobile task" }],
      ["mobile_observe", { sessionId: SESSION }], ["mobile_click", { ...binding, nodeId: "n0" }],
      ["mobile_observe", { sessionId: SESSION }], ["mobile_scroll", { ...binding, nodeId: "n3", direction: "forward" }],
      ["mobile_observe", { sessionId: SESSION }], ["mobile_back", binding],
      ["mobile_observe", { sessionId: SESSION }], ["mobile_stop", {}]
    ];
    if (step <= calls.length) {
      const [name, args] = calls[step - 1], id = randomUUID();
      const block = { type: "tool-call", id, name, arguments: JSON.stringify(args) };
      yield { type: "block-start", index: 0, blockType: "tool-call" };
      yield { type: "tool-call-delta", index: 0, id, name, argumentsDelta: block.arguments };
      yield { type: "block-end", index: 0, block }; yield { type: "finish", reason: { kind: "tool-calls" } };
    } else {
      assert.equal(step, 14); const block = { type: "text", text: "Fixture text-only mobile task verified and stopped." };
      yield { type: "block-start", index: 0, blockType: "text" }; yield { type: "text-delta", index: 0, text: block.text };
      yield { type: "block-end", index: 0, block }; yield { type: "finish", reason: { kind: "stop" } };
    }
  };
  const agent = await r.ctx.agentLoop.create("text-mobile-loop-" + randomUUID(), { provider: "fixture-provider", model: "text-model" });
  agent.followup(createUserMessage({ content: [{ type: "text", text: "Fixture only: open Notes, type, click, scroll, Back, verify each result, then stop." }] }));
  let timer;
  try { await Promise.race([agent.whenIdle(), new Promise((_, reject) => { timer = setTimeout(() => { agent.cancel({ kind: "user" }); reject(new Error("Text-only fixture AgentLoop exceeded 5 seconds.")); }, 5000); })]); }
  finally { clearTimeout(timer); }
  assert.deepEqual(errors, []); assert.equal(requests.length, 14);
  const expected = ["status", "list_apps", "open_app", "observe", "type", "observe", "click", "observe", "scroll", "observe", "back", "observe", "stop"];
  assert.deepEqual(r.native.calls.map(call => call.path.split("/").at(-1)), expected);
  assert.deepEqual(r.native.calls.find(call => call.path.endsWith("/open_app")).args, { sessionId: SESSION, packageName: "io.github.fixture.notes" });
  const log = agent.session.snapshotEvents(); assert.equal(log.filter(event => event.type === "tool/call").length, 13);
  assert.equal(log.filter(event => event.type === "tool/result").length, 13);
  assert.ok(!JSON.stringify(log).includes(TOKEN) && !JSON.stringify(log).includes("fixture-secret") && !JSON.stringify(log).includes(png.toString("base64")));
});
test("desktop/Termux never registers mobile tools", async () => {
  const r = await runtime(undefined, { android: false });
  assert.deepEqual((await r.ctx.systemPrompt.assemble({ scope: r.agent })).tools.filter(tool => names.includes(tool.name)), []);
  assert.equal(r.native.calls.length, 0);
});
test("status/stop use authenticated real UDS POSTs and reflect native grant, including paused state", async () => {
  const r = await runtime();
  const current = await r.execute("mobile_status");
  assert.equal(current.isError, false); assert.deepEqual(current.value, status());
  const stopped = await r.execute("mobile_stop");
  assert.equal(stopped.isError, false); assert.equal(stopped.value.active, false); assert.equal(stopped.value.sessionId, null);
  assert.ok(stopped.content.some(block => block.type === "text" && /Mobile use/.test(block.text)));
  for (const call of r.native.calls) { assert.equal(call.method, "POST"); assert.deepEqual(call.args, {}); assert.equal(call.auth, `Bearer ${TOKEN}`); }
});
test("nonvision, unknown and unresolved model routes request no screenshot and return only real nodes", async () => {
  for (const options of [{ modalities: ["text"] }, { modalities: null }, { modelInfoError: true }]) {
    const r = await runtime(undefined, options);
    const value = await r.observe({ screenshot: true });
    assert.deepEqual(r.resolutions, [{ provider: "fixture-provider", model: "fixture-model" }]);
    assert.equal(r.native.calls[0].args.screenshot, false); assert.equal(value.screenshotRequested, false);
    assert.equal(value.screenshotOmittedReason, "text_only_model"); assert.equal(value.image, undefined);
    assert.equal(value.nodes[0].text, "Open"); assert.equal(value.nodes[2].text, undefined); assert.equal(value.nodes[2].description, undefined);
  }
});
test("vision observation stores a real durable DSH image attachment and renders its actual reference, without base64", async () => {
  const r = await runtime(undefined, { modalities: ["text", "image"] });
  const result = await r.execute("mobile_observe", { sessionId: SESSION, screenshot: true });
  assert.equal(result.isError, false, JSON.stringify(result.error));
  const image = result.value.image;
  assert.ok(image.attachmentId); assert.equal(image.width, 1); assert.equal(image.height, 1);
  assert.equal(r.native.calls[0].args.screenshot, true);
  const stored = await r.ctx.attachments.readImage(image);
  assert.ok(stored);
  assert.equal(createHash("sha256").update(await readFile(r.ctx.attachments.imageHostPath(image))).digest("hex"), createHash("sha256").update(png).digest("hex"));
  assert.deepEqual(result.content.find(block => block.type === "image").attachment, image);
  assert.ok(!JSON.stringify(result).includes(png.toString("base64")) && !JSON.stringify(result).includes(TOKEN));
});
test("default observations are text-first even for a vision route, with pure JSON and a separate real accessibility guide", async () => {
  const r = await runtime(undefined, { modalities: ["text", "image"] });
  const result = await r.execute("mobile_observe", { sessionId: SESSION });
  assert.equal(result.isError, false); assert.equal(r.native.calls[0].args.screenshot, false);
  assert.equal(result.value.screenshotOmittedReason, "not_requested"); assert.equal(result.value.image, undefined);
  assert.deepEqual(r.resolutions, [], "default text observation does not depend on remote capability metadata");
  const parsed = JSON.parse(result.content[0].text); assert.deepEqual(parsed, result.value);
  assert.equal(result.content.filter(block => block.type === "text").length, 2);
  const guide = result.content[1].text; assert.ok(guide.length <= LIMITS.summaryChars);
  assert.match(guide, /n0 parent=n3 \[click\] "Open" bounds=\(0,0,100,44\) actions=click/);
  assert.match(guide, /n3 \[scroll\]/); assert.match(guide, /scroll_forward,scroll_backward/);
  assert.ok(!JSON.stringify(result).includes("fixture-secret")); assert.ok(!result.content.some(block => block.type === "image"));
});
test("every binding expires after five minutes and observations cannot cross agents", async t => {
  const r = await runtime();
  const value = await r.observe(), binding = { sessionId: SESSION, observationId: value.observationId };
  const calls = r.native.calls.length;
  const other = { ...r.agent, id: randomUUID() };
  const wrongOwner = await r.execute("mobile_click", { ...binding, nodeId: "n0" }, undefined, other);
  assert.equal(code(wrongOwner), "MOBILE_STALE_OBSERVATION");
  assert.match(wrongOwner.content[0].text, /belongs to another DSH agent/);
  const received = performance.now();
  t.mock.method(performance, "now", () => received + LIMITS.observationAgeMs + 1);
  try {
    for (const [action, extra] of [["click", { x: 1, y: 1 }], ["swipe", { fromX: 0, fromY: 0, toX: 10, toY: 10 }], ["back", {}], ["click", { nodeId: "n0" }], ["type", { nodeId: "n1", text: "expired" }], ["scroll", { nodeId: "n3", direction: "forward" }]]) {
      const result = await r.execute("mobile_" + action, { ...binding, ...extra });
      assert.equal(code(result), "MOBILE_STALE_OBSERVATION");
      assert.match(result.content[0].text, /five-minute lifetime/);
    }
    assert.equal(r.native.calls.length, calls);
  } finally { t.mock.restoreAll(); }
});
test("a ninety-second model wait does not expire unchanged coordinate, swipe or Back bindings", async t => {
  const r = await runtime();
  for (const [action, extra] of [["click", { x: 1, y: 1 }], ["swipe", { fromX: 0, fromY: 0, toX: 10, toY: 10 }], ["back", {}]]) {
    const value = await r.observe();
    const received = performance.now();
    t.mock.method(performance, "now", () => received + 90_000);
    try {
      const result = await r.execute("mobile_" + action, { sessionId: SESSION, observationId: value.observationId, ...extra });
      assert.equal(result.isError, false, `${action}: ${result.content[0]?.text}`);
      assert.equal(r.native.calls.at(-1).path, `/v1/mobile/${action}`);
    } finally { t.mock.restoreAll(); }
  }
});
test("wall-clock corrections cannot expire or renew an observation", async t => {
  const r = await runtime();
  for (const offset of [-86_400_000, 86_400_000]) {
    const value = await r.observe(), wall = Date.now();
    t.mock.method(Date, "now", () => wall + offset);
    try {
      const result = await r.execute("mobile_click", { sessionId: SESSION, observationId: value.observationId, nodeId: "n0" });
      assert.equal(result.isError, false);
    } finally { t.mock.restoreAll(); }
  }
  const value = await r.observe(), monotonic = performance.now(), wall = Date.now();
  t.mock.method(Date, "now", () => wall - 86_400_000);
  t.mock.method(performance, "now", () => monotonic + LIMITS.observationAgeMs + 1);
  try {
    const result = await r.execute("mobile_back", { sessionId: SESSION, observationId: value.observationId });
    assert.equal(code(result), "MOBILE_STALE_OBSERVATION");
    assert.match(result.content[0].text, /five-minute lifetime/);
  } finally { t.mock.restoreAll(); }
});
test("binding diagnostics distinguish consumed, replaced, session, window, screen and target failures without leaking native text", async () => {
  const r = await runtime();
  const old = await r.observe(), current = await r.observe();
  const replaced = await r.execute("mobile_back", { sessionId: SESSION, observationId: old.observationId });
  assert.match(replaced.content[0].text, /replaced by a newer observation/);
  const session = await r.execute("mobile_back", { sessionId: randomUUID(), observationId: current.observationId });
  assert.match(session.content[0].text, /phone-control session changed/);
  const args = { sessionId: SESSION, observationId: current.observationId };
  assert.equal((await r.execute("mobile_back", args)).isError, false);
  const consumed = await r.execute("mobile_back", args);
  assert.match(consumed.content[0].text, /consumed the previous binding/);
  const errors = await bridge(call => ({ ok: false, error: { code: call.args.fixtureCode, message: TOKEN + " private-screen-text" } }));
  for (const [reason, expected] of [
    ["observation_missing", /No unused observation/], ["observation_replaced", /replaced by a newer/],
    ["observation_owner", /another DSH agent/], ["observation_session", /phone-control session changed/],
    ["observation_expired", /five-minute lifetime/], ["observation_window_changed", /window or display orientation changed/],
    ["observation_screen_changed", /Coordinate gestures require an unchanged screen/], ["observation_target_changed", /control disappeared or changed/],
  ]) {
    await assert.rejects(createMobileTransport(errors.env)("status", { fixtureCode: reason }), error => {
      assert.equal(error.code, "MOBILE_STALE_OBSERVATION"); assert.match(error.message, expected);
      assert.ok(!error.message.includes(TOKEN)); assert.ok(!error.message.includes("private-screen-text"));
      return true;
    });
  }
});
test("reported enabled/visible/scroll actions are enforced without silently clicking coordinates or retrying stale native actions", async () => {
  for (const [changes, tool, args, expected] of [
    [{ clickable: false, actions: [] }, "mobile_click", { nodeId: "n0" }, "MOBILE_NOT_CLICKABLE"],
    [{ enabled: false }, "mobile_click", { nodeId: "n0" }, "MOBILE_NOT_ENABLED"],
    [{ visible: false }, "mobile_click", { nodeId: "n0" }, "MOBILE_NOT_VISIBLE"],
    [{ scrollable: false }, "mobile_scroll", { nodeId: "n3", direction: "forward" }, "MOBILE_NOT_SCROLLABLE"],
    [{ actions: ["scroll_backward"] }, "mobile_scroll", { nodeId: "n3", direction: "forward" }, "MOBILE_NOT_SCROLLABLE"]
  ]) {
    const r = await runtime(call => { const value = observation(call.args); Object.assign(value.nodes.find(node => node.id === args.nodeId), changes); return { ok: true, value }; });
    const observed = await r.observe(); const count = r.native.calls.length;
    assert.equal(code(await r.execute(tool, { sessionId: SESSION, observationId: observed.observationId, ...args })), expected);
    assert.equal(r.native.calls.length, count, "an ineligible cached target never receives an implicit coordinate fallback");
  }
  const r = await runtime(call => call.path.endsWith("/observe") ? { ok: true, value: observation(call.args) } :
    { ok: false, error: { code: "stale_observation", message: "private-details" } });
  const observed = await r.observe(); const result = await r.execute("mobile_click", { sessionId: SESSION, observationId: observed.observationId, nodeId: "n0" });
  assert.equal(code(result), "MOBILE_STALE_OBSERVATION"); assert.match(result.content[0].text, /do not repeat an endless observe\/action loop/);
  assert.deepEqual(r.native.calls.map(call => call.path.split("/").at(-1)), ["observe", "click"], "one failed dispatch never triggers hidden observe or action retries");
});
test("accessibility guides stay bounded while full JSON retains every real node and password data stays redacted", async () => {
  const r = await runtime(call => {
    const value = observation(call.args); value.nodes = Array.from({ length: LIMITS.nodes }, (_, index) => ({
      ...value.nodes[0], id: `n${index}`, parentId: null, text: index === 999 ? "fixture-secret" : "Long accessible label " + "x".repeat(1000),
      password: index === 999, description: index === 999 ? "fixture-secret" : "fixture description"
    })); return { ok: true, value };
  });
  const result = await r.execute("mobile_observe", { sessionId: SESSION }); assert.equal(result.isError, false);
  assert.equal(JSON.parse(result.content[0].text).nodes.length, LIMITS.nodes);
  assert.ok(result.content[1].text.length <= LIMITS.summaryChars); assert.match(result.content[1].text, /Guide shortened/);
  assert.ok(!JSON.stringify(result).includes("fixture-secret"));
});
test("text layout validates enabled/visible/scrollable flags, closed action names, unique actions and real retained parent IDs", async () => {
  for (const change of [
    { enabled: "true" }, { visible: 1 }, { scrollable: null }, { parentId: "not-retained" }, { parentId: "n0" },
    { actions: ["shell"] }, { actions: ["click", "click"] }, { actions: Array(17).fill("focus") }
  ]) {
    const r = await runtime(call => { const value = observation(call.args); Object.assign(value.nodes[0], change); return { ok: true, value }; });
    const result = await r.execute("mobile_observe", { sessionId: SESSION });
    assert.equal(code(result), "MOBILE_INVALID_RESPONSE");
    assert.ok(!JSON.stringify(result).includes("fixture-secret"));
  }
});
test("all action variants bind fresh observation, enforce coordinates/text/password limits, and require verification", async () => {
  const r = await runtime();
  for (const [action, extra] of [["click", { nodeId: "n0" }], ["click", { x: 359.5, y: 639.5 }], ["type", { nodeId: "n1", text: "replacement 🌻" }], ["scroll", { nodeId: "n3", direction: "forward" }], ["scroll", { nodeId: "n3", direction: "backward" }], ["swipe", { fromX: 0, fromY: 10, toX: 359, toY: 639, durationMs: 300 }], ["back", {}]]) {
    const value = await r.observe();
    const args = { sessionId: SESSION, observationId: value.observationId, ...extra };
    const result = await r.execute("mobile_" + action, args);
    assert.equal(result.isError, false, JSON.stringify(result.error)); assert.equal(result.value.verificationRequired, true);
    assert.deepEqual(r.native.calls.at(-1).args, args);
    const stale = await r.execute("mobile_" + action, args);
    assert.equal(code(stale), "MOBILE_STALE_OBSERVATION");
  }
  const invalid = [
    ["click", { nodeId: "n0", x: 1, y: 1 }], ["click", { x: 360, y: 0 }], ["click", { x: -1, y: 0 }], ["click", { nodeId: "n999" }],
    ["type", { nodeId: "n0", text: "not editable" }], ["type", { nodeId: "n1", text: "x".repeat(4097) }],
    ["type", { nodeId: "n1", text: "\0" }], ["type", { nodeId: "n1", text: "\ud800" }], ["swipe", { fromX: 0, fromY: 0, toX: 1, toY: 1, durationMs: 99 }],
  ];
  for (const [action, extra] of invalid) {
    const value = await r.observe(); const count = r.native.calls.length;
    assert.equal(code(await r.execute("mobile_" + action, { sessionId: SESSION, observationId: value.observationId, ...extra })), "MOBILE_INVALID_REQUEST");
    assert.equal(r.native.calls.length, count);
  }
  const value = await r.observe();
  assert.equal(code(await r.execute("mobile_type", { sessionId: SESSION, observationId: value.observationId, nodeId: "n2", text: "blocked" })), "MOBILE_PASSWORD_FIELD");
  const other = { ...r.agent, id: randomUUID() };
  assert.equal(code(await r.execute("mobile_back", { sessionId: SESSION, observationId: value.observationId }, undefined, other)), "MOBILE_STALE_OBSERVATION");
});
test("native errors propagate stable safe codes; failed dispatched actions invalidate the observation", async () => {
  const r = await runtime(call => call.path.endsWith("/observe") ? { ok: true, value: observation(call.args) } : { ok: false, error: { code: "paused", message: TOKEN + " sensitive server detail" } });
  const value = await r.observe();
  const args = { sessionId: SESSION, observationId: value.observationId };
  const failed = await r.execute("mobile_back", args);
  assert.equal(code(failed), "MOBILE_PAUSED"); assert.match(failed.content[0].text, /Mobile use/); assert.ok(!JSON.stringify(failed).includes(TOKEN));
  assert.equal(code(await r.execute("mobile_back", args)), "MOBILE_STALE_OBSERVATION");
  const errors = await bridge(call => ({ ok: false, error: { code: call.args.fixtureCode, message: TOKEN } }));
  for (const nativeCode of ["timeout", "no_window", "not_enabled", "not_editable", "not_clickable", "not_scrollable", "not_visible", "unknown_node", "invalid_display", "secure_window", "screenshot_failed", "screen_too_large", "response_too_large", "action_cancelled", "forbidden"]) {
    await assert.rejects(createMobileTransport(errors.env)("status", { fixtureCode: nativeCode }), error => error.code === "MOBILE_" + nativeCode.toUpperCase() && !error.message.includes(TOKEN));
  }
});
test("malformed native observations and screenshots fail before attachment/model output", async () => {
  for (const mutate of [v => { v.nodes = Array(1001).fill(v.nodes[0]); }, v => { v.nodes[0].text = "x".repeat(2049); }, v => { v.sessionId = randomUUID(); }, v => { v.screenshot.base64 = "bad!"; }, v => { v.screenshot.widthPx = 2; }]) {
    const r = await runtime(call => { const value = observation(call.args); mutate(value); return { ok: true, value }; }, { modalities: ["image"] });
    const result = await r.execute("mobile_observe", { sessionId: SESSION, screenshot: true });
    assert.equal(code(result), "MOBILE_INVALID_RESPONSE"); assert.ok(!JSON.stringify(result).includes(TOKEN));
  }
});
test("actual Android 11 clipped offscreen geometry is rejected without granting an action binding", async () => {
  const r = await runtime(call => ({ ok: true, value: androidClippedObservation(call.args, false) }));
  const result = await r.execute("mobile_observe", { sessionId: SESSION });
  assert.equal(r.native.calls[0].args.screenshot, false);
  assert.equal(code(result), "MOBILE_INVALID_RESPONSE");
  assert.equal(result.error.message, "The native mobile bridge returned an invalid or oversized response.");
  assert.ok(!result.content.some(block => block.type === "image"));
  const actionsBefore = r.native.calls.length;
  const stale = await r.execute("mobile_click", { sessionId: SESSION, observationId: randomUUID(), nodeId: "n0" });
  assert.equal(code(stale), "MOBILE_STALE_OBSERVATION");
  assert.equal(r.native.calls.length, actionsBefore);
});
test("native omission of clipped nodes preserves retained IDs, descriptions, action binding and real PNG attachments", async () => {
  for (const modalities of [["text"], ["text", "image"]]) {
    let issued;
    const r = await runtime(call => {
      const action = call.path.split("/").at(-1);
      if (action === "observe") {
        issued = androidClippedObservation(call.args, true);
        return { ok: true, value: issued };
      }
      return { ok: true, value: { performed: true, action, observationId: call.args.observationId } };
    }, { modalities });
    const result = await r.execute("mobile_observe", { sessionId: SESSION, screenshot: modalities.includes("image") });
    assert.equal(result.isError, false, JSON.stringify(result.error));
    assert.equal(result.value.nodes.length, 31);
    assert.deepEqual(result.value.nodes, issued.nodes);
    assert.deepEqual(result.value.nodes.map(node => node.id), Array.from({ length: 31 }, (_, index) => `n${index}`));
    assert.deepEqual(result.value.display, { widthPx: 720, heightPx: 1280, rotation: 0 });
    assert.deepEqual(result.value.window, { id: 1127, packageName: "com.android.settings" });
    assert.equal(result.value.screenshotRequested, modalities.includes("image"));
    if (modalities.includes("image")) {
      const image = result.value.image;
      assert.ok(image.attachmentId);
      assert.equal(image.width, 1); assert.equal(image.height, 1);
      assert.deepEqual(result.content.find(block => block.type === "image").attachment, image);
      assert.deepEqual(await readFile(r.ctx.attachments.imageHostPath(image)), png);
    } else {
      assert.equal(result.value.screenshotOmittedReason, "not_requested");
      assert.equal(result.value.image, undefined);
      assert.ok(!result.content.some(block => block.type === "image"));
    }
    assert.ok(!JSON.stringify(result).includes(png.toString("base64")) && !JSON.stringify(result).includes(TOKEN));
    const binding = { sessionId: SESSION, observationId: result.value.observationId };
    const callsBefore = r.native.calls.length;
    assert.equal(code(await r.execute("mobile_click", { ...binding, nodeId: "n31" })), "MOBILE_INVALID_REQUEST");
    assert.equal(r.native.calls.length, callsBefore);
    const typed = await r.execute("mobile_type", { ...binding, nodeId: "n30", text: "Fixture replacement" });
    assert.equal(typed.isError, false, JSON.stringify(typed.error));
    assert.deepEqual(r.native.calls.at(-1).args, { ...binding, nodeId: "n30", text: "Fixture replacement" });
  }
});
test("launcher list is grant-bound, sorted, bounded and exposes only launcher metadata", async () => {
  const r = await runtime(call => ({ ok: true, value: {
    ...appList(call.args.sessionId), truncated: true,
    apps: [{ packageName: "fixture.z", label: "Same", component: "hidden.Component" },
      { packageName: "fixture.a", label: "Same" }, { packageName: "fixture.notes", label: "Notes" },
      { packageName: "fixture.beta", label: "Beta" }, { packageName: "fixture.alpha", label: "alpha" }],
  } }));
  const result = await r.execute("mobile_list_apps", { sessionId: SESSION });
  assert.equal(result.isError, false, JSON.stringify(result.error));
  assert.deepEqual(result.value, { sessionId: SESSION, apps: [
    { packageName: "fixture.alpha", label: "alpha" }, { packageName: "fixture.beta", label: "Beta" },
    { packageName: "fixture.notes", label: "Notes" }, { packageName: "fixture.a", label: "Same" },
    { packageName: "fixture.z", label: "Same" }], truncated: true, foregroundOnly: true });
  assert.deepEqual(r.native.calls[0].args, { sessionId: SESSION });
  assert.ok(!JSON.stringify(result).includes("hidden.Component"));
  assert.ok(!JSON.stringify(result).includes(TOKEN));
  const assembly = await r.ctx.systemPrompt.assemble({ scope: r.agent });
  assert.match(JSON.stringify(assembly), /current foreground window only/);
  const empty = await runtime(() => ({ ok: true, value: { ...appList(), apps: [] } }));
  assert.equal((await empty.execute("mobile_list_apps", { sessionId: SESSION })).value.apps.length, 0);
  for (const mutate of [
    v => { v.apps = Array(129).fill(APPS[0]); }, v => { v.apps = [APPS[0], APPS[0]]; },
    v => { v.sessionId = randomUUID(); }, v => { v.foregroundOnly = false; }, v => { v.truncated = 0; },
    v => { v.apps = [{ packageName: "fixture.notes", label: "x".repeat(129) }]; },
    v => { v.apps = [{ packageName: "fixture.notes", label: "bad\0label" }]; },
    v => { v.apps = [{ packageName: "fixture.notes", label: "\ud800" }]; },
    v => { v.apps = [{ packageName: "https://example.com", label: "URI" }]; },
  ]) {
    const invalid = await runtime(() => { const value = structuredClone(appList()); mutate(value); return { ok: true, value }; });
    assert.equal(code(await invalid.execute("mobile_list_apps", { sessionId: SESSION })), "MOBILE_INVALID_RESPONSE");
  }
});
test("listing and opening cannot bypass paused or missing native task grants", async () => {
  for (const nativeCode of ["paused", "accessibility_disabled", "locked", "disconnected"]) {
    const r = await runtime(call => call.path.endsWith("/observe") ? { ok: true, value: observation(call.args) } : {
      ok: false, error: { code: nativeCode, message: TOKEN + " private detail" },
    });
    const listed = await r.execute("mobile_list_apps", { sessionId: SESSION });
    assert.equal(code(listed), "MOBILE_" + nativeCode.toUpperCase());
    assert.ok(!JSON.stringify(listed).includes(TOKEN));
    const value = await r.observe();
    const args = { sessionId: SESSION, observationId: value.observationId, packageName: "com.android.settings" };
    assert.equal(code(await r.execute("mobile_open_app", args)), "MOBILE_" + nativeCode.toUpperCase());
    assert.equal(code(await r.execute("mobile_open_app", args)), "MOBILE_" + nativeCode.toUpperCase());
  }
  const r = await runtime();
  assert.equal(code(await r.execute("mobile_list_apps", { sessionId: "" })), "MOBILE_INVALID_REQUEST");
  assert.equal(r.native.calls.length, 0);
});
test("open app rejects URI/component/extra fields and invalid package names before native dispatch", async () => {
  const r = await runtime();
  const value = await r.observe();
  const binding = { sessionId: SESSION, observationId: value.observationId };
  const calls = r.native.calls.length;
  for (const name of ["", "android", "com..app", ".com.app", "com.app.", "1com.app", "com.1app", "com.app/Activity", "https://example.com", "intent://app", "com.app?extra=1", "com.app\0", "com.app\n", "com." + "x".repeat(253)]) {
    assert.equal(code(await r.execute("mobile_open_app", { ...binding, packageName: name })), "MOBILE_INVALID_REQUEST");
    assert.equal(r.native.calls.length, calls);
  }
  for (const extra of [{ component: "com.app.Activity" }, { uri: "content://fixture" }, { intent: { action: "arbitrary" } }, { background: true }]) {
    const result = await r.execute("mobile_open_app", { ...binding, packageName: "com.android.settings", ...extra });
    assert.equal(result.isError, true);
    assert.equal(r.native.calls.length, calls);
  }
});
test("launcher opening is independent of screen observations while the native current-session grant remains authoritative", async t => {
  const r = await runtime(call => call.args.sessionId !== SESSION ? { ok: false, error: { code: "invalid_session", message: TOKEN } } :
    { ok: true, value: { performed: true, action: "open_app", sessionId: call.args.sessionId,
      observationId: call.args.observationId, packageName: call.args.packageName, foregroundOnly: true } });
  const args = { sessionId: SESSION, packageName: "com.android.settings" };
  assert.equal((await r.execute("mobile_open_app", args)).isError, false, "a text model can open an app without observing DSH first");
  const other = { ...r.agent, id: randomUUID() };
  assert.equal((await r.execute("mobile_open_app", args, undefined, other)).isError, false, "launcher opening is not a screen-target action");
  assert.equal(code(await r.execute("mobile_open_app", { ...args, sessionId: randomUUID() })), "MOBILE_INVALID_SESSION");
  assert.equal((await r.execute("mobile_open_app", { ...args, observationId: randomUUID() })).isError, false, "legacy observation field is only compatibility metadata");
  const now = performance.now();
  t.mock.method(performance, "now", () => now + LIMITS.observationAgeMs + 1);
  try {
    assert.equal((await r.execute("mobile_open_app", args)).isError, false, "slow inference never expires a screen-independent launcher request");
  } finally { t.mock.restoreAll(); }
});
test("accepted launcher open requires a fresh observation before further foreground actions", async () => {
  let opened = false;
  const r = await runtime(call => {
    const action = call.path.split("/").at(-1);
    if (action === "list_apps") return { ok: true, value: appList(call.args.sessionId) };
    if (action === "observe") {
      const value = observation(call.args);
      if (opened) {
        value.window = { id: 2, packageName: "io.github.fixture.notes" };
        value.nodes.forEach(node => { node.packageName = "io.github.fixture.notes"; });
      }
      return { ok: true, value };
    }
    if (action === "open_app") opened = true;
    return { ok: true, value: { performed: true, action, observationId: call.args.observationId,
      ...(action === "open_app" ? { sessionId: call.args.sessionId, packageName: call.args.packageName, foregroundOnly: true } : {}) } };
  });
  const observed = await r.observe();
  assert.equal((await r.execute("mobile_list_apps", { sessionId: SESSION })).isError, false);
  const args = { sessionId: SESSION, observationId: observed.observationId, packageName: "io.github.fixture.notes" };
  const result = await r.execute("mobile_open_app", args);
  assert.equal(result.isError, false, JSON.stringify(result.error));
  assert.deepEqual(result.value, { performed: true, action: "open_app", sessionId: SESSION, observationId: observed.observationId,
    verificationRequired: true, packageName: "io.github.fixture.notes", foregroundOnly: true });
  assert.deepEqual(r.native.calls.at(-1).args, args);
  assert.equal(code(await r.execute("mobile_back", { sessionId: SESSION, observationId: observed.observationId })), "MOBILE_STALE_OBSERVATION");
  const next = await r.observe();
  assert.deepEqual(next.window, { id: 2, packageName: "io.github.fixture.notes" });
  assert.notEqual(next.observationId, observed.observationId);
  assert.equal((await r.execute("mobile_click", { sessionId: SESSION, observationId: next.observationId, nodeId: "n0" })).isError, false);
});
test("failed or malformed launcher replies consume the observation and redact native messages", async () => {
  for (const reply of [
    args => ({ ok: false, error: { code: "app_not_available", message: TOKEN + " private package state" } }),
    args => ({ ok: true, value: { performed: true, action: "open_app", sessionId: args.sessionId, observationId: args.observationId, packageName: "different.app", foregroundOnly: true } }),
    args => ({ ok: true, value: { performed: true, action: "open_app", sessionId: args.sessionId, observationId: args.observationId, packageName: args.packageName, foregroundOnly: false } }),
  ]) {
    const r = await runtime(call => call.path.endsWith("/observe") ? { ok: true, value: observation(call.args) } : reply(call.args));
    const value = await r.observe();
    const args = { sessionId: SESSION, observationId: value.observationId, packageName: "com.android.settings" };
    const result = await r.execute("mobile_open_app", args);
    assert.equal(result.isError, true);
    assert.ok(["MOBILE_APP_NOT_AVAILABLE", "MOBILE_INVALID_RESPONSE"].includes(code(result)));
    assert.ok(!JSON.stringify(result).includes(TOKEN));
    assert.equal(code(await r.execute("mobile_back", { sessionId: SESSION, observationId: value.observationId })), "MOBILE_STALE_OBSERVATION");
  }
});
test("launcher requests share the FIFO and queued aborted open never launches", async () => {
  let active = 0, maximum = 0;
  const r = await runtime(async call => {
    active++; maximum = Math.max(maximum, active);
    await sleep(50);
    active--;
    const action = call.path.split("/").at(-1);
    return { ok: true, value: action === "observe" ? observation(call.args) : action === "list_apps" ? appList(call.args.sessionId) : status() };
  });
  const value = await r.observe();
  const first = r.execute("mobile_list_apps", { sessionId: SESSION });
  const cancel = new AbortController();
  const second = r.execute("mobile_open_app", { sessionId: SESSION, observationId: value.observationId, packageName: "com.android.settings" }, cancel.signal);
  const third = r.execute("mobile_list_apps", { sessionId: SESSION });
  await sleep(10); cancel.abort();
  const results = await Promise.all([first, second, third]);
  assert.equal(results[0].isError, false); assert.equal(results[2].isError, false);
  assert.equal(results[1].isError, true);
  assert.equal(maximum, 1);
  assert.equal(r.native.calls.some(call => call.path.endsWith("/open_app")), false);
  assert.deepEqual(r.native.calls.map(call => call.path.split("/").at(-1)), ["observe", "list_apps", "list_apps"]);
});
test("bounded FIFO serializes actual UDS requests and cancels a queued call before dispatch", async () => {
  let active = 0, maximum = 0;
  const r = await runtime(async () => { active++; maximum = Math.max(maximum, active); await sleep(80); active--; return { ok: true, value: status() }; });
  const first = r.execute("mobile_status");
  const cancel = new AbortController();
  const second = r.execute("mobile_status", {}, cancel.signal);
  const third = r.execute("mobile_status");
  await sleep(20); cancel.abort();
  const results = await Promise.all([first, second, third]);
  assert.equal(results[0].isError, false); assert.equal(results[2].isError, false); assert.equal(results[1].isError, true);
  assert.equal(maximum, 1); assert.equal(r.native.calls.length, 2);
});
test("transport rejects wrong/missing credentials, caps response bytes, rejects bad envelopes, and honors abort/deadline", async () => {
  const good = await bridge(() => ({ ok: true, value: status() }));
  await assert.rejects(createMobileTransport({ ...good.env, DSH_ANDROID_MOBILE_TOKEN: "0".repeat(64) })("status", {}), error => error.code === "MOBILE_UNAUTHORIZED" && !error.message.includes(TOKEN));
  await assert.rejects(createMobileTransport({})("status", {}), { code: "MOBILE_UNAVAILABLE" });
  await assert.rejects(createMobileTransport({ ...good.env, DSH_ANDROID_MOBILE_SOCKET: "../../socket" })("status", {}), { code: "MOBILE_UNAVAILABLE" });
  assert.equal(good.calls.length, 1);
  for (const handler of [(_call, res) => { res.writeHead(200, { "Content-Length": LIMITS.responseBytes + 1 }); res.end(); }, (_call, res) => { res.writeHead(200); res.end("not json"); }, () => ({ ok: true, value: null })]) {
    const b = await bridge(handler);
    await assert.rejects(createMobileTransport(b.env)("status", {}), { code: "MOBILE_INVALID_RESPONSE" });
  }
  const slow = await bridge(async () => { await sleep(200); return { ok: true, value: status() }; });
  await assert.rejects(createMobileTransport(slow.env, { deadlineMs: 30 })("status", {}), { code: "MOBILE_TIMEOUT" });
  const controller = new AbortController();
  const pending = createMobileTransport(slow.env)("status", {}, controller.signal);
  setTimeout(() => controller.abort(), 20);
  await assert.rejects(pending, { code: "MOBILE_ABORTED" });
  assert.equal((await createMobileTransport(good.env)("status", {})).active, true);
});
