package com.wmods.wppenhacer.xposed.features.general;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;

import androidx.annotation.NonNull;
import androidx.core.content.ContextCompat;

import com.wmods.wppenhacer.xposed.core.Feature;
import com.wmods.wppenhacer.xposed.utils.Utils;

import java.lang.reflect.Method;

import de.robv.android.xposed.XSharedPreferences;
import de.robv.android.xposed.XposedBridge;

/**
 * IntegrityBridge - Phase 2 BlackBox/WaEnhancer Play Integrity token proxy.
 *
 * BlackBox's virtualised WhatsApp cannot generate a com.whatsapp Play Integrity
 * token (it runs as top.niunaijun.blackbox). This feature runs inside the REAL
 * com.whatsapp process and acts as a token broker:
 *
 *   1. BlackBox broadcasts ACTION_REQUEST with the nonce
 *   2. This receiver calls StandardIntegrityManager.requestIntegrityToken()
 *      using the real WhatsApp context → Google signs a token for com.whatsapp
 *   3. Token is broadcast back via ACTION_RESPONSE to BlackBox
 *   4. BlackBox's virtual WhatsApp uses the real token in its HTTP request
 *   5. WhatsApp's server validates "com.whatsapp passes integrity" → accepts
 */
public class IntegrityBridge extends Feature {

    public static final String ACTION_REQUEST  = "com.blackbox.integrity.REQUEST";
    public static final String ACTION_RESPONSE = "com.blackbox.integrity.RESPONSE";
    public static final String EXTRA_NONCE     = "nonce";
    public static final String EXTRA_REQUESTOR = "requestor";
    public static final String EXTRA_TOKEN     = "token";
    public static final String EXTRA_ERROR     = "error";

    // Cached reflection handles
    private volatile Object sManager;
    private volatile Method sRequestMethod;
    private volatile Method sBuilderMethod;
    private volatile Method sSetHashMethod;
    private volatile Method sBuildMethod;
    private volatile Method sAddSuccessMethod;
    private volatile Method sAddFailureMethod;
    private volatile Method sTokenMethod;
    private volatile Class<?> sRequestClass;

    public IntegrityBridge(@NonNull ClassLoader classLoader,
                            @NonNull XSharedPreferences preferences) {
        super(classLoader, preferences);
    }

    @Override
    public void doHook() throws Throwable {
        Context app = Utils.getApplication();
        if (app == null) {
            XposedBridge.log("[IntegrityBridge] WhatsApp context null, skipping");
            return;
        }

        // Pre-warm — resolve reflection handles early so first request is fast
        try {
            resolveIntegrityApi(app);
            XposedBridge.log("[IntegrityBridge] Play Integrity API resolved");
        } catch (Throwable t) {
            XposedBridge.log("[IntegrityBridge] Pre-warm failed (will retry on request): " + t);
        }

        BroadcastReceiver receiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context ctx, Intent intent) {
                if (!ACTION_REQUEST.equals(intent.getAction())) return;
                String nonce     = intent.getStringExtra(EXTRA_NONCE);
                String requestor = intent.getStringExtra(EXTRA_REQUESTOR);
                if (nonce == null || requestor == null) return;

                XposedBridge.log("[IntegrityBridge] Request from " + requestor
                        + " nonce=" + nonce.substring(0, Math.min(16, nonce.length())) + "…");

                Context whatsappCtx = Utils.getApplication();
                if (whatsappCtx == null) whatsappCtx = ctx;
                requestToken(whatsappCtx, nonce, requestor);
            }
        };

        ContextCompat.registerReceiver(app, receiver,
                new IntentFilter(ACTION_REQUEST), ContextCompat.RECEIVER_EXPORTED);
        XposedBridge.log("[IntegrityBridge] Ready — listening for BlackBox requests");
    }

    private void resolveIntegrityApi(Context ctx) throws Exception {
        if (sManager != null) return;

        // Newer Play Integrity SDK uses GMS tasks, not Play Core tasks.
        // Try multiple class name variants across multiple classloaders.
        ClassLoader[] loaders = buildLoaderList(ctx);

        // Class name sets to try (Play Core vs GMS tasks)
        String[][] taskVariants = {
            {"com.google.android.play.core.tasks.Task",
             "com.google.android.play.core.tasks.OnSuccessListener",
             "com.google.android.play.core.tasks.OnFailureListener"},
            {"com.google.android.gms.tasks.Task",
             "com.google.android.gms.tasks.OnSuccessListener",
             "com.google.android.gms.tasks.OnFailureListener"},
        };
        String[] factoryVariants = {
            "com.google.android.play.core.integrity.IntegrityManagerFactory",
        };
        String[] reqVariants = {
            "com.google.android.play.core.integrity.StandardIntegrityManager$StandardIntegrityTokenRequest",
        };
        String[] tokenVariants = {
            "com.google.android.play.core.integrity.StandardIntegrityManager$StandardIntegrityToken",
        };

        Throwable lastError = null;
        outer:
        for (ClassLoader loader : loaders) {
            for (String[] taskSet : taskVariants) {
                try {
                    Class<?> taskCls    = Class.forName(taskSet[0], true, loader);
                    Class<?> successCls = Class.forName(taskSet[1], true, loader);
                    Class<?> failureCls = Class.forName(taskSet[2], true, loader);

                    for (String factoryName : factoryVariants) {
                        try {
                            Class<?> factory = Class.forName(factoryName, true, loader);
                            Object manager = factory.getMethod("createStandard", Context.class).invoke(null, ctx);

                            for (String reqName : reqVariants) {
                                try {
                                    Class<?> reqClass = Class.forName(reqName, true, loader);
                                    Method builderM = reqClass.getMethod("builder");
                                    Object builder  = builderM.invoke(null);
                                    Class<?> builderClass = builder.getClass();

                                    for (String tokenName : tokenVariants) {
                                        try {
                                            Class<?> tokenClass = Class.forName(tokenName, true, loader);

                                            // All classes found — cache and done
                                            sManager          = manager;
                                            sRequestClass     = reqClass;
                                            sBuilderMethod    = builderM;
                                            sSetHashMethod    = builderClass.getMethod("setRequestHash", String.class);
                                            sBuildMethod      = builderClass.getMethod("build");
                                            sRequestMethod    = manager.getClass().getMethod("requestIntegrityToken", reqClass);
                                            sAddSuccessMethod = taskCls.getMethod("addOnSuccessListener", successCls);
                                            sAddFailureMethod = taskCls.getMethod("addOnFailureListener", failureCls);
                                            sTokenMethod      = tokenClass.getMethod("token");
                                            XposedBridge.log("[IntegrityBridge] Resolved via loader=" +
                                                    loader.getClass().getSimpleName() + " task=" + taskSet[0]);
                                            break outer;
                                        } catch (Throwable t) { lastError = t; }
                                    }
                                } catch (Throwable t) { lastError = t; }
                            }
                        } catch (Throwable t) { lastError = t; }
                    }
                } catch (Throwable t) { lastError = t; }
            }
        }

        if (sManager == null) {
            throw new Exception("Play Integrity API not found on any loader. Last: " + lastError);
        }
    }

    private ClassLoader[] buildLoaderList(Context ctx) {
        java.util.List<ClassLoader> list = new java.util.ArrayList<>();
        list.add(classLoader); // WhatsApp's own loader
        for (String pkg : new String[]{"com.google.android.gms", "com.android.vending"}) {
            try {
                Context pkgCtx = ctx.createPackageContext(pkg,
                        Context.CONTEXT_INCLUDE_CODE | Context.CONTEXT_IGNORE_SECURITY);
                list.add(pkgCtx.getClassLoader());
            } catch (Throwable ignored) {}
        }
        list.add(ClassLoader.getSystemClassLoader());
        return list.toArray(new ClassLoader[0]);
    }

    /** Return the classloader that has Play Core classes — WhatsApp first, then GMS. */
    private ClassLoader resolvePlayCoreLoader(Context ctx) {
        // Kept for compatibility — now delegates to buildLoaderList
        return classLoader;
    }

    private void requestToken(Context ctx, String nonce, String requestorPkg) {
        try {
            resolveIntegrityApi(ctx);

            Object builder  = sBuilderMethod.invoke(null);
            sSetHashMethod.invoke(builder, nonce);
            Object request  = sBuildMethod.invoke(builder);
            Object task     = sRequestMethod.invoke(sManager, request);

            final Context fCtx = ctx;

            sAddSuccessMethod.invoke(task,
                java.lang.reflect.Proxy.newProxyInstance(classLoader,
                    new Class[]{sAddSuccessMethod.getParameterTypes()[0]},
                    (p, m, args) -> {
                        try {
                            String token = (String) sTokenMethod.invoke(args[0]);
                            XposedBridge.log("[IntegrityBridge] Token obtained → " + requestorPkg);
                            sendResponse(fCtx, requestorPkg, token, null);
                        } catch (Throwable t) {
                            sendResponse(fCtx, requestorPkg, null, "token() failed: " + t);
                        }
                        return null;
                    }));

            sAddFailureMethod.invoke(task,
                java.lang.reflect.Proxy.newProxyInstance(classLoader,
                    new Class[]{sAddFailureMethod.getParameterTypes()[0]},
                    (p, m, args) -> {
                        Exception ex = (Exception) args[0];
                        XposedBridge.log("[IntegrityBridge] Token failed: " + ex);
                        sendResponse(fCtx, requestorPkg, null, ex.getMessage());
                        return null;
                    }));

        } catch (Throwable t) {
            XposedBridge.log("[IntegrityBridge] Exception: " + t);
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
