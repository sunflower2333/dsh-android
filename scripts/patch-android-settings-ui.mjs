#!/usr/bin/env node
import { createHash } from "node:crypto";
import { readFile, writeFile } from "node:fs/promises";
import { join, resolve } from "node:path";
import { fileURLToPath } from "node:url";

const MARKER = "/* dsh-android-web-settings-ui-v1 */";
const LEGACY_SECTIONS_SHA = "069865fa21ab2b0719e7294deb985a8757fa4c80f240b8127c3c0360426691e0";
const PRE_LIVE_SECTIONS_SHA = "d9505e551bc75e4ce825691c1a16d19adb5481dcaf9e9f7b631434e27dfabf66";
const PRE_AUTO_LIVE_SECTIONS_SHA = "e545a19cc3aee545c24da8bb9c0a146af0c38c81d513a6cc714b521bddd06f6b";
const REMOVED_LIVE_KEYS = ["androidRuntime.liveUpdates", "androidRuntime.liveHelp", "androidRuntime.liveSettings"];
const BEGIN = "    function AndroidMobileUseSection({ t }) {";
const END = "    function GeneralSection({ renderSlot }) {";
const SCRIPT_TAG = '<script data-dsh-android-settings src="./assets/dsh-android-settings-ui.js"></script>';
const OPENER_ANCHOR = "      const { close, openSection } = actions;";
const OPENER_CODE = `${OPENER_ANCHOR}
      (0, react.useEffect)(() => {
        if (!window.DshAndroidSettings) return;
        const openAndroidSettings = (kind) => {
          const section = kind === "mobile" ? "android-mobile-use" : kind === "runtime" ? "android-runtime" : null;
          if (!section) return false;
          openSection(section);
          window.DshAndroidNavigation?.closeSidebar();
          return true;
        };
        window.__DSH_ANDROID_OPEN_SETTINGS__ = openAndroidSettings;
        return () => {
          if (window.__DSH_ANDROID_OPEN_SETTINGS__ === openAndroidSettings) delete window.__DSH_ANDROID_OPEN_SETTINGS__;
        };
      }, [openSection]);`;
const LOCALE_ANCHOR = "      const t = ctx.locale.bind(NS);";
const LOCALE_CODE = `${LOCALE_ANCHOR}
      if (window.DshAndroidSettings) ctx.effect(() =>
        window.DshAndroidSettings.installLocale(ctx.locale),
        "ui-settings-general: Android global language synchronization");`;

const css = `
.dshAndroidSettings{display:flex;flex-direction:column;gap:20px;min-width:0;max-width:680px;color:var(--dsw-alias-label-primary);font-size:14px;line-height:22px}
.dshAndroidSettings h2{margin:0;font-size:16px;font-weight:600;line-height:24px}
.dshAndroidSettings p{margin:0;color:var(--dsw-alias-label-secondary);font-size:13px;line-height:20px;overflow-wrap:anywhere}
.dshAndroidSettings dl{margin:0;min-width:0}
.dshAndroidSettingsRow{display:flex;justify-content:space-between;align-items:center;gap:20px;min-height:44px;padding:12px 0;border-bottom:.5px solid var(--dsw-alias-border-l2)}
.dshAndroidSettingsRow dt{min-width:0;flex:1}
.dshAndroidSettingsRow dd{margin:0;max-width:60%;text-align:right;overflow-wrap:anywhere;color:var(--dsw-alias-label-secondary)}
.dshAndroidSettingsActions{display:flex;flex-wrap:wrap;gap:8px;min-width:0}
.dshAndroidSettingsActions button{min-height:44px;max-width:100%;white-space:normal;overflow-wrap:anywhere;text-align:center}
.dshAndroidSettingsToggle{display:flex;align-items:center;justify-content:space-between;gap:20px;min-height:44px}
.dshAndroidSettingsToggle>div{flex:1;min-width:0}
.dshAndroidSettingsToggle small{display:block;margin-top:4px;font-size:12px;line-height:18px;color:var(--dsw-alias-label-secondary)}
.dshAndroidSettings .dshAndroidSettingsSwitch{width:44px;height:44px;padding:14px 6px;background:transparent!important}
.dshAndroidSettingsSwitch:before{content:"";position:absolute;left:4px;top:12px;width:36px;height:20px;border-radius:999px;background:var(--dsw-alias-border-l3)}
.dshAndroidSettingsSwitch[aria-checked=true]:before{background:var(--dsw-alias-brand-primary)}
.dshAndroidSettingsSwitch>span{position:relative}
.dshAndroidSettingsError{color:var(--dsw-alias-state-error-primary)!important}
.dshAndroidCapabilitySettings{display:flex;flex-direction:column;gap:12px;padding-top:8px;border-top:.5px solid var(--dsw-alias-border-l2)}
.dshAndroidCapabilitySettings h3{margin:0;font-size:15px;font-weight:600;line-height:22px}
.dshAndroidCapabilitySettings input{min-height:40px;min-width:160px;max-width:100%;padding:8px 10px;border:1px solid var(--dsw-alias-border-l2);border-radius:8px;background:var(--dsw-alias-fill-tertiary);color:var(--dsw-alias-label-primary)}
.dshAndroidSessionList{display:flex;flex-direction:column;gap:12px;min-width:0}
.dshAndroidSessionList h3{margin:0;font-size:14px;font-weight:500;line-height:22px}
.dshAndroidSession{border:.5px solid var(--dsw-alias-border-l2);border-radius:var(--dsw-radius-md,12px);padding:12px;min-width:0;background:var(--dsw-alias-settings-card-fill)}
.dshAndroidSessionHeader{display:flex;align-items:center;justify-content:space-between;gap:12px;margin-bottom:8px;min-width:0}
.dshAndroidSessionHeader button{min-height:44px;min-width:0;max-width:75%;white-space:normal;text-align:left;overflow-wrap:anywhere;justify-content:flex-start}
.dshAndroidSessionState{color:var(--dsw-alias-label-secondary);font-size:12px;white-space:nowrap}
.dshAndroidSessionMetrics{display:grid;grid-template-columns:repeat(2,minmax(0,1fr));gap:12px 16px;margin:0}
.dshAndroidSessionMetrics>div{min-width:0}
.dshAndroidSessionMetrics dt{color:var(--dsw-alias-label-secondary);font-size:12px;line-height:18px}
.dshAndroidSessionMetrics dd{margin:2px 0 0;overflow-wrap:anywhere;font-size:13px;line-height:20px;font-variant-numeric:tabular-nums}
.dshAndroidSessionScope{margin-top:12px!important;font-size:12px!important;line-height:18px!important}
@media(max-width:420px){.dshAndroidSettingsRow{gap:12px}.dshAndroidSettingsRow dd{max-width:55%}.dshAndroidSettingsActions button{flex:1 1 130px}}
`;

const sectionCode = `    const androidSettingsStyleId = "@deepseek-ai/dsh-client-ui-settings-general/AndroidSettings.css";
    if (typeof document !== "undefined" && document.querySelector("style[data-plugin-css=" + JSON.stringify(androidSettingsStyleId) + "]") === null) {
      const tag = document.createElement("style");
      tag.dataset.plugin = "@deepseek-ai/dsh-client-ui-settings-general";
      tag.dataset.pluginCss = androidSettingsStyleId;
      tag.textContent = ${JSON.stringify(css)};
      document.head.appendChild(tag);
    }
    const androidSettingsUnavailable = Object.freeze({ status: null, error: "unavailable", connected: false });
    function useAndroidSettings() {
      const host = window.DshAndroidSettings;
      const state = (0, react.useSyncExternalStore)(host ? host.subscribe : () => () => {}, host ? host.getSnapshot : () => androidSettingsUnavailable);
      const [busy, setBusy] = (0, react.useState)(false);
      const [error, setError] = (0, react.useState)(null);
      const mounted = (0, react.useRef)(true);
      (0, react.useEffect)(() => { mounted.current = true; return () => { mounted.current = false; }; }, []);
      const perform = (type, enabled, event, serial) => {
        if (busy || !host) return;
        setBusy(true); setError(null);
        host.request(type, enabled, event, serial).catch((failure) => {
          if (mounted.current) setError(failure.message);
        }).finally(() => { if (mounted.current) setBusy(false); });
      };
      return { state, busy, error, perform };
    }
    function AndroidSettingsRow({ label, value }) {
      return (0, react_jsx_runtime.jsxs)("div", { className: "dshAndroidSettingsRow", children: [
        (0, react_jsx_runtime.jsx)("dt", { children: label }),
        (0, react_jsx_runtime.jsx)("dd", { children: value })
      ] });
    }
    function AndroidSettingsError({ t, error }) {
      if (!error) return null;
      const key = ["user_gesture_required", "control_unavailable", "settings_unavailable", "permission_request_pending"].includes(error) ? error : "unavailable";
      return (0, react_jsx_runtime.jsx)("p", { role: "alert", className: "dshAndroidSettingsError", children: t("androidSettings.error." + key) });
    }
    function AndroidSettingsButton({ t, label, type, enabled, busy, connected, perform, onClick, disabled = false }) {
      return (0, react_jsx_runtime.jsx)(_deepseek_ai_dsh_client_ui_primitives.Button, {
        variant: type === "allow-control" ? "primary" : "outline", size: "sm", type: "button",
        disabled: busy || !connected || disabled, "data-dsh-android-action": type,
        onClick: onClick || ((event) => perform(type, enabled, event)), children: t(label)
      });
    }
    function AndroidMobileUseSection({ t }) {
      const { state, busy, error, perform } = useAndroidSettings();
      const status = state.status;
      const mobile = status && status.mobile;
      const gesture = (0, react.useRef)(null);
      const unknown = t("androidSettings.checking");
      const controlLabel = !mobile ? unknown : mobile.active ? t("mobileUse.active") :
        mobile.reason === "device_locked" ? t("mobileUse.locked") : t("mobileUse.paused");
      return (0, react_jsx_runtime.jsxs)("section", {
        className: "dshAndroidSettings", "data-dsh-android-mobile-use": "", "aria-label": t("mobileUse.nav"),
        children: [
          (0, react_jsx_runtime.jsx)("h2", { children: t("mobileUse.nav") }),
          (0, react_jsx_runtime.jsx)("p", { children: t("mobileUse.description") }),
          (0, react_jsx_runtime.jsxs)("dl", { children: [
            (0, react_jsx_runtime.jsx)(AndroidSettingsRow, { label: t("mobileUse.accessibility"), value: !mobile ? unknown : t(mobile.enabled ? "androidSettings.on" : "androidSettings.off") }),
            (0, react_jsx_runtime.jsx)(AndroidSettingsRow, { label: t("mobileUse.connection"), value: !mobile ? unknown : t(mobile.connected ? "androidSettings.connected" : "androidSettings.disconnected") }),
            (0, react_jsx_runtime.jsx)(AndroidSettingsRow, { label: t("mobileUse.control"), value: controlLabel }),
            (0, react_jsx_runtime.jsx)(AndroidSettingsRow, { label: t("mobileUse.currentApp"), value: !mobile ? unknown : mobile.currentPackage || t("mobileUse.noApp") })
          ] }),
          (0, react_jsx_runtime.jsxs)("div", { className: "dshAndroidSettingsActions", children: [
            (0, react_jsx_runtime.jsx)(AndroidSettingsButton, { t, label: "mobileUse.systemSettings", type: "open-accessibility", busy, connected: state.connected, perform }),
            (0, react_jsx_runtime.jsx)(AndroidSettingsButton, { t, label: "mobileUse.allow", type: "allow-control", busy, connected: state.connected, perform, disabled: !mobile || !mobile.canAllow }),
            (0, react_jsx_runtime.jsx)(AndroidSettingsButton, { t, label: "mobileUse.pause", type: "pause-control", busy, connected: state.connected, perform, disabled: !mobile || !mobile.active })
          ] }),
          (0, react_jsx_runtime.jsx)("p", { children: t("mobileUse.grantHelp") }),
          (0, react_jsx_runtime.jsxs)("div", { className: "dshAndroidSettingsToggle",
            onClickCapture: (event) => {
              const nativeEvent = event.nativeEvent || event;
              gesture.current = nativeEvent;
              setTimeout(() => { if (gesture.current === nativeEvent) gesture.current = null; }, 0);
            },
            children: [
              (0, react_jsx_runtime.jsxs)("div", { children: [
                (0, react_jsx_runtime.jsx)("span", { children: t("mobileUse.feedback") }),
                (0, react_jsx_runtime.jsx)("small", { children: t("mobileUse.feedbackHelp") })
              ] }),
              (0, react_jsx_runtime.jsx)(_deepseek_ai_dsh_client_ui_primitives.Switch, {
                checked: !!mobile && mobile.feedback, disabled: busy || !state.connected || !mobile,
                label: t("mobileUse.feedback"), className: "dshAndroidSettingsSwitch",
                onChange: (enabled) => {
                  const event = gesture.current;
                  gesture.current = null;
                  perform("set-feedback", enabled, event);
                }
              })
            ]
          }),
          (0, react_jsx_runtime.jsx)(AndroidCapabilitySettings, { t, state, busy, perform }),
          (0, react_jsx_runtime.jsx)(AndroidSettingsError, { t, error: error || state.error })
        ]
      });
    }
    function AndroidCapabilitySettings({ t, state, busy, perform }) {
      const capabilities = state.status && state.status.capabilities;
      const [serial, setSerial] = (0, react.useState)(capabilities && capabilities.adbTarget || "");
      const gesture = (0, react.useRef)(null);
      (0, react.useEffect)(() => {
        if (capabilities) setSerial(capabilities.adbTarget || "");
      }, [capabilities && capabilities.adbTarget]);
      if (!capabilities) return null;
      const capture = (event) => {
        const nativeEvent = event.nativeEvent || event;
        gesture.current = nativeEvent;
        setTimeout(() => { if (gesture.current === nativeEvent) gesture.current = null; }, 0);
      };
      const applyTarget = (event) => {
        const nativeEvent = gesture.current;
        gesture.current = null;
        perform("set-adb-target", undefined, nativeEvent || event, serial);
      };
      return (0, react_jsx_runtime.jsxs)("div", { className: "dshAndroidCapabilitySettings", "data-dsh-android-capabilities": "", children: [
        (0, react_jsx_runtime.jsx)("h3", { children: t("androidSecurity.heading") }),
        (0, react_jsx_runtime.jsx)("p", { children: t("androidSecurity.description") }),
        (0, react_jsx_runtime.jsxs)("dl", { children: [
          (0, react_jsx_runtime.jsx)(AndroidSettingsRow, { label: t("androidSecurity.adbClient"), value: t(capabilities.adbConfigured ? "androidSettings.configured" : "androidSettings.notConfigured") }),
          (0, react_jsx_runtime.jsx)(AndroidSettingsRow, { label: t("androidSecurity.adbConnection"), value: t(capabilities.adbConnected ? "androidSettings.connected" : "androidSettings.disconnected") }),
          (0, react_jsx_runtime.jsx)(AndroidSettingsRow, { label: t("androidSecurity.rootAvailability"), value: t(capabilities.rootAvailable ? "androidSettings.available" : "androidSettings.unavailable") })
        ] }),
        (0, react_jsx_runtime.jsxs)("div", { className: "dshAndroidSettingsActions", children: [
          (0, react_jsx_runtime.jsx)(AndroidSettingsButton, { t, label: "androidSecurity.importAdb", type: "open-adb-file", busy, connected: state.connected, perform }),
          (0, react_jsx_runtime.jsx)(AndroidSettingsButton, { t, label: capabilities.adbEnabled ? "androidSecurity.disableAdb" : "androidSecurity.enableAdb", type: "set-adb-enabled", enabled: !capabilities.adbEnabled, busy, connected: state.connected, perform, disabled: !capabilities.adbConfigured }),
          (0, react_jsx_runtime.jsx)(AndroidSettingsButton, { t, label: capabilities.rootEnabled ? "androidSecurity.disableRoot" : "androidSecurity.enableRoot", type: "set-root-enabled", enabled: !capabilities.rootEnabled, busy, connected: state.connected, perform, disabled: !capabilities.rootAvailable })
        ] }),
        (0, react_jsx_runtime.jsxs)("div", { className: "dshAndroidSettingsActions", onClickCapture: capture, children: [
          (0, react_jsx_runtime.jsx)("input", { type: "text", value: serial, maxLength: 128, placeholder: t("androidSecurity.targetPlaceholder"), "aria-label": t("androidSecurity.target"), onChange: (event) => setSerial(event.target.value.replace(/[^A-Za-z0-9._:-]/g, "")) }),
          (0, react_jsx_runtime.jsx)(AndroidSettingsButton, { t, label: "androidSecurity.saveTarget", type: "set-adb-target", busy, connected: state.connected, onClick: applyTarget })
        ] }),
        (0, react_jsx_runtime.jsx)("p", { children: t("androidSecurity.help") })
      ] });
    }
    function androidSessionNumber(t, value, decimals = 0) {
      if (value === null) return t("androidRuntime.statsUnavailable");
      return new Intl.NumberFormat(t("androidRuntime.numberLocale"), { maximumFractionDigits: decimals }).format(value);
    }
    function androidSessionCache(t, item) {
      if (item.cachedInputTokens === null) return t("androidRuntime.statsUnavailable");
      const tokens = androidSessionNumber(t, item.cachedInputTokens);
      if (item.inputTokens === null || item.cacheWriteTokens === null) return t("androidRuntime.cachedCount", { tokens });
      const input = item.inputTokens + item.cachedInputTokens + item.cacheWriteTokens;
      if (!Number.isSafeInteger(input) || input === 0) return t("androidRuntime.cachedCount", { tokens });
      return t("androidRuntime.cachedPercent", { tokens, percent: androidSessionNumber(t, item.cachedInputTokens / input * 100, 1) });
    }
    function androidSessionContext(t, item) {
      if (item.contextUsed === null) return item.contextCapacity === null ? t("androidRuntime.statsUnavailable") :
        t("androidRuntime.contextCapacityOnly", { capacity: androidSessionNumber(t, item.contextCapacity) });
      const used = androidSessionNumber(t, item.contextUsed);
      if (item.contextCapacity === null) return t("androidRuntime.contextCount", { used });
      return t("androidRuntime.contextCapacity", { used, capacity: androidSessionNumber(t, item.contextCapacity),
        percent: androidSessionNumber(t, item.contextUsed / item.contextCapacity * 100, 1) });
    }
    function AndroidSessionMetric({ label, value, name }) {
      return (0, react_jsx_runtime.jsxs)("div", { "data-dsh-android-metric": name, children: [
        (0, react_jsx_runtime.jsx)("dt", { children: label }),
        (0, react_jsx_runtime.jsx)("dd", { children: value })
      ] });
    }
    function AndroidSessionSummary({ t, item }) {
      const [failed, setFailed] = (0, react.useState)(false);
      const open = (event) => {
        if (event.isTrusted !== true) return;
        setFailed(false);
        if (typeof window.__DSH_ANDROID_OPEN_SESSION__ !== "function" || window.__DSH_ANDROID_OPEN_SESSION__(item.sessionId) !== true) setFailed(true);
      };
      return (0, react_jsx_runtime.jsxs)("article", {
        className: "dshAndroidSession", "data-dsh-android-session": item.sessionId,
        children: [
          (0, react_jsx_runtime.jsxs)("div", { className: "dshAndroidSessionHeader", children: [
            (0, react_jsx_runtime.jsx)(_deepseek_ai_dsh_client_ui_primitives.Button, {
              variant: "ghost", size: "sm", type: "button", "data-dsh-android-open-session": "",
              disabled: typeof window.__DSH_ANDROID_OPEN_SESSION__ !== "function", onClick: open,
              children: item.name || t("androidRuntime.untitledSession")
            }),
            (0, react_jsx_runtime.jsx)("span", { className: "dshAndroidSessionState", children: t(item.state === "running" ? "androidRuntime.sessionRunning" : "androidRuntime.sessionWaiting") })
          ] }),
          (0, react_jsx_runtime.jsxs)("dl", { className: "dshAndroidSessionMetrics", children: [
            (0, react_jsx_runtime.jsx)(AndroidSessionMetric, { label: t("androidRuntime.sessionTurns"), name: "turns", value: androidSessionNumber(t, item.turns) }),
            (0, react_jsx_runtime.jsx)(AndroidSessionMetric, { label: t("androidRuntime.sessionSteps"), name: "steps", value: androidSessionNumber(t, item.steps) }),
            (0, react_jsx_runtime.jsx)(AndroidSessionMetric, { label: t("androidRuntime.sessionTokens"), name: "sessionTokens", value: androidSessionNumber(t, item.sessionTokens) }),
            (0, react_jsx_runtime.jsx)(AndroidSessionMetric, { label: t("androidRuntime.sessionInput"), name: "inputTokens", value: androidSessionNumber(t, item.inputTokens) }),
            (0, react_jsx_runtime.jsx)(AndroidSessionMetric, { label: t("androidRuntime.sessionOutput"), name: "outputTokens", value: androidSessionNumber(t, item.outputTokens) }),
            (0, react_jsx_runtime.jsx)(AndroidSessionMetric, { label: t("androidRuntime.sessionTotal"), name: "totalTokens", value: androidSessionNumber(t, item.totalTokens) }),
            (0, react_jsx_runtime.jsx)(AndroidSessionMetric, { label: t("androidRuntime.sessionSpeed"), name: "tokensPerSecond", value: item.tokensPerSecond === null ? t("androidRuntime.statsUnavailable") : t("androidRuntime.speedValue", { speed: androidSessionNumber(t, item.tokensPerSecond, 1) }) }),
            (0, react_jsx_runtime.jsx)(AndroidSessionMetric, { label: t("androidRuntime.sessionCache"), name: "cacheHit", value: androidSessionCache(t, item) }),
            (0, react_jsx_runtime.jsx)(AndroidSessionMetric, { label: t("androidRuntime.sessionCacheWrite"), name: "cacheWriteTokens", value: androidSessionNumber(t, item.cacheWriteTokens) }),
            (0, react_jsx_runtime.jsx)(AndroidSessionMetric, { label: t("androidRuntime.sessionContext"), name: "context", value: androidSessionContext(t, item) })
          ] }),
          (0, react_jsx_runtime.jsx)("p", { className: "dshAndroidSessionScope", children: t("androidRuntime.statsScope") }),
          failed && (0, react_jsx_runtime.jsx)("p", { role: "alert", className: "dshAndroidSettingsError", children: t("androidRuntime.sessionOpenError") })
        ]
      });
    }
    function AndroidRuntimeSection({ t }) {
      const { state, busy, error, perform } = useAndroidSettings();
      const status = state.status;
      const runtime = status && status.runtime;
      const notifications = status && status.notifications;
      const unknown = t("androidSettings.checking");
      return (0, react_jsx_runtime.jsxs)("section", {
        className: "dshAndroidSettings", "data-dsh-android-runtime-settings": "", "aria-label": t("androidRuntime.heading"),
        children: [
          (0, react_jsx_runtime.jsx)("h2", { children: t("androidRuntime.heading") }),
          (0, react_jsx_runtime.jsx)("p", { children: t("androidRuntime.background") }),
          runtime && (0, react_jsx_runtime.jsxs)("div", { className: "dshAndroidSessionList", "data-dsh-android-session-list": "", children: [
            (0, react_jsx_runtime.jsx)("h3", { children: t("androidRuntime.activeSessions") }),
            runtime.sessions.map((item) => (0, react_jsx_runtime.jsx)(AndroidSessionSummary, { t, item }, item.sessionId)),
            runtime.sessions.length === 0 && (0, react_jsx_runtime.jsx)("p", { children: t(runtime.connected && runtime.running === 0 && runtime.waiting === 0 && runtime.sessionsComplete ? "androidRuntime.noActiveSessions" : "androidRuntime.sessionDetailsUnavailable") }),
            !runtime.sessionsComplete && (0, react_jsx_runtime.jsx)("p", { children: t("androidRuntime.sessionDetailsPartial") })
          ] }),
          (0, react_jsx_runtime.jsxs)("dl", { children: [
            (0, react_jsx_runtime.jsx)(AndroidSettingsRow, { label: t("androidRuntime.service"), value: !runtime ? unknown : t(runtime.hostRunning ? "androidRuntime.running" : "androidRuntime.stopped") }),
            (0, react_jsx_runtime.jsx)(AndroidSettingsRow, { label: t("androidRuntime.connection"), value: !runtime ? unknown : t(runtime.connected ? "androidSettings.connected" : "androidSettings.disconnected") }),
            (0, react_jsx_runtime.jsx)(AndroidSettingsRow, { label: t("androidRuntime.tasks"), value: !runtime ? unknown : t("androidRuntime.taskCount", { running: runtime.running, waiting: runtime.waiting }) }),
            (0, react_jsx_runtime.jsx)(AndroidSettingsRow, { label: t("androidRuntime.notification"), value: !notifications ? unknown : t(notifications.enabled ? "androidSettings.on" : "androidSettings.off") }),
            (0, react_jsx_runtime.jsx)(AndroidSettingsRow, { label: t("androidRuntime.battery"), value: !status ? unknown : t(status.battery.unrestricted ? "androidRuntime.unrestricted" : "androidRuntime.optimized") })
          ] }),
          (0, react_jsx_runtime.jsx)("p", { children: t("androidRuntime.notifications") }),
          notifications && notifications.channelsDisabled && (0, react_jsx_runtime.jsx)("p", { children: t("androidRuntime.channelHelp") }),
          (0, react_jsx_runtime.jsxs)("div", { className: "dshAndroidSettingsActions", children: [
            (0, react_jsx_runtime.jsx)(AndroidSettingsButton, { t, label: notifications && notifications.permissionRequired && !notifications.permissionGranted ? "androidRuntime.allowNotifications" : "androidRuntime.notificationSettings", type: "open-notifications", busy, connected: state.connected, perform }),
            (0, react_jsx_runtime.jsx)(AndroidSettingsButton, { t, label: "androidRuntime.batterySettings", type: "open-battery", busy, connected: state.connected, perform })
          ] }),
          (0, react_jsx_runtime.jsx)("p", { children: t("androidRuntime.batteryHelp") }),
          (0, react_jsx_runtime.jsx)(AndroidSettingsError, { t, error: error || state.error })
        ]
      });
    }
`;

const dictionaries = {
  zh: {
    "androidSettings.checking": "正在读取状态…",
    "androidSettings.on": "已开启", "androidSettings.off": "未开启",
    "androidSettings.connected": "已连接", "androidSettings.disconnected": "未连接",
    "androidSettings.configured": "已配置", "androidSettings.notConfigured": "未配置",
    "androidSettings.available": "可用", "androidSettings.unavailable": "不可用",
    "androidSettings.error.user_gesture_required": "请直接点击按钮完成此操作。",
    "androidSettings.error.control_unavailable": "手机控制暂不可用。请开启无障碍服务并解锁手机。",
    "androidSettings.error.settings_unavailable": "无法打开系统设置，请在 Android 设置中手动打开。",
    "androidSettings.error.permission_request_pending": "请先完成系统中的权限选择。",
    "androidSettings.error.unavailable": "暂时无法读取或更新 Android 状态，请稍后重新打开此页面。",
    "mobileUse.description": "让 DSH 操作当前前台界面，或切换到已安装的应用。后台不可见的窗口无法直接操作。",
    "mobileUse.accessibility": "无障碍服务", "mobileUse.connection": "服务连接",
    "mobileUse.control": "手机控制", "mobileUse.currentApp": "当前应用",
    "mobileUse.active": "已允许", "mobileUse.paused": "已暂停", "mobileUse.locked": "设备已锁定",
    "mobileUse.noApp": "暂无可用界面", "mobileUse.systemSettings": "打开无障碍设置",
    "mobileUse.allow": "允许本次控制", "mobileUse.pause": "暂停控制",
    "mobileUse.grantHelp": "开启无障碍服务后，仍需在此手动允许控制。可随时在此或通知中暂停。",
    "mobileUse.feedback": "显示操作提示", "mobileUse.feedbackHelp": "标出点击、输入和滑动位置。提示不拦截触摸，也不会出现在截图中。",
    "androidSecurity.heading": "ADB 与 Root Shell", "androidSecurity.description": "这些能力默认关闭。ADB 使用你选择的 ARM64 客户端；Root Shell 只有设备提供 su 时可用。启用后，MOBILEUSE 才能使用对应功能。",
    "androidSecurity.adbClient": "ADB 客户端", "androidSecurity.adbConnection": "ADB 状态", "androidSecurity.rootAvailability": "Root Shell",
    "androidSecurity.importAdb": "导入 ADB 客户端", "androidSecurity.enableAdb": "启用 ADB", "androidSecurity.disableAdb": "停用 ADB",
    "androidSecurity.enableRoot": "启用 Root Shell", "androidSecurity.disableRoot": "停用 Root Shell",
    "androidSecurity.target": "ADB 目标序列号", "androidSecurity.targetPlaceholder": "留空使用默认设备", "androidSecurity.saveTarget": "保存目标",
    "androidSecurity.help": "ADB 目标只保存序列号，不保存配对凭据。停用能力会终止正在运行的子进程。",
    "androidRuntime.nav": "后台", "androidRuntime.heading": "后台与通知",
    "androidRuntime.service": "DSH 后台服务", "androidRuntime.connection": "任务状态连接",
    "androidRuntime.running": "运行中", "androidRuntime.stopped": "未运行",
    "androidRuntime.tasks": "当前任务", "androidRuntime.taskCount": "{running} 个运行中 · {waiting} 个等待操作",
    "androidRuntime.activeSessions": "运行中的会话", "androidRuntime.untitledSession": "未命名会话",
    "androidRuntime.sessionRunning": "运行中", "androidRuntime.sessionWaiting": "等待操作",
    "androidRuntime.sessionTurns": "累计轮次", "androidRuntime.sessionSteps": "累计步骤",
    "androidRuntime.sessionTokens": "会话累计 token 消耗",
    "androidRuntime.sessionInput": "最近步骤未缓存输入 token", "androidRuntime.sessionOutput": "最近步骤输出 token",
    "androidRuntime.sessionTotal": "最近步骤总 token", "androidRuntime.sessionSpeed": "平均模型调用速度",
    "androidRuntime.sessionCache": "最近步骤缓存命中", "androidRuntime.sessionContext": "当前上下文",
    "androidRuntime.sessionCacheWrite": "最近步骤缓存写入 token",
    "androidRuntime.statsUnavailable": "暂不可用", "androidRuntime.numberLocale": "zh-CN",
    "androidRuntime.speedValue": "{speed} tok/s", "androidRuntime.cachedCount": "{tokens} token 命中缓存",
    "androidRuntime.cachedPercent": "{tokens} token · {percent}%",
    "androidRuntime.contextCount": "{used} token", "androidRuntime.contextCapacity": "{used} / {capacity} token · {percent}%",
    "androidRuntime.contextCapacityOnly": "容量 {capacity} token · 用量暂不可用",
    "androidRuntime.statsScope": "轮次、步骤与会话 token 消耗为累计值；其余 token 和缓存来自最近已完成步骤的模型统计。缓存命中率按未缓存输入、缓存读取和写入之和计算；速度为该次模型调用平均值，上下文来自 DSH 的实际统计。未报告的数据不作估算。",
    "androidRuntime.noActiveSessions": "暂无运行中的会话。",
    "androidRuntime.sessionDetailsUnavailable": "暂时无法读取会话详情。",
    "androidRuntime.sessionDetailsPartial": "部分会话详情暂不可用，未显示的会话仍可能在运行。",
    "androidRuntime.sessionOpenError": "暂时无法打开此会话，请从侧边栏选择。",
    "androidRuntime.notification": "通知", "androidRuntime.battery": "电池使用",
    "androidRuntime.unrestricted": "不受限制", "androidRuntime.optimized": "系统优化",
    "androidRuntime.background": "切换应用后，DSH 通过前台服务继续当前任务。系统强制停止仍会中断任务，已保存的会话会保留。",
    "androidRuntime.notifications": "在等待回答或操作确认时提醒你。点击通知返回对应会话，在 DSH 中回答或确认。",
    "androidRuntime.channelHelp": "部分通知类别已被系统关闭，可在通知设置中分别开启。",
    "androidRuntime.allowNotifications": "允许通知", "androidRuntime.notificationSettings": "通知设置",
    "androidRuntime.batterySettings": "电池设置",
    "androidRuntime.batteryHelp": "后台任务易被中断时，可在系统中调整电池限制。不同厂商的后台策略可能仍影响运行。"
  },
  en: {
    "androidSettings.checking": "Reading status…",
    "androidSettings.on": "On", "androidSettings.off": "Off",
    "androidSettings.connected": "Connected", "androidSettings.disconnected": "Disconnected",
    "androidSettings.configured": "Configured", "androidSettings.notConfigured": "Not configured",
    "androidSettings.available": "Available", "androidSettings.unavailable": "Unavailable",
    "androidSettings.error.user_gesture_required": "Tap the button directly to perform this action.",
    "androidSettings.error.control_unavailable": "Phone control is unavailable. Enable Accessibility and unlock the device.",
    "androidSettings.error.settings_unavailable": "Could not open system settings. Open them manually in Android Settings.",
    "androidSettings.error.permission_request_pending": "Complete the permission prompt in Android first.",
    "androidSettings.error.unavailable": "Could not read or update Android status. Reopen this page shortly.",
    "mobileUse.description": "Let DSH operate the current screen or switch to an installed app. Hidden background windows cannot be operated directly.",
    "mobileUse.accessibility": "Accessibility service", "mobileUse.connection": "Service connection",
    "mobileUse.control": "Phone control", "mobileUse.currentApp": "Current app",
    "mobileUse.active": "Allowed", "mobileUse.paused": "Paused", "mobileUse.locked": "Device locked",
    "mobileUse.noApp": "No available screen", "mobileUse.systemSettings": "Accessibility settings",
    "mobileUse.allow": "Allow this control", "mobileUse.pause": "Pause control",
    "mobileUse.grantHelp": "After enabling Accessibility, allow control here manually. Pause here or from the notification at any time.",
    "mobileUse.feedback": "Show action feedback", "mobileUse.feedbackHelp": "Mark taps, typing, and swipes. Feedback does not intercept touch or appear in screenshots.",
    "androidSecurity.heading": "ADB and Root Shell", "androidSecurity.description": "These capabilities are off by default. ADB uses an ARM64 client that you choose; Root Shell is available only when the device provides su. Enable each capability before MOBILEUSE can use it.",
    "androidSecurity.adbClient": "ADB client", "androidSecurity.adbConnection": "ADB status", "androidSecurity.rootAvailability": "Root Shell",
    "androidSecurity.importAdb": "Import ADB client", "androidSecurity.enableAdb": "Enable ADB", "androidSecurity.disableAdb": "Disable ADB",
    "androidSecurity.enableRoot": "Enable Root Shell", "androidSecurity.disableRoot": "Disable Root Shell",
    "androidSecurity.target": "ADB target serial", "androidSecurity.targetPlaceholder": "Empty uses default device", "androidSecurity.saveTarget": "Save target",
    "androidSecurity.help": "Only the serial is stored; pairing credentials are not. Disabling a capability terminates its active child processes.",
    "androidRuntime.nav": "Background", "androidRuntime.heading": "Background & notifications",
    "androidRuntime.service": "DSH background service", "androidRuntime.connection": "Task status connection",
    "androidRuntime.running": "Running", "androidRuntime.stopped": "Stopped",
    "androidRuntime.tasks": "Current tasks", "androidRuntime.taskCount": "{running} running · {waiting} waiting for input",
    "androidRuntime.activeSessions": "Active conversations", "androidRuntime.untitledSession": "Untitled conversation",
    "androidRuntime.sessionRunning": "Running", "androidRuntime.sessionWaiting": "Waiting for input",
    "androidRuntime.sessionTurns": "Cumulative turns", "androidRuntime.sessionSteps": "Cumulative steps",
    "androidRuntime.sessionTokens": "Session token consumption",
    "androidRuntime.sessionInput": "Latest step uncached input tokens", "androidRuntime.sessionOutput": "Latest step output tokens",
    "androidRuntime.sessionTotal": "Latest step total tokens", "androidRuntime.sessionSpeed": "Average model-call speed",
    "androidRuntime.sessionCache": "Latest step cache hit", "androidRuntime.sessionContext": "Current context",
    "androidRuntime.sessionCacheWrite": "Latest step cache write tokens",
    "androidRuntime.statsUnavailable": "Unavailable", "androidRuntime.numberLocale": "en-US",
    "androidRuntime.speedValue": "{speed} tok/s", "androidRuntime.cachedCount": "{tokens} cached tokens",
    "androidRuntime.cachedPercent": "{tokens} tokens · {percent}%",
    "androidRuntime.contextCount": "{used} tokens", "androidRuntime.contextCapacity": "{used} / {capacity} tokens · {percent}%",
    "androidRuntime.contextCapacityOnly": "Capacity {capacity} tokens · usage unavailable",
    "androidRuntime.statsScope": "Turns, steps, and session token consumption are cumulative. Other tokens and cache come from the latest completed step's provider statistics. Cache hit divides cached reads by uncached input plus cache reads and writes; speed is that model call's average and context comes from DSH statistics. Missing data is not estimated.",
    "androidRuntime.noActiveSessions": "No active conversations.",
    "androidRuntime.sessionDetailsUnavailable": "Conversation details are unavailable.",
    "androidRuntime.sessionDetailsPartial": "Some conversation details are unavailable. Conversations not shown may still be running.",
    "androidRuntime.sessionOpenError": "Could not open this conversation. Select it from the sidebar.",
    "androidRuntime.notification": "Notifications", "androidRuntime.battery": "Battery use",
    "androidRuntime.unrestricted": "Unrestricted", "androidRuntime.optimized": "System optimized",
    "androidRuntime.background": "A foreground service continues tasks when you switch apps. A system force-stop still interrupts tasks; saved conversations remain available.",
    "androidRuntime.notifications": "Get notified when an answer or approval is needed. Tap to return to its conversation, then respond in DSH.",
    "androidRuntime.channelHelp": "Some notification categories are disabled. Enable them individually in notification settings.",
    "androidRuntime.allowNotifications": "Allow notifications", "androidRuntime.notificationSettings": "Notification settings",
    "androidRuntime.batterySettings": "Battery settings",
    "androidRuntime.batteryHelp": "If background tasks are interrupted, adjust battery restrictions in system settings. Manufacturer policies may still affect execution."
  }
};

function dictionaryPatch(source, language) {
  const pattern = new RegExp(`(    const ${language} = \\{)([\\s\\S]*?)(\\n    \\};)`);
  const match = pattern.exec(source);
  if (!match) throw new Error(`dsh Android settings UI: missing ${language} dictionary`);
  let body = match[2];
  const additions = [];
  for (const [key, value] of Object.entries(dictionaries[language])) {
    const expression = new RegExp(`("${key.replace(/[.*+?^${}()|[\]\\]/g, "\\$&")}": )"(?:[^"\\\\]|\\\\.)*"`, "g");
    const entries = [...body.matchAll(expression)];
    if (entries.length > 1) throw new Error(`dsh Android settings UI: duplicate ${language}.${key}`);
    if (entries.length === 1) body = body.replace(expression, (_, prefix) => prefix + JSON.stringify(value));
    else additions.push(`      ${JSON.stringify(key)}: ${JSON.stringify(value)},`);
  }
  if (additions.length === 0 && body === match[2]) return source;
  return source.replace(match[0], match[1] + "\n" + additions.join("\n") + body + match[3]);
}

function dictionaryRemove(source, language, keys) {
  for (const key of keys) {
    const expression = new RegExp(`\\n[ \\t]*"${key.replace(/[.*+?^${}()|[\\]\\\\]/g, "\\\\$&")}": (?:"(?:[^"\\\\]|\\\\.)*"|[^,\\n]+),?`, "g");
    source = source.replace(expression, "");
  }
  return source;
}

/** Runs after the existing Android navigation/runtime section patch. */
export async function patchAndroidSettingsUi(root) {
  const filename = join(root, "node_modules/@deepseek-ai/dsh-client-ui-settings-general/lib/client.js");
  let source = await readFile(filename, "utf8");
  const start = source.indexOf(BEGIN);
  const end = source.indexOf(END, start);
  if (start === -1 || end === -1 || source.split(BEGIN).length !== 2 || source.split(END).length !== 2) {
    throw new Error("dsh Android settings UI: missing unique production sections");
  }
  if (source.includes(MARKER)) {
    const previousStart = source.indexOf('    const androidSettingsStyleId =');
    const previousHash = previousStart >= 0 && previousStart < start ?
      createHash("sha256").update(source.slice(previousStart, end)).digest("hex") : null;
    const previous = previousHash === PRE_LIVE_SECTIONS_SHA;
    const previousAutoLive = previousHash === PRE_AUTO_LIVE_SECTIONS_SHA;
    const replacePrevious = previous || previousAutoLive;
    if (source.split(MARKER).length !== 2 || (!replacePrevious && source.split(sectionCode).length !== 2) ||
      source.split(OPENER_CODE).length !== 2 ||
      source.split(LOCALE_CODE).length !== 2 ||
      source.includes('window.location.assign("/__dsh_android__/mobile-control")') ||
      source.includes('window.location.assign("/__dsh_android__/runtime-settings")')) {
      throw new Error("dsh Android settings UI: damaged patched sections");
    }
    for (const language of ["zh", "en"]) {
      const match = new RegExp(`    const ${language} = (\\{[\\s\\S]*?\\n    \\});`).exec(source);
      if (!match) throw new Error("dsh Android settings UI: damaged dictionary");
      const values = JSON.parse(match[1]);
      for (const [key, value] of Object.entries(dictionaries[language])) {
        if (values[key] !== value) throw new Error(`dsh Android settings UI: damaged ${language}.${key}`);
      }
    }
    if (replacePrevious) {
      source = source.slice(0, previousStart) + sectionCode + source.slice(end);
      if (previousAutoLive) source = dictionaryRemove(source, "zh", REMOVED_LIVE_KEYS);
      if (previousAutoLive) source = dictionaryRemove(source, "en", REMOVED_LIVE_KEYS);
      source = dictionaryPatch(dictionaryPatch(source, "zh"), "en");
    }
  } else {
    if (createHash("sha256").update(source.slice(start, end)).digest("hex") !== LEGACY_SECTIONS_SHA) {
      throw new Error("dsh Android settings UI: unsupported production section revision");
    }
    source = source.slice(0, start) + sectionCode + source.slice(end);
    if (source.split(OPENER_ANCHOR).length !== 2) throw new Error("dsh Android settings UI: unsupported settings controller hook");
    source = source.replace(OPENER_ANCHOR, OPENER_CODE);
    if (source.split(LOCALE_ANCHOR).length !== 2) throw new Error("dsh Android settings UI: unsupported global locale hook");
    source = source.replace(LOCALE_ANCHOR, LOCALE_CODE);
    source = dictionaryPatch(dictionaryPatch(source, "zh"), "en");
    source = MARKER + "\n" + source;
  }
  const dist = join(root, "node_modules/@deepseek-ai/dsh-web-frontend/dist");
  const indexFile = join(dist, "index.html");
  const index = await readFile(indexFile, "utf8");
  if (index.split("<head>").length !== 2 ||
    (index.includes("data-dsh-android-settings") &&
      (index.split("data-dsh-android-settings").length !== 2 || index.split(SCRIPT_TAG).length !== 2))) {
    throw new Error("dsh Android settings UI: unsupported frontend script hook");
  }
  const nextIndex = index.includes("data-dsh-android-settings") ? index : index.replace("<head>", `<head>\n    ${SCRIPT_TAG}`);
  const helper = await readFile(new URL("./android-settings-ui.js", import.meta.url), "utf8");
  await writeFile(filename, source);
  await writeFile(join(dist, "assets/dsh-android-settings-ui.js"), helper);
  await writeFile(indexFile, nextIndex);
  console.log("patched: dsh Android UI: embedded phone control and background settings");
}

if (process.argv[1] && resolve(process.argv[1]) === fileURLToPath(import.meta.url)) {
  if (!process.argv[2]) throw new Error("usage: patch-android-settings-ui.mjs <dsh-package-directory>");
  await patchAndroidSettingsUi(resolve(process.argv[2]));
}
