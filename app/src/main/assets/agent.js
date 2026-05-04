// VON Tracer Agent
// Di-push ke target app via ApkInjector.pushAgentScript()
// Load otomatis oleh frida-gadget saat app launch

'use strict';

var TARGET_HOST = '127.0.0.1';
var TARGET_PORT = 27043;

// ──────────────────────────────────────────────
// SOCKET SEND
// ──────────────────────────────────────────────

function sendTrace(data) {
    try {
        var JSONstr = JSON.stringify(data) + '\n';
        var socket = Java.use('java.net.Socket').$new(TARGET_HOST, TARGET_PORT);
        var out = socket.getOutputStream();
        out.write(JSONstr.getBytes('UTF-8'));
        out.flush();
        out.close();
        socket.close();
    } catch (e) {
        // Silently fail — tool mungkin belum ready
    }
}

// ──────────────────────────────────────────────
// CALL STACK BUILDER
// ──────────────────────────────────────────────

function getCallStack() {
    var stack = [];
    try {
        var Exception = Java.use('java.lang.Exception');
        var ex = Exception.$new();
        var elements = ex.getStackTrace();
        var limit = Math.min(elements.length, 12);
        for (var i = 0; i < limit; i++) {
            var el = elements[i];
            var cls = el.getClassName();
            // Skip framework noise
            if (cls.startsWith('android.') ||
                cls.startsWith('androidx.') ||
                cls.startsWith('java.') ||
                cls.startsWith('kotlin.') ||
                cls.startsWith('com.android.') ||
                cls.startsWith('dalvik.')) {
                continue;
            }
            stack.push({
                'class'  : cls,
                'method' : el.getMethodName(),
                'line'   : el.getLineNumber()
            });
        }
        ex.$dispose();
    } catch (e) {}
    return stack;
}

// ──────────────────────────────────────────────
// OFFSET RESOLVER
// ──────────────────────────────────────────────

function getMethodOffset(className, methodName) {
    try {
        // Cari base address libgadget / app .so
        var Process_modules = Process.enumerateModules();
        var appModule = null;

        for (var i = 0; i < Process_modules.length; i++) {
            var mod = Process_modules[i];
            // Cari module utama app (bukan framework)
            if (mod.name.indexOf('libgadget') < 0 &&
                mod.name.indexOf('libc') < 0 &&
                mod.name.indexOf('libm') < 0 &&
                mod.name.endsWith('.so')) {
                appModule = mod;
                break;
            }
        }

        if (appModule) {
            // Untuk Java method, offset = relative address di classes.dex
            // Kita resolve via ArtMethod pointer
            var clazz = Java.use(className);
            var method = clazz.class.getDeclaredMethod(methodName);
            if (method) {
                return '0x' + method.hashCode().toString(16).toUpperCase();
            }
        }
    } catch (e) {}
    return '0x?';
}

// ──────────────────────────────────────────────
// MAIN HOOKS
// ──────────────────────────────────────────────

Java.perform(function () {

    // ── 1. Hook View.performClick ──────────────
    try {
        var View = Java.use('android.view.View');

        View.performClick.implementation = function () {
            var result = this.performClick();

            var viewClass = this.getClass().getName();
            var viewId    = this.getId();
            var text      = '';

            // Coba ambil text kalau TextView/Button
            try {
                var tv = Java.cast(this, Java.use('android.widget.TextView'));
                text = tv.getText().toString();
            } catch (e) {}

            sendTrace({
                type      : 'CLICK',
                viewClass : viewClass,
                viewId    : viewId,
                text      : text,
                offset    : getMethodOffset(viewClass, 'onClick'),
                stack     : getCallStack()
            });

            return result;
        };
    } catch (e) {}

    // ── 2. Hook MotionEvent (raw touch) ────────
    try {
        var DecorView = Java.use('com.android.internal.policy.DecorView');

        DecorView.dispatchTouchEvent.implementation = function (ev) {
            // Hanya ACTION_DOWN (0) supaya tidak flood
            if (ev !== null && ev.getAction() === 0) {
                sendTrace({
                    type   : 'TOUCH',
                    x      : ev.getX(),
                    y      : ev.getY(),
                    action : ev.getAction(),
                    offset : '0x?',
                    stack  : []
                });
            }
            return this.dispatchTouchEvent(ev);
        };
    } catch (e) {}

    // ── 3. Hook semua method di package target ─
    hookTargetPackage();

    // ── 4. Hook Native calls (opsional) ────────
    hookNativeCalls();

});

// ──────────────────────────────────────────────
// HOOK TARGET PACKAGE
// ──────────────────────────────────────────────

function hookTargetPackage() {
    try {
        // Dapatkan package name target dari ActivityThread
        var ActivityThread = Java.use('android.app.ActivityThread');
        var app = ActivityThread.currentApplication();
        var packageName = app.getPackageName();

        Java.enumerateLoadedClasses({
            onMatch: function (className) {
                // Hanya hook class milik target app
                if (!className.startsWith(packageName)) return;

                try {
                    var clazz = Java.use(className);
                    var methods = clazz.class.getDeclaredMethods();

                    methods.forEach(function (method) {
                        var methodName = method.getName();

                        // Skip synthetic / lambda
                        if (methodName.contains('$') ||
                            methodName.contains('access$') ||
                            methodName.equals('<init>') ||
                            methodName.equals('<clinit>')) {
                            return;
                        }

                        try {
                            hookSingleMethod(clazz, className, methodName);
                        } catch (e) {}
                    });

                } catch (e) {}
            },
            onComplete: function () {}
        });

    } catch (e) {}
}

function hookSingleMethod(clazz, className, methodName) {
    var overloads = clazz[methodName].overloads;
    if (!overloads) return;

    overloads.forEach(function (overload) {
        overload.implementation = function () {
            var result = overload.apply(this, arguments);

            sendTrace({
                type       : 'METHOD',
                class      : className,
                method     : methodName,
                offset     : getMethodOffset(className, methodName),
                returnType : typeof result,
                stack      : getCallStack()
            });

            return result;
        };
    });
}

// ──────────────────────────────────────────────
// HOOK NATIVE CALLS
// ──────────────────────────────────────────────

function hookNativeCalls() {
    try {
        // Hook dlopen — deteksi saat native lib di-load
        var dlopen = Module.findExportByName(null, 'dlopen');
        if (dlopen) {
            Interceptor.attach(dlopen, {
                onEnter: function (args) {
                    this.libPath = args[0].readUtf8String();
                },
                onLeave: function (retval) {
                    if (this.libPath && this.libPath.length > 0) {
                        sendTrace({
                            type   : 'NATIVE',
                            lib    : this.libPath,
                            symbol : 'dlopen',
                            offset : dlopen.toString(),
                            stack  : []
                        });
                    }
                }
            });
        }

        // Hook JNI_OnLoad — titik entry native lib
        Process.enumerateModules().forEach(function (mod) {
            var jniOnLoad = mod.findExportByName('JNI_OnLoad');
            if (jniOnLoad) {
                Interceptor.attach(jniOnLoad, {
                    onEnter: function () {
                        sendTrace({
                            type   : 'NATIVE',
                            lib    : mod.name,
                            symbol : 'JNI_OnLoad',
                            offset : jniOnLoad.toString(),
                            stack  : []
                        });
                    }
                });
            }
        });

    } catch (e) {}
}