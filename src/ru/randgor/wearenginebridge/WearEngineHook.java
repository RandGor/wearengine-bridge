package ru.randgor.wearenginebridge;

import android.app.Application;
import android.content.Context;
import android.content.pm.PackageInfo;
import android.os.Build;
import android.os.Process;
import android.util.Log;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

/** Allows the original Frida scope/channel for every client inside Health only. */
public final class WearEngineHook implements IXposedHookLoadPackage {
    private static final String TAG = "WearEngineBridge";
    private static final String VERSION = BuildConfig.VERSION;
    private static final String CALL_ID = "ru.randgor.wearenginebridge.callId";
    private static final String DECISION = "ru.randgor.wearenginebridge.decision";
    private static final String TARGET = "com.huawei.wearengine.scope.ScopeManager";
    private static final String TARGET_SLASH = "com/huawei/wearengine/scope/ScopeManager";
    private final AtomicBoolean contextChecked = new AtomicBoolean();
    private final ClassHookRegistry classes = new ClassHookRegistry();
    private final AtomicLong calls = new AtomicLong();
    private String processName;

    @Override public void handleLoadPackage(final XC_LoadPackage.LoadPackageParam pkg) {
        if (!AccessPolicy.HEALTH.equals(pkg.packageName)) return;
        processName = pkg.processName;
        if (BuildConfig.DEBUG) log("LOAD package=" + pkg.packageName + " loader=" + pkg.classLoader);
        if (!AccessPolicy.isProcess(pkg.packageName, pkg.processName)) {
            if (BuildConfig.DEBUG) log("SKIP_PROCESS expected=" + AccessPolicy.PROCESS + " or " + AccessPolicy.PHONE_PROCESS);
            return;
        }
        try {
            installClassWatchers();
            XposedHelpers.findAndHookMethod(Application.class, "attach", Context.class, new XC_MethodHook() {
                @Override protected void afterHookedMethod(MethodHookParam param) {
                    if (param.hasThrowable()) {
                        failure("ATTACH_FAILED", param.getThrowable());
                        return;
                    }
                    if (!(param.args[0] instanceof Context)) {
                        log("ATTACH_IGNORED missing Context");
                        return;
                    }
                    Context context = (Context) param.args[0];
                    if (BuildConfig.DEBUG) log("ATTACH package=" + context.getPackageName() + " loader=" + context.getClassLoader());
                    if (!AccessPolicy.HEALTH.equals(context.getPackageName())) return;
                    if (!contextChecked.compareAndSet(false, true)) return;
                    activate(context, pkg.classLoader);
                }
            });
            if (BuildConfig.DEBUG) log("ATTACH_HOOK_READY waiting for Application.attach");
        } catch (Throwable error) {
            failure("INACTIVE bootstrap hook failed", error);
        }
    }

    private void installClassWatchers() {
        XC_MethodHook observer = new XC_MethodHook() {
            @Override protected void afterHookedMethod(MethodHookParam param) {
                // Fast name check: never stringify/log unrelated class loads.
                if (param.args.length == 0 || !(TARGET.equals(param.args[0])
                        || TARGET_SLASH.equals(param.args[0]))) return;
                if (param.hasThrowable() || !(param.getResult() instanceof Class<?>)) return;
                observe((Class<?>) param.getResult(), param.method.getDeclaringClass().getName()
                    + "." + param.method.getName());
            }
        };
        int watcherCount = 0;
        String[] loaders = {"java.lang.ClassLoader", "dalvik.system.BaseDexClassLoader",
            "dalvik.system.DelegateLastClassLoader", "dalvik.system.DexFile"};
        for (String name : loaders) {
            try {
                Class<?> owner = Class.forName(name, false, null);
                for (Method method : owner.getDeclaredMethods()) {
                    String methodName = method.getName();
                    Class<?>[] args = method.getParameterTypes();
                    if (!(methodName.equals("loadClass") || methodName.equals("findClass")
                            || methodName.equals("loadClassBinaryName"))) continue;
                    if (method.getReturnType() != Class.class || args.length == 0 || args[0] != String.class
                            || Modifier.isAbstract(method.getModifiers()) || Modifier.isNative(method.getModifiers())) continue;
                    try {
                        XposedBridge.hookMethod(method, observer);
                        watcherCount++;
                        if (BuildConfig.DEBUG) log("WATCH " + method);
                    } catch (Throwable error) {
                        failure("WATCH_FAILED " + method, error);
                    }
                }
            } catch (Throwable error) {
                failure("WATCH_UNAVAILABLE " + name, error);
            }
        }
        if (BuildConfig.DEBUG) log("WATCHERS_READY count=" + watcherCount);
        if (watcherCount == 0) throw new IllegalStateException("No class loading observers installed");
    }

    @SuppressWarnings("deprecation")
    private void activate(Context context, ClassLoader packageLoader) {
        try {
            PackageInfo host = context.getPackageManager().getPackageInfo(AccessPolicy.HEALTH, 0);
            long version = Build.VERSION.SDK_INT >= 28 ? host.getLongVersionCode() : host.versionCode;
            if (BuildConfig.DEBUG) log("HOST versionName=" + host.versionName + " versionCode=" + version
                + " androidApi=" + Build.VERSION.SDK_INT);
            if (!AccessPolicy.isTestedHost(version)) {
                log("WARN untested Health versionName=" + host.versionName + " versionCode=" + version
                    + "; tested=" + AccessPolicy.TESTED_HEALTH_VERSION
                    + "; attempting hook with method contract checks");
            }
            // These classes may have been seen before the Health Context was available.
            // No lock is held while reflecting or installing an ART hook.
            for (Class<?> type : classes.activate()) observe(type, "queued-before-attach");
            seed(context.getClassLoader(), "context-loader");
            if (packageLoader != context.getClassLoader()) seed(packageLoader, "package-loader");
            if (BuildConfig.DEBUG) log("ACTIVE class watchers remain enabled; known=" + classes.knownCount()
                + " hooked=" + classes.installedCount());
        } catch (Throwable error) {
            failure("INACTIVE host initialization failed", error);
        }
    }

    private void seed(ClassLoader loader, String source) {
        try {
            // Initial lookup covers a class already loaded when observers were installed.
            observe(Class.forName(TARGET, false, loader), source);
        } catch (ClassNotFoundException missing) {
            if (BuildConfig.DEBUG) log("WAIT_CLASS source=" + source + "; future loader events will be observed");
        } catch (Throwable error) {
            failure("SEED_FAILED source=" + source, error);
        }
    }

    private void observe(Class<?> type, String source) {
        if (!TARGET.equals(type.getName())) return;
        ClassHookRegistry.Ticket ticket = classes.observe(type);
        if (ticket == null) return;
        installClass(ticket, source);
    }

    private void installClass(final ClassHookRegistry.Ticket ticket, String source) {
        try {
            Class<?> manager = ticket.type;
            Method gate = manager.getDeclaredMethod("checkScopeAvailability",
                String.class, int.class, int.class, String.class);
            if (BuildConfig.DEBUG) log("CLASS_FOUND classId=" + ticket.id + " source=" + source
                + " loaderId=" + System.identityHashCode(manager.getClassLoader())
                + " loader=" + manager.getClassLoader());
            if (gate.getReturnType() != boolean.class || Modifier.isStatic(gate.getModifiers())) {
                log("CLASS_INACTIVE classId=" + ticket.id + " unexpected scope method contract");
                return;
            }
            XposedBridge.hookMethod(gate, new XC_MethodHook() {
                @Override protected void beforeHookedMethod(MethodHookParam param) {
                    boolean allowed = AccessPolicy.isRequest(param.args);
                    if (BuildConfig.DEBUG) {
                        long id = calls.incrementAndGet();
                        String decision = allowed ? "ALLOW" : "PASS_THROUGH";
                        param.setObjectExtra(CALL_ID, id);
                        param.setObjectExtra(DECISION, decision);
                        log("CALL #" + id + " classId=" + ticket.id + " scope=" + value(param.args[0])
                            + " clientPid=" + param.args[1] + " clientUid=" + param.args[2]
                            + " channel=" + value(param.args[3]) + " decision=" + decision);
                    }
                    if (allowed) param.setResult(Boolean.TRUE);
                }
                @Override protected void afterHookedMethod(MethodHookParam param) {
                    if (!BuildConfig.DEBUG) return;
                    Object id = param.getObjectExtra(CALL_ID);
                    Object decision = param.getObjectExtra(DECISION);
                    // This is the result observed by this callback, not a second call to Health.
                    if (param.hasThrowable()) {
                        failure("RETURN #" + id + " classId=" + ticket.id + " decision=" + decision + " threw", param.getThrowable());
                    } else {
                        log("RETURN #" + id + " classId=" + ticket.id + " decision=" + decision + " result=" + param.getResult());
                    }
                }
            });
            classes.installed(ticket);
            log("READY classId=" + ticket.id + " hooked=" + classes.installedCount()
                + " checkScopeAvailability(String,int,int,String); watching additional classes");
        } catch (Throwable error) {
            failure("CLASS_HOOK_FAILED classId=" + ticket.id, error);
        }
    }

    private static String value(Object item) {
        if (item == null) return "<null>";
        String text = String.valueOf(item).replace("\\", "\\\\")
            .replace("\r", "\\r").replace("\n", "\\n").replace("\"", "\\\"");
        if (text.length() > 512) text = text.substring(0, 512) + "...[truncated]";
        return "\"" + text + "\"";
    }

    private void failure(String event, Throwable error) {
        log(event + " exception=" + error.getClass().getName());
        if (BuildConfig.DEBUG) log(Log.getStackTraceString(error));
    }

    private void log(String message) {
        String line = "v=" + VERSION + " process=" + processName + " pid=" + Process.myPid()
            + " tid=" + Process.myTid() + " " + message;
        // Logging failures must not change the scope decision or Health's result.
        try { XposedBridge.log(TAG + ": " + line); } catch (Throwable ignored) {}
        try { Log.i(TAG, line); } catch (Throwable ignored) {}
    }
}
