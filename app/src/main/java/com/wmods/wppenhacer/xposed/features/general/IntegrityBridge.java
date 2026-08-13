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
import java.lang.reflect.Proxy;

import de.robv.android.xposed.XSharedPreferences;
import de.robv.android.xposed.XposedBridge;

/**
 * IntegrityBridge — Phase 2 Play Integrity token proxy.
 *
 * BlackBox's virtual WhatsApp cannot generate a com.whatsapp Play Integrity
 * token. This feature runs inside the REAL com.whatsapp process:
 *   1. BlackBox broadcasts ACTION_REQUEST with nonce
 *   2. This receiver requests a real com.whatsapp token from GMS via WhatsApp's
 *      own (obfuscated) StandardIntegrityManager, found via DexKit scan
 *   3. Real token is broadcast back to BlackBox via ACTION_RESPONSE
 */
public class IntegrityBridge extends Feature {

    public static final String ACTION_REQUEST  = "com.blackbox.integrity.REQUEST";
    public static final String ACTION_RESPONSE = "com.blackbox.integrity.RESPONSE";
    public static final String EXTRA_NONCE     = "nonce";
    public static final String EXTRA_REQUESTOR = "requestor";
    public static final String EXTRA_TOKEN     = "token";
    public static final String EXTRA_ERROR     = "error";

    // Cached handles — discovered via DexKit on first use
    private volatile Object  sManager;
    private volatile Method  sBuilderMethod;
    private volatile Method  sSetHashMethod;
    private volatile Method  sBuildMethod;
    private volatile Method  sRequestMethod;
    private volatile Method  sAddSuccessMethod;
    private volatile Method  sAddFailureMethod;
    private volatile Method  sTokenMethod;     // lazily resolved from first response
    private volatile Class<?> sSuccessCls;
    private volatile Class<?> sFailureCls;

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

        // Pre-warm — find and cache the Play Integrity API handles
        try {
            resolveIntegrityApi(app);
            XposedBridge.log("[IntegrityBridge] Play Integrity API resolved");
        } catch (Throwable t) {
            XposedBridge.log("[IntegrityBridge] Pre-warm failed (will retry): " + t);
        }

        BroadcastReceiver receiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context ctx, Intent intent) {
                if (!ACTION_REQUEST.equals(intent.getAction())) return;
                String nonce     = intent.getStringExtra(EXTRA_NONCE);
                String requestor = intent.getStringExtra(EXTRA_REQUESTOR);
                if (nonce == null || requestor == null) return;
                XposedBridge.log("[IntegrityBridge] Request nonce=" +
                        nonce.substring(0, Math.min(16, nonce.length())) + "...");
                Context wctx = Utils.getApplication();
                if (wctx == null) wctx = ctx;
                requestToken(wctx, nonce, requestor);
            }
        };
        ContextCompat.registerReceiver(app, receiver,
                new IntentFilter(ACTION_REQUEST), ContextCompat.RECEIVER_EXPORTED);
        XposedBridge.log("[IntegrityBridge] Ready — listening for BlackBox requests");
    }

    // ──────────────────────────────────────────────────────────────────────────
    // API resolution via DexKit — finds obfuscated Play Core classes by their
    // characteristic strings and method signatures
    // ──────────────────────────────────────────────────────────────────────────

    private void resolveIntegrityApi(Context ctx) throws Exception {
        if (sRequestMethod != null) return;

        // Reset all fields for a clean retry
        sManager = null; sBuilderMethod = null; sSetHashMethod = null;
        sBuildMethod = null; sRequestMethod = null;
        sAddSuccessMethod = null; sAddFailureMethod = null;
        sTokenMethod = null; sSuccessCls = null; sFailureCls = null;

        // Step 1: Find the class containing "expressintegrityservice" string —
        // this is the StandardIntegrityManager implementation (obfuscated)
        // We look for the class that actually performs the service binding.
        // The factory or manager class will contain this service name.
        Class<?> managerOrFactoryCls = findIntegrityClass();

        if (managerOrFactoryCls == null) {
            throw new Exception("Cannot find integrity class via DexKit");
        }
        XposedBridge.log("[IntegrityBridge] Found candidate class: " + managerOrFactoryCls.getName());

        // Step 2: Try all static methods on this class that take a Context parameter.
        // Don't filter by return type name — the method is obfuscated.
        // Accept the first one that returns a non-null object with at least one 1-param method.
        Object manager = null;

        java.util.List<Class<?>> candidateClasses = new java.util.ArrayList<>();
        candidateClasses.add(managerOrFactoryCls);

        // Also add all classes found via broad scan
        try {
            Class<?>[] allCandidates = Unobfuscator.findAllClassUsingStrings(classLoader,
                    StringMatchType.Contains, "expressintegrityservice");
            if (allCandidates != null) {
                for (Class<?> c : allCandidates) candidateClasses.add(c);
            }
        } catch (Throwable ignored) {}

        outer:
        for (Class<?> cls : candidateClasses) {
            for (Method m : cls.getDeclaredMethods()) {
                if (!java.lang.reflect.Modifier.isStatic(m.getModifiers())) continue;
                if (m.getParameterCount() != 1) continue;
                if (!Context.class.isAssignableFrom(m.getParameterTypes()[0])) continue;
                if (m.getReturnType().equals(void.class)) continue;
                try {
                    m.setAccessible(true);
                    Object result = m.invoke(null, ctx);
                    if (result == null) continue;
                    // Check this result has at least one method taking 1 parameter (the token request method)
                    boolean has1ParamMethod = false;
                    for (Method rm : result.getClass().getMethods()) {
                        if (rm.getParameterCount() == 1 && !rm.getReturnType().equals(void.class)) {
                            has1ParamMethod = true; break;
                        }
                    }
                    if (!has1ParamMethod) continue;
                    manager = result;
                    XposedBridge.log("[IntegrityBridge] Factory method found: " + cls.getName() + "." + m.getName()
                            + " → " + result.getClass().getName());
                    break outer;
                } catch (Throwable t) {
                    XposedBridge.log("[IntegrityBridge] Factory method " + cls.getName() + "." + m.getName() + " failed: " + t);
                }
            }
        }

        if (manager == null) {
            throw new Exception("Cannot instantiate integrity manager");
        }

        cacheManagerMethods(manager);
    }

    private Class<?> findIntegrityClass() {
        // Primary: find by ExpressIntegrityService string
        try {
            Class<?> cls = Unobfuscator.findFirstClassUsingStrings(classLoader,
                    StringMatchType.Contains, "expressintegrityservice");
            if (cls != null) return cls;
        } catch (Throwable t) {
            XposedBridge.log("[IntegrityBridge] DexKit scan 1 failed: " + t);
        }

        // Secondary: find by integrity service action string
        try {
            Class<?> cls = Unobfuscator.findFirstClassUsingStrings(classLoader,
                    StringMatchType.Contains, "BIND_EXPRESS_INTEGRITY_SERVICE");
            if (cls != null) return cls;
        } catch (Throwable t) {
            XposedBridge.log("[IntegrityBridge] DexKit scan 2 failed: " + t);
        }

        // Tertiary: find by standard integrity manager canonical name fragment
        try {
            Class<?> cls = Unobfuscator.findFirstClassUsingStrings(classLoader,
                    StringMatchType.Contains, "StandardIntegrityManager");
            if (cls != null) return cls;
        } catch (Throwable t) {
            XposedBridge.log("[IntegrityBridge] DexKit scan 3 failed: " + t);
        }
        return null;
    }

    private boolean hasRequestIntegrityToken(Class<?> cls) {
        for (Method m : cls.getMethods()) {
            if (m.getName().contains("requestIntegrityToken")
                    || m.getName().contains("IntegrityToken")
                    || (m.getParameterCount() == 1 && m.getName().toLowerCase().contains("token"))) {
                return true;
            }
        }
        return false;
    }

    private void cacheManagerMethods(Object manager) throws Exception {
        Class<?> mgrClass = manager.getClass();
        XposedBridge.log("[IntegrityBridge] Scanning manager class: " + mgrClass.getName());

        // Find requestIntegrityToken: the method that takes 1 param and returns a Task-like object.
        // In obfuscated code, we can't rely on the name — scan by parameter/return characteristics.
        // The request class should have a builder() static method.
        Method requestM = null;
        for (Method m : mgrClass.getMethods()) {
            if (m.getParameterCount() != 1) continue;
            if (m.getReturnType().equals(void.class)) continue;
            // The parameter class should have a no-arg static builder/create method
            Class<?> paramCls = m.getParameterTypes()[0];
            boolean hasBuilder = false;
            for (Method pm : paramCls.getMethods()) {
                if (pm.getParameterCount() == 0 && java.lang.reflect.Modifier.isStatic(pm.getModifiers())
                        && !pm.getReturnType().equals(void.class)) {
                    hasBuilder = true; break;
                }
            }
            if (hasBuilder) {
                requestM = m;
                XposedBridge.log("[IntegrityBridge] Request method candidate: " + m.getName()
                        + " param=" + paramCls.getName());
                break;
            }
        }
        if (requestM == null) throw new Exception("requestIntegrityToken not found on " + mgrClass.getName());

        Class<?> reqClass = requestM.getParameterTypes()[0];
        XposedBridge.log("[IntegrityBridge] Request class: " + reqClass.getName());

        // Find builder factory method (static, no args, returns builder)
        Method builderM = null;
        for (Method m : reqClass.getMethods()) {
            if (m.getParameterCount() == 0 && java.lang.reflect.Modifier.isStatic(m.getModifiers())
                    && !m.getReturnType().equals(void.class) && !m.getReturnType().equals(reqClass)) {
                builderM = m; break;
            }
        }
        if (builderM == null) throw new Exception("builder() not found on " + reqClass.getName());

        Object builder    = builderM.invoke(null);
        Class<?> builderCls = builder.getClass();
        XposedBridge.log("[IntegrityBridge] Builder class: " + builderCls.getName());

        // setRequestHash: 1-param method on builder that returns builder (fluent)
        Method setHashM = null;
        for (Method m : builderCls.getMethods()) {
            if (m.getParameterCount() == 1 && !m.getReturnType().equals(void.class)) {
                setHashM = m; break;
            }
        }
        if (setHashM == null) throw new Exception("setRequestHash not found on " + builderCls.getName());

        // build(): no-arg, returns reqClass
        Method buildM = null;
        for (Method m : builderCls.getMethods()) {
            if (m.getParameterCount() == 0 && m.getReturnType().equals(reqClass)) { buildM = m; break; }
        }
        if (buildM == null) throw new Exception("build() not found on " + builderCls.getName());

        // Make a dummy request to discover the Task class
        Object dummyReq = buildM.invoke(builderM.invoke(null));
        Object task     = requestM.invoke(manager, dummyReq);
        if (task == null) throw new Exception("requestIntegrityToken returned null task");
        XposedBridge.log("[IntegrityBridge] Task class: " + task.getClass().getName());

        Method addSuccessM = null, addFailureM = null;
        for (Method m : task.getClass().getMethods()) {
            if (m.getParameterCount() == 1) {
                String name = m.getName().toLowerCase();
                if (name.contains("success") || name.equals("addonsuccesslistener")) addSuccessM = m;
                if (name.contains("fail")    || name.equals("addonfailurelistener"))  addFailureM = m;
            }
        }
        if (addSuccessM == null) {
            // Last resort: take any 1-param addOn* method
            for (Method m : task.getClass().getMethods()) {
                if (m.getParameterCount() == 1 && m.getName().startsWith("addOn")) {
                    addSuccessM = m; break;
                }
            }
        }
        if (addSuccessM == null) throw new Exception("addOnSuccessListener not found on " + task.getClass().getName());

        sManager          = manager;
        sBuilderMethod    = builderM;
        sSetHashMethod    = setHashM;
        sBuildMethod      = buildM;
        sRequestMethod    = requestM;
        sAddSuccessMethod = addSuccessM;
        sAddFailureMethod = addFailureM;
        sSuccessCls       = addSuccessM.getParameterTypes()[0];
        sFailureCls       = addFailureM != null ? addFailureM.getParameterTypes()[0] : sSuccessCls;
        XposedBridge.log("[IntegrityBridge] Cached: reqM=" + requestM.getName()
                + " builderM=" + builderM.getName() + " setHashM=" + setHashM.getName()
                + " successM=" + addSuccessM.getName());
    }

    // ──────────────────────────────────────────────────────────────────────────
    // Token request
    // ──────────────────────────────────────────────────────────────────────────

    private void requestToken(Context ctx, String nonce, String requestorPkg) {
        try {
            resolveIntegrityApi(ctx);
            if (sRequestMethod == null) throw new IllegalStateException("API not resolved");

            Object builder = sBuilderMethod.invoke(null);

            // setRequestHash: String or byte[] depending on SDK version
            Class<?> hashType = sSetHashMethod.getParameterTypes()[0];
            if (byte[].class.equals(hashType)) {
                sSetHashMethod.invoke(builder, nonce.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            } else {
                sSetHashMethod.invoke(builder, nonce);
            }

            Object request = sBuildMethod.invoke(builder);
            Object task    = sRequestMethod.invoke(sManager, request);

            ClassLoader proxyLoader = sSuccessCls.getClassLoader();
            if (proxyLoader == null) proxyLoader = classLoader;
            final ClassLoader fLoader = proxyLoader;
            final Context     fCtx   = ctx;

            sAddSuccessMethod.invoke(task,
                Proxy.newProxyInstance(fLoader, new Class[]{sSuccessCls},
                    (p, m, args) -> {
                        try {
                            Object tokenObj = args[0];
                            Method tokenM = sTokenMethod;
                            if (tokenM == null && tokenObj != null) {
                                for (Method tm : tokenObj.getClass().getMethods()) {
                                    if (tm.getParameterCount() == 0 &&
                                            (tm.getName().equals("token") || tm.getName().contains("Token"))
                                            && String.class.equals(tm.getReturnType())) {
                                        sTokenMethod = tokenM = tm; break;
                                    }
                                }
                            }
                            String token = tokenM != null ? (String) tokenM.invoke(tokenObj) : null;
                            XposedBridge.log("[IntegrityBridge] Token obtained len=" +
                                    (token != null ? token.length() : 0));
                            sendResponse(fCtx, requestorPkg, token,
                                    token == null ? "token() returned null" : null);
                        } catch (Throwable t) {
                            sendResponse(fCtx, requestorPkg, null, "success cb: " + t);
                        }
                        return null;
                    }));

            if (sAddFailureMethod != null) {
                sAddFailureMethod.invoke(task,
                    Proxy.newProxyInstance(fLoader, new Class[]{sFailureCls},
                        (p, m, args) -> {
                            String msg = args[0] != null ? args[0].toString() : "failure";
                            XposedBridge.log("[IntegrityBridge] Token failed: " + msg);
                            sendResponse(fCtx, requestorPkg, null, msg);
                            return null;
                        }));
            }
        } catch (Throwable t) {
            XposedBridge.log("[IntegrityBridge] requestToken: " + t);
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
