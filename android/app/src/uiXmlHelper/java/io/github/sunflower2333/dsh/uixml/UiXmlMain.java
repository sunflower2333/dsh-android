package io.github.sunflower2333.dsh.uixml;

import android.accessibilityservice.AccessibilityServiceInfo;
import android.app.UiAutomation;
import android.graphics.Rect;
import android.os.HandlerThread;
import android.os.Looper;
import android.os.Process;
import android.os.SystemClock;
import android.view.accessibility.AccessibilityNodeInfo;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;

/** One-shot shell UID helper. No installed app, instrumentation, or control grant. */
public final class UiXmlMain {
    private static final int MAX_BYTES = 60 * 1024;
    private static final int MAX_NODES = 256;
    private static final int MAX_DEPTH = 48;
    private static final int MAX_FIELD_CHARS = 512;
    private static final int MAX_TEXT_CHARS = 8192;
    private static final long WORK_TIMEOUT_MS = 7_500;
    private static final long HARD_TIMEOUT_MS = 9_000;
    private static final int EXIT_CAPTURE = 65;
    private static final int EXIT_TIMEOUT = 124;

    private static final class CaptureFailure extends Exception {
        private static final long serialVersionUID = 1L;
        final String code;
        CaptureFailure(String code) { this.code = code; }
    }

    private static final class Session {
        private boolean closed;
        private final HandlerThread callbackThread = new HandlerThread("dsh-ui-xml-callback");
        private volatile Object connection;
        private volatile UiAutomation automation;
        private volatile Method disconnect;
        private volatile Method directDisconnect;
        private volatile boolean connectAttempted;
        private volatile boolean clean = true;

        UiAutomation open() throws Exception {
            Class<?> connectionType = Class.forName("android.app.IUiAutomationConnection");
            Class<?> implementation = Class.forName("android.app.UiAutomationConnection");
            Constructor<?> constructor = UiAutomation.class.getConstructor(Looper.class, connectionType);
            Method connect = UiAutomation.class.getMethod("connect", int.class);
            disconnect = UiAutomation.class.getMethod("disconnect");
            directDisconnect = implementation.getMethod("disconnect");
            connection = implementation.getConstructor().newInstance();
            callbackThread.setDaemon(true);
            callbackThread.start();
            automation = (UiAutomation) constructor.newInstance(callbackThread.getLooper(), connection);
            connectAttempted = true;
            // AOSP default flags=0 suppress other Accessibility services. Never use that path.
            connect.invoke(automation, UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES);
            return automation;
        }

        synchronized boolean close() {
            if (closed) return clean;
            closed = true;
            if (connectAttempted) {
                try {
                    disconnect.invoke(automation);
                } catch (Exception clientFailure) {
                    // connect() can fail after framework registration but before client init.
                    // Bypass the client connection-state check to unregister that partial session.
                    try {
                        directDisconnect.invoke(connection);
                    } catch (Exception connectionFailure) {
                        clean = false;
                    }
                }
            }
            callbackThread.quitSafely();
            try {
                callbackThread.join(200);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                clean = false;
            }
            return clean;
        }
    }

    @SuppressWarnings("deprecation") // recycle() is required for Android 11/12 node pooling.
    private static final class Capture {
        private final long deadline = SystemClock.uptimeMillis() + WORK_TIMEOUT_MS;
        private final XmlBuffer xml = new XmlBuffer(MAX_BYTES, MAX_FIELD_CHARS);
        private int visited;

        void checkDeadline() throws CaptureFailure {
            if (SystemClock.uptimeMillis() >= deadline) throw new CaptureFailure("timeout");
        }

        byte[] run(UiAutomation automation) throws Exception {
            checkDeadline();
            AccessibilityServiceInfo serviceInfo = automation.getServiceInfo();
            if (serviceInfo == null) throw new CaptureFailure("service_unavailable");
            serviceInfo.flags |= AccessibilityServiceInfo.FLAG_INCLUDE_NOT_IMPORTANT_VIEWS
                    | AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS;
            automation.setServiceInfo(serviceInfo);
            checkDeadline();
            AccessibilityNodeInfo root = automation.getRootInActiveWindow();
            if (root == null) throw new CaptureFailure("no_active_window");
            try {
                checkDeadline();
                xml.raw("<?xml version=\"1.0\" encoding=\"UTF-8\"?><hierarchy>");
                visited = 1;
                writeNode(root, 0, 0);
                xml.raw("</hierarchy>\n");
                checkDeadline();
                return xml.toUtf8();
            } finally {
                root.recycle();
            }
        }

        private void writeNode(AccessibilityNodeInfo node, int index, int depth) throws Exception {
            checkDeadline();
            if (depth > MAX_DEPTH) throw new CaptureFailure("xml_depth_limit");
            boolean password = node.isPassword();
            Rect bounds = new Rect();
            node.getBoundsInScreen(bounds);
            xml.raw("<node")
                    .attribute("index", Integer.toString(index))
                    .attribute("text", password ? "" : node.getText(), MAX_TEXT_CHARS)
                    .attribute("resource-id", node.getViewIdResourceName())
                    .attribute("class", node.getClassName())
                    .attribute("package", node.getPackageName())
                    .attribute("content-desc", password ? "" : node.getContentDescription(), MAX_TEXT_CHARS)
                    .attribute("checkable", Boolean.toString(node.isCheckable()))
                    .attribute("checked", Boolean.toString(node.isChecked()))
                    .attribute("clickable", Boolean.toString(node.isClickable()))
                    .attribute("enabled", Boolean.toString(node.isEnabled()))
                    .attribute("focusable", Boolean.toString(node.isFocusable()))
                    .attribute("focused", Boolean.toString(node.isFocused()))
                    .attribute("scrollable", Boolean.toString(node.isScrollable()))
                    .attribute("long-clickable", Boolean.toString(node.isLongClickable()))
                    .attribute("password", Boolean.toString(password))
                    .attribute("selected", Boolean.toString(node.isSelected()))
                    .attribute("bounds", bounds.toShortString())
                    .attribute("visible-to-user", Boolean.toString(node.isVisibleToUser()))
                    .attribute("editable", Boolean.toString(node.isEditable()))
                    .raw(">");
            int children = node.getChildCount();
            for (int childIndex = 0; childIndex < children; childIndex++) {
                checkDeadline();
                // Count all child fetches, including null/invisible ones, to bound traversal work.
                if (++visited > MAX_NODES) throw new CaptureFailure("xml_node_limit");
                AccessibilityNodeInfo child = node.getChild(childIndex);
                if (child == null) continue;
                try {
                    checkDeadline();
                    if (child.isVisibleToUser()) writeNode(child, childIndex, depth + 1);
                } finally {
                    child.recycle();
                }
            }
            xml.raw("</node>");
        }
    }

    public static void main(String[] args) {
        int uid = Process.myUid();
        if (args.length != 0 || (uid != Process.SHELL_UID && uid != Process.ROOT_UID)) {
            System.err.println("DSH_UI_XML_ERROR: shell_or_root_uid_required");
            System.exit(EXIT_CAPTURE);
            return;
        }
        final Session session = new Session();
        Thread shutdown = new Thread(new Runnable() {
            @Override public void run() { session.close(); }
        }, "dsh-ui-xml-shutdown");
        Runtime.getRuntime().addShutdownHook(shutdown);
        Thread watchdog = new Thread(new Runnable() {
            @Override public void run() {
                try {
                    Thread.sleep(HARD_TIMEOUT_MS - 500);
                    Thread cleanup = new Thread(new Runnable() {
                        @Override public void run() { session.close(); }
                    }, "dsh-ui-xml-timeout-cleanup");
                    cleanup.setDaemon(true);
                    cleanup.start();
                    Thread.sleep(500);
                    System.err.println("DSH_UI_XML_ERROR: timeout");
                    // A stuck Binder call cannot defeat the deadline. Binder owner death
                    // removes this automation registration even when disconnect is stuck.
                    Runtime.getRuntime().halt(EXIT_TIMEOUT);
                } catch (InterruptedException done) {
                    // Normal exit after capture and disconnect.
                }
            }
        }, "dsh-ui-xml-deadline");
        watchdog.setDaemon(true);
        watchdog.start();
        byte[] output = null;
        String error = null;
        int status = EXIT_CAPTURE;
        try {
            Capture capture = new Capture();
            output = capture.run(session.open());
        } catch (CaptureFailure failure) {
            error = failure.code;
            if ("timeout".equals(error)) status = EXIT_TIMEOUT;
        } catch (XmlBuffer.Limit failure) {
            error = failure.code;
        } catch (Exception failure) {
            error = "capture_failed_" + failure.getClass().getSimpleName();
        } catch (LinkageError failure) {
            error = "unsupported_framework";
        } finally {
            if (!session.close() && error == null) error = "disconnect_failed";
        }
        if (error == null && output != null) {
            try {
                System.out.write(output);
                System.out.flush();
                if (System.out.checkError()) {
                    error = "output_failed";
                } else {
                    status = 0;
                }
            } catch (Exception failure) {
                error = "output_failed";
            }
        }
        if (error != null) System.err.println("DSH_UI_XML_ERROR: " + error);
        watchdog.interrupt();
        System.exit(status);
    }
}
