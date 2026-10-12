/* Android settings transport for the real DeepSeek Harness settings sections. */
(function () {
  "use strict";
  var port = null;
  var nextId = 0;
  var listeners = [];
  var pending = Object.create(null);
  var pollTimer = null;
  var handshakeTimer = null;
  var localeOwner = null;
  var latestLocale = "en";
  var languageAttempt = null;
  var languageInFlight = null;
  var snapshot = Object.freeze({ status: null, error: null, connected: false });
  var reasons = ["disabled", "disconnected", "device_locked", "user_paused", "user_grant", "host_started", "host_stopped", "service_disconnected", "service_interrupted", "unavailable"];
  var errors = ["invalid_request", "user_gesture_required", "control_unavailable", "settings_unavailable", "permission_request_pending", "import_cancelled", "import_failed", "adb_unavailable", "root_unavailable", "invalid_target", "unavailable"];
  var actions = ["open-accessibility", "allow-control", "pause-control", "set-feedback", "open-notifications", "open-battery", "open-adb-file", "set-adb-enabled", "set-root-enabled", "set-adb-target"];

  function nullableCount(value) {
    return value === null || (Number.isSafeInteger(value) && value >= 0);
  }

  function validSessions(values) {
    if (!Array.isArray(values) || values.length > 1000) return null;
    var ids = Object.create(null), result = [];
    for (var index = 0; index < values.length; index++) {
      var item = values[index];
      if (!item || typeof item !== "object" || typeof item.sessionId !== "string" ||
        item.sessionId.length < 1 || item.sessionId.length > 160 || !/^[A-Za-z0-9._:-]+$/.test(item.sessionId) ||
        ids[item.sessionId] || (item.name !== null && (typeof item.name !== "string" || item.name.length < 1 ||
          item.name.length > 160 || /[\u0000-\u001f\u007f]/.test(item.name))) ||
        (item.state !== "running" && item.state !== "waiting") ||
        ![item.turns, item.steps, item.sessionTokens, item.inputTokens, item.outputTokens, item.totalTokens,
          item.cachedInputTokens, item.cacheWriteTokens, item.contextUsed, item.contextCapacity].every(nullableCount) ||
        item.contextCapacity === 0 ||
        (item.tokensPerSecond !== null && (typeof item.tokensPerSecond !== "number" ||
          !Number.isFinite(item.tokensPerSecond) || item.tokensPerSecond < 0 || item.tokensPerSecond > 1000000000000))) return null;
      ids[item.sessionId] = true;
      // IDs and names remain in this checked private in-app port. No prompt,
      // model/provider configuration, nonce, or arbitrary session body is kept.
      result.push(Object.freeze({ sessionId: item.sessionId, name: item.name, state: item.state,
        turns: item.turns, steps: item.steps, sessionTokens: item.sessionTokens, inputTokens: item.inputTokens, outputTokens: item.outputTokens,
        totalTokens: item.totalTokens, cachedInputTokens: item.cachedInputTokens, cacheWriteTokens: item.cacheWriteTokens,
        tokensPerSecond: item.tokensPerSecond, contextUsed: item.contextUsed, contextCapacity: item.contextCapacity }));
    }
    return Object.freeze(result);
  }

  function validStatus(value) {
    if (!value || typeof value !== "object") return null;
    var m = value.mobile, r = value.runtime, n = value.notifications, b = value.battery, c = value.capabilities;
    var sessions = r && validSessions(r.sessions);
    if (!m || !r || !n || !b ||
      [m.enabled, m.connected, m.active, m.feedback, m.canAllow, r.hostRunning, r.connected,
        n.enabled, n.permissionRequired, n.permissionGranted, n.channelsDisabled, b.unrestricted, r.sessionsComplete]
        .some(function (item) { return typeof item !== "boolean"; }) ||
      !sessions ||
      reasons.indexOf(m.reason) === -1 ||
      (m.currentPackage !== null && (typeof m.currentPackage !== "string" || m.currentPackage.length > 255 ||
        !/^[A-Za-z][A-Za-z0-9_]*(?:\.[A-Za-z][A-Za-z0-9_]*)+$/.test(m.currentPackage))) ||
      !Number.isSafeInteger(r.running) || r.running < 0 || r.running > 100000 ||
      !Number.isSafeInteger(r.waiting) || r.waiting < 0 || r.waiting > 100000) return null;
    if (c !== undefined && (!c || typeof c !== "object" || typeof c.adbConfigured !== "boolean" ||
      typeof c.adbEnabled !== "boolean" || typeof c.adbConnected !== "boolean" ||
      typeof c.rootAvailable !== "boolean" || typeof c.rootEnabled !== "boolean" ||
      (c.adbTarget !== null && (typeof c.adbTarget !== "string" || c.adbTarget.length > 128 ||
        !/^[A-Za-z0-9._:-]+$/.test(c.adbTarget))))) return null;
    // Copy only the agreed display fields. Session IDs are needed for the
    // existing Controller-owned opener and never appear as user-visible text.
    return Object.freeze({
      mobile: Object.freeze({ enabled: m.enabled, connected: m.connected, active: m.active,
        reason: m.reason, currentPackage: m.currentPackage, feedback: m.feedback, canAllow: m.canAllow }),
      runtime: Object.freeze({ hostRunning: r.hostRunning, connected: r.connected, running: r.running, waiting: r.waiting,
        sessions: sessions, sessionsComplete: r.sessionsComplete }),
      notifications: Object.freeze({ enabled: n.enabled, permissionRequired: n.permissionRequired,
        permissionGranted: n.permissionGranted, channelsDisabled: n.channelsDisabled,
        }),
      battery: Object.freeze({ unrestricted: b.unrestricted }),
      capabilities: c === undefined ? null : (c && typeof c === "object" &&
        typeof c.adbConfigured === "boolean" && typeof c.adbEnabled === "boolean" &&
        typeof c.adbConnected === "boolean" && typeof c.rootAvailable === "boolean" &&
        typeof c.rootEnabled === "boolean" && (c.adbTarget === null ||
          (typeof c.adbTarget === "string" && c.adbTarget.length <= 128 && /^[A-Za-z0-9._:-]+$/.test(c.adbTarget))) ?
        Object.freeze({ adbConfigured: c.adbConfigured, adbEnabled: c.adbEnabled, adbConnected: c.adbConnected,
          adbTarget: c.adbTarget, rootAvailable: c.rootAvailable, rootEnabled: c.rootEnabled }) : null)
    });
  }

  function publish(status, error) {
    snapshot = Object.freeze({ status: status, error: error, connected: !!port });
    listeners.slice().forEach(function (listener) { listener(); });
  }

  function finish(id, ok, value) {
    var entry = pending[id];
    if (!entry) return;
    delete pending[id];
    window.clearTimeout(entry.timer);
    if (ok) entry.resolve(value);
    else entry.reject(new Error(value));
  }

  function disconnect(error) {
    if (port && typeof port.close === "function") port.close();
    port = null;
    languageAttempt = null;
    languageInFlight = null;
    Object.keys(pending).forEach(function (id) { finish(id, false, error); });
    publish(null, error);
  }

  function receive(event) {
    var value;
    try { value = JSON.parse(event.data); } catch (_) { return; }
    if (!value || typeof value !== "object" || typeof value.ok !== "boolean") return;
    var isPush = value.id === null;
    if (!isPush && (typeof value.id !== "string" || !pending[value.id])) return;
    var status = value.status === undefined ? null : validStatus(value.status);
    if (value.status !== undefined && !status) {
      if (!isPush) finish(value.id, false, "unavailable");
      return;
    }
    if (value.ok) {
      if (!status) {
        if (!isPush) finish(value.id, false, "unavailable");
        return;
      }
      publish(status, null);
      if (!isPush) finish(value.id, true, status);
    } else if (!isPush) {
      var error = errors.indexOf(value.error) === -1 ? "unavailable" : value.error;
      publish(status || snapshot.status, error);
      finish(value.id, false, error);
    }
  }

  function send(type, enabled, locale, serial) {
    if (!port) return Promise.reject(new Error("unavailable"));
    if (Object.keys(pending).length >= 4) return Promise.reject(new Error("unavailable"));
    var id = "settings-" + (++nextId);
    var message = { id: id, type: type };
    if (type === "set-feedback") message.enabled = enabled;
    if (type === "set-adb-enabled" || type === "set-root-enabled") message.enabled = enabled;
    if (type === "set-adb-target") message.serial = serial;
    if (type === "sync-language") message.locale = locale;
    return new Promise(function (resolve, reject) {
      pending[id] = { resolve: resolve, reject: reject, timer: window.setTimeout(function () {
        if (pending[id]) {
          finish(id, false, "unavailable");
          publish(null, "unavailable");
        }
      }, 8000) };
      try { port.postMessage(JSON.stringify(message)); }
      catch (_) { disconnect("unavailable"); }
    });
  }

  function sendLanguage() {
    if (!localeOwner || !port || languageInFlight || languageAttempt === latestLocale) return;
    var request = { locale: latestLocale, endpoint: port };
    languageAttempt = latestLocale;
    languageInFlight = request;
    send("sync-language", undefined, request.locale).catch(function () {}).finally(function () {
      if (languageInFlight !== request) return;
      languageInFlight = null;
      if (port === request.endpoint && languageAttempt !== latestLocale) sendLanguage();
    });
  }

  function refresh() {
    if (!listeners.length || document.visibilityState === "hidden") return;
    if (Object.keys(pending).some(function (id) { return pending[id].status; })) return;
    var before = nextId;
    var result = send("status");
    if (nextId !== before && pending["settings-" + nextId]) pending["settings-" + nextId].status = true;
    result.catch(function () {});
  }

  function updatePolling() {
    if (pollTimer !== null) window.clearInterval(pollTimer);
    pollTimer = null;
    if (handshakeTimer !== null) window.clearTimeout(handshakeTimer);
    handshakeTimer = null;
    if (listeners.length && document.visibilityState !== "hidden") {
      refresh();
      pollTimer = window.setInterval(refresh, 2000);
      if (!port) handshakeTimer = window.setTimeout(function () {
        handshakeTimer = null;
        if (!port && listeners.length) publish(null, "unavailable");
      }, 8000);
    }
  }

  window.addEventListener("message", function (event) {
    if (event.data !== "dsh.android.settings.port.v1" || event.source !== null ||
      (event.origin !== "" && event.origin !== "null" && event.origin !== window.location.origin) ||
      !event.ports || event.ports.length !== 1) return;
    disconnect("unavailable");
    port = event.ports[0];
    var endpoint = port;
    port.onmessage = function (message) { if (port === endpoint) receive(message); };
    if (typeof port.start === "function") port.start();
    publish(null, null);
    sendLanguage();
    updatePolling();
  });
  document.addEventListener("visibilitychange", updatePolling);
  window.addEventListener("focus", refresh);
  window.addEventListener("pagehide", function () {
    if (pollTimer !== null) window.clearInterval(pollTimer);
    pollTimer = null;
    if (handshakeTimer !== null) window.clearTimeout(handshakeTimer);
    handshakeTimer = null;
    disconnect("unavailable");
  });

  window.DshAndroidSettings = Object.freeze({
    getSnapshot: function () { return snapshot; },
    subscribe: function (listener) {
      if (typeof listener !== "function") throw new TypeError("settings listener");
      listeners.push(listener);
      updatePolling();
      var active = true;
      return function () {
        if (!active) return;
        active = false;
        listeners = listeners.filter(function (item) { return item !== listener; });
        updatePolling();
      };
    },
    installLocale: function (locale) {
      if (!locale || typeof locale.getSnapshot !== "function" || typeof locale.subscribe !== "function") throw new TypeError("DSH locale owner");
      if (localeOwner) localeOwner.stop();
      var owner = { stop: function () {} };
      localeOwner = owner;
      languageAttempt = null;
      function sync() {
        if (localeOwner !== owner) return;
        var value;
        try { value = locale.getSnapshot(); } catch (_) { value = null; }
        latestLocale = value && value.active === "zh" ? "zh" : "en";
        sendLanguage();
      }
      var stop = locale.subscribe(sync), active = true;
      owner.stop = function () { if (active) { active = false; stop(); } };
      sync();
      return function () {
        owner.stop();
        if (localeOwner === owner) { localeOwner = null; languageAttempt = null; }
      };
    },
    request: function (type, enabled, event, serial) {
      if (actions.indexOf(type) === -1 || (type === "set-feedback" && typeof enabled !== "boolean") ||
        ((type === "set-adb-enabled" || type === "set-root-enabled") && typeof enabled !== "boolean") ||
        (type !== "set-feedback" && type !== "set-adb-enabled" && type !== "set-root-enabled" && enabled !== undefined) ||
        (type === "set-adb-target" && (typeof serial !== "string" || serial.length > 128 || !/^[A-Za-z0-9._:-]*$/.test(serial)))) return Promise.reject(new Error("invalid_request"));
      // The bridge independently consumes a real native WebView touch. This
      // DOM check is an extra guard, never the source of control permission.
      if (!event || event.isTrusted !== true) return Promise.reject(new Error("user_gesture_required"));
      return send(type, enabled, undefined, serial);
    }
  });
})();
