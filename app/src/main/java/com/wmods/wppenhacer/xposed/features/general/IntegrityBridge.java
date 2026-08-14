package com.wmods.wppenhacer.xposed.features.general;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;

import androidx.annotation.NonNull;
import androidx.core.content.ContextCompat;

import com.wmods.wppenhacer.xposed.core.Feature;
import com.wmods.wppenhacer.xposed.core.devkit.Unobfuscator;
import com.wmods.wppenhacer.xposed.utils.Utils;

import org.luckypray.dexkit.query.enums.StringMatchType;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.lang.reflect.Proxy;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XSharedPreferences;
import de.robv.android.xposed.XposedBridge;

/**
 * IntegrityBridge - Phase 2 Play Integrity token proxy.
 *
 * Uses DexKit to find WhatsApp's obfuscated StandardIntegrityManager class
 * (located by the BIND_EXPRESS_INTEGRITY_SERVICE string it references).
 * Hooks requestIntegrityToken() to:
 *   1. Capture the manager instance when WhatsApp uses it naturally
 *   2. Let WhatsApp's real call proceed unchanged
 *   3. When BlackBox broadcasts a nonce, call requestIntegrityToken() again
 *      on the captured manager to get a valid com.whatsapp token
 *   4. Return the token to BlackBox via ACTION_RESPONSE broadcast
 */
public class IntegrityBridge extends Feature {

    public static final String ACTION_REQUEST  = "com.blackbox.integrity.REQUEST";
    public static final String ACTION_RESPONSE = "com.blackbox.integrity.RESPONSE";
    public static final String EXTRA_NONCE     = "nonce";
    public static final String EXTRA_REQUESTOR = "requestor";
    public static final String EXTRA_TOKEN     = "token";
    public static final String EXTRA_ERROR     = "error";

    // The BIND intent action that Play Core embeds in the manager class
    private static final String BIND_ACTION =
            "com.google.android.play.core.expressintegrityservice.BIND_EXPRESS_INTEGRITY_SERVICE";

    // Captured at runtime when WhatsApp makes its first integrity call
    private volatile Object  capturedManager;
    private volatile Method  capturedRequestMethod;
    private volatile Method  capturedBuilderMethod;  // request.builder()
    private volatile Method  capturedSetHashMethod;  // builder.setRequestHash(...)
    private volatile Method  capturedBuildMethod;    // builder.build()
    private volatile Method  capturedAddSuccessMethod;
    private volatile Method  capturedAddFailureMethod;
    private volatile Method  capturedTokenMethod;    // resolved lazily
    private volatile Class<?> capturedSuccessCls;
    private volatile Class<?> capturedFailureCls;

    public IntegrityBridge(@NonNull ClassLoader classLoader,
                            @NonNull XSharedPreferences preferences) {
        super(classLoader, preferences);
    }

    @Override
    public void doHook() throws Throwable {
        Context app = Utils.getApplication();
        if (app == null) {
            XposedBridge.log("[IntegrityBridge] WhatsApp context null — skipping");
            return;
        }

        // ── Step 1: Use DexKit to find requestIntegrityToken by the binding string ──
        hookIntegrityManager(app);

        // ── Step 2: Register broadcast receiver for BlackBox nonce requests ──
        BroadcastReceiver receiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context ctx, Intent intent) {
                if (!ACTION_REQUEST.equals(intent.getAction())) return;
                String nonce     = intent.getStringExtra(EXTRA_NONCE);
                String requestor = intent.getStringExtra(EXTRA_REQUESTOR);
                if (nonce == null || requestor == null) return;
                XposedBridge.log("[IntegrityBridge] Request nonce="
                        + nonce.substring(0, Math.min(16, nonce.length())) + "...");
                Context wctx = Utils.getApplication();
                if (wctx == null) wctx = ctx;
                requestTokenForBlackBox(wctx, nonce, requestor);
            }
        };
        ContextCompat.registerReceiver(app, receiver,
                new IntentFilter(ACTION_REQUEST), ContextCompat.RECEIVER_EXPORTED);
        XposedBridge.log("[IntegrityBridge] Ready — listening for BlackBox requests");
    }

    // ──────────────────────────────────────────────────────────────────────────
    // DexKit-based manager discovery and hook
    // ──────────────────────────────────────────────────────────────────────────

    private void hookIntegrityManager(Context ctx) {
        try {
            Class<?> constClass = Unobfuscator.findFirstClassUsingStrings(
                    classLoader, StringMatchType.Contains, "expressintegrityservice");
            if (constClass == null) { hookFallback(); return; }

            // Dump full class hierarchy
            Class<?> cls = constClass;
            int depth = 0;
            while (cls != null && cls != Object.class && depth < 8) {
                XposedBridge.log("[IntegrityBridge] Level " + depth + ": " + cls.getName()
                        + " methods=" + cls.getDeclaredMethods().length
                        + " fields=" + cls.getDeclaredFields().length);
                for (Method m : cls.getDeclaredMethods()) {
                    XposedBridge.log("[IntegrityBridge]  M: "
                            + (Modifier.isStatic(m.getModifiers()) ? "S" : "I")
                            + " " + m.getReturnType().getSimpleName()
                            + " " + m.getName() + "(" + m.getParameterCount() + ")");
                }
                for (java.lang.reflect.Field f : cls.getDeclaredFields()) {
                    f.setAccessible(true);
                    String val = "";
                    try { val = String.valueOf(f.get(null)); } catch (Throwable ignored) {}
                    XposedBridge.log("[IntegrityBridge]  F: " + f.getName() + "=" + val);
                }
                // Check interfaces
                for (Class<?> iface : cls.getInterfaces()) {
                    XposedBridge.log("[IntegrityBridge]  IF: " + iface.getName()
                            + " methods=" + iface.getDeclaredMethods().length);
                    Method rm = findRequestMethod(iface);
                    if (rm != null) { proceedWithHook(rm); return; }
                }
                cls = cls.getSuperclass();
                depth++;
            }

            // Also try findRequestMethod on the original class
            Method requestM = findRequestMethod(constClass);
            if (requestM != null) { proceedWithHook(requestM); return; }

            hookFallback();
        } catch (Throwable t) {
            XposedBridge.log("[IntegrityBridge] hookIntegrityManager error: " + t);
            hookFallback();
        }
    }

    private void proceedWithHook(Method requestM) throws Exception {
        XposedBridge.log("[IntegrityBridge] Hooking: " + requestM.getDeclaringClass().getName()
                + "." + requestM.getName());
        Class<?> reqClass = requestM.getParameterTypes()[0];
        captureBuilderHandles(reqClass);

        XposedBridge.hookMethod(requestM, new XC_MethodHook() {
            @Override
            protected void afterHookedMethod(MethodHookParam param) {
                try {
                    if (capturedManager == null) {
                        capturedManager       = param.thisObject;
                        capturedRequestMethod = (Method) param.method;
                        XposedBridge.log("[IntegrityBridge] Manager captured from live call");
                    }
                    Object task = param.getResult();
                    if (task != null && capturedAddSuccessMethod == null) captureTaskHandles(task);
                } catch (Throwable t) {
                    XposedBridge.log("[IntegrityBridge] afterHook error: " + t);
                }
            }
        });
        XposedBridge.log("[IntegrityBridge] Hook installed — awaiting live integrity call");
    }

    /** Fallback: hook bindService to detect when integrity service is being bound */
    private void hookFallback() {
        XposedBridge.log("[IntegrityBridge] Using fallback — will attempt resolution when broadcast arrives");
    }

    /** Find requestIntegrityToken: non-static, 1 param, returns non-primitive/non-void */
    private Method findRequestMethod(Class<?> cls) {
        // First pass: look for a method whose return type has addOnSuccessListener (standard Task)
        for (Method m : cls.getMethods()) {
            if (Modifier.isStatic(m.getModifiers())) continue;
            if (m.getParameterCount() != 1) continue;
            if (m.getDeclaringClass() == Object.class) continue;
            Class<?> returnType = m.getReturnType();
            if (returnType == void.class || returnType.isPrimitive() || returnType == String.class) continue;
            for (Method rm : returnType.getMethods()) {
                if (rm.getName().equals("addOnSuccessListener")) return m;
            }
        }
        // Second pass: accept any non-static 1-param method returning a non-primitive object
        // (handles obfuscated Task where method names are also renamed)
        Method bestCandidate = null;
        for (Method m : cls.getDeclaredMethods()) {
            if (Modifier.isStatic(m.getModifiers())) continue;
            if (m.getParameterCount() != 1) continue;
            if (m.getDeclaringClass() == Object.class) continue;
            Class<?> returnType = m.getReturnType();
            if (returnType == void.class || returnType.isPrimitive()
                    || returnType == String.class || returnType == Boolean.class) continue;
            // Prefer methods whose parameter class has a method that could be setRequestHash
            Class<?> paramType = m.getParameterTypes()[0];
            for (Method pm : paramType.getMethods()) {
                if (pm.getParameterCount() == 1 && !Modifier.isStatic(pm.getModifiers())) {
                    bestCandidate = m;
                    break;
                }
            }
        }
        if (bestCandidate != null) {
            XposedBridge.log("[IntegrityBridge] Using best-candidate method: " + bestCandidate);
        }
        return bestCandidate;
    }

    /** Cache builder() / setRequestHash() / build() from the request class */
    private void captureBuilderHandles(Class<?> reqClass) {
        try {
            for (Method m : reqClass.getMethods()) {
                if ("builder".equals(m.getName()) && m.getParameterCount() == 0) {
                    capturedBuilderMethod = m;
                    Object builder = m.invoke(null);
                    if (builder == null) continue;
                    Class<?> bCls = builder.getClass();
                    for (Method bm : bCls.getMethods()) {
                        if ("setRequestHash".equals(bm.getName()) && bm.getParameterCount() == 1)
                            capturedSetHashMethod = bm;
                        if ("build".equals(bm.getName()) && bm.getParameterCount() == 0)
                            capturedBuildMethod = bm;
                    }
                    XposedBridge.log("[IntegrityBridge] Builder handles captured");
                    return;
                }
            }
        } catch (Throwable t) {
            XposedBridge.log("[IntegrityBridge] captureBuilderHandles: " + t);
        }
    }

    /** Cache addOnSuccessListener / addOnFailureListener from the Task object */
    private void captureTaskHandles(Object task) {
        Class<?> taskCls = task.getClass();
        for (Method m : taskCls.getMethods()) {
            if ("addOnSuccessListener".equals(m.getName()) && m.getParameterCount() == 1) {
                capturedAddSuccessMethod = m;
                capturedSuccessCls       = m.getParameterTypes()[0];
            }
            if ("addOnFailureListener".equals(m.getName()) && m.getParameterCount() == 1) {
                capturedAddFailureMethod = m;
                capturedFailureCls       = m.getParameterTypes()[0];
            }
        }
        if (capturedAddSuccessMethod != null)
            XposedBridge.log("[IntegrityBridge] Task handles captured");
    }

    // ──────────────────────────────────────────────────────────────────────────
    // Token request for BlackBox
    // ──────────────────────────────────────────────────────────────────────────

    private void requestTokenForBlackBox(Context ctx, String nonce, String requestorPkg) {
        if (capturedManager == null || capturedRequestMethod == null) {
            XposedBridge.log("[IntegrityBridge] Manager not yet captured — WhatsApp hasn't made an integrity call yet");
            sendResponse(ctx, requestorPkg, null, "Manager not captured yet — try after WA has started fully");
            return;
        }
        if (capturedBuilderMethod == null || capturedSetHashMethod == null || capturedBuildMethod == null) {
            XposedBridge.log("[IntegrityBridge] Builder handles missing");
            sendResponse(ctx, requestorPkg, null, "Builder handles not captured");
            return;
        }

        try {
            // Build request with BlackBox's nonce
            Object builder = capturedBuilderMethod.invoke(null);
            if (byte[].class.equals(capturedSetHashMethod.getParameterTypes()[0])) {
                capturedSetHashMethod.invoke(builder,
                        nonce.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            } else {
                capturedSetHashMethod.invoke(builder, nonce);
            }
            Object request = capturedBuildMethod.invoke(builder);
            Object task    = capturedRequestMethod.invoke(capturedManager, request);

            // If Task handles not yet cached, cache them now
            if (capturedAddSuccessMethod == null && task != null) {
                captureTaskHandles(task);
            }
            if (capturedAddSuccessMethod == null) {
                sendResponse(ctx, requestorPkg, null, "No addOnSuccessListener on Task");
                return;
            }

            ClassLoader proxyLoader = capturedSuccessCls.getClassLoader();
            if (proxyLoader == null) proxyLoader = classLoader;
            final ClassLoader fLoader = proxyLoader;
            final Context     fCtx   = ctx;

            capturedAddSuccessMethod.invoke(task,
                Proxy.newProxyInstance(fLoader, new Class[]{capturedSuccessCls},
                    (p, m, args) -> {
                        try {
                            Object tokenObj = args[0];
                            // Lazily resolve token() method
                            Method tokenM = capturedTokenMethod;
                            if (tokenM == null && tokenObj != null) {
                                for (Method tm : tokenObj.getClass().getMethods()) {
                                    if ("token".equals(tm.getName()) && tm.getParameterCount() == 0) {
                                        capturedTokenMethod = tokenM = tm; break;
                                    }
                                }
                            }
                            String token = tokenM != null ? (String) tokenM.invoke(tokenObj) : null;
                            XposedBridge.log("[IntegrityBridge] Token obtained len="
                                    + (token != null ? token.length() : 0));
                            sendResponse(fCtx, requestorPkg, token,
                                    token == null ? "token() null" : null);
                        } catch (Throwable t) {
                            sendResponse(fCtx, requestorPkg, null, "success cb: " + t);
                        }
                        return null;
                    }));

            if (capturedAddFailureMethod != null) {
                capturedAddFailureMethod.invoke(task,
                    Proxy.newProxyInstance(fLoader, new Class[]{capturedFailureCls},
                        (p, m, args) -> {
                            String msg = args[0] != null ? args[0].toString() : "failure";
                            XposedBridge.log("[IntegrityBridge] Token request failed: " + msg);
                            sendResponse(fCtx, requestorPkg, null, msg);
                            return null;
                        }));
            }

        } catch (Throwable t) {
            XposedBridge.log("[IntegrityBridge] requestTokenForBlackBox: " + t);
            sendResponse(ctx, requestorPkg, null, t.getMessage());
        }
    }

    private void sendResponse(Context ctx, String pkg, String token, String error) {
        Intent resp = new Intent(ACTION_RESPONSE);
        resp.setPackage(pkg);
        if (token != null) resp.putExtra(EXTRA_TOKEN, token);
        if (error != null) resp.putExtra(EXTRA_ERROR, error);
        ctx.sendBroadcast(resp);
    }

    @NonNull
    @Override
    public String getPluginName() { return "Integrity Bridge"; }
}
