package com.wmods.wppenhacer.xposed.features.general;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;

import androidx.annotation.NonNull;
import androidx.core.content.ContextCompat;

import com.google.android.gms.tasks.Tasks;
import com.google.android.play.core.integrity.IntegrityManagerFactory;
import com.google.android.play.core.integrity.StandardIntegrityManager;
import com.google.android.play.core.integrity.StandardIntegrityManager.PrepareIntegrityTokenRequest;
import com.google.android.play.core.integrity.StandardIntegrityManager.StandardIntegrityToken;
import com.google.android.play.core.integrity.StandardIntegrityManager.StandardIntegrityTokenProvider;
import com.google.android.play.core.integrity.StandardIntegrityManager.StandardIntegrityTokenRequest;
import com.wmods.wppenhacer.xposed.core.Feature;
import com.wmods.wppenhacer.xposed.utils.Utils;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import de.robv.android.xposed.XSharedPreferences;

/**
 * IntegrityBridge — Phase 2B Play Integrity token proxy for BlackBox Reborn.
 *
 * Approach (replaces the old DexKit-into-WhatsApp reflection, which failed on
 * obfuscated Play Core classes): WaEnhancer BUNDLES the official Play Integrity
 * library and calls StandardIntegrityManager itself, from inside the real
 * com.whatsapp process. Because the process identity is com.whatsapp (real UID +
 * Meta signing cert), Google mints a token scoped to com.whatsapp — exactly what
 * BlackBox's virtual WhatsApp cannot produce for itself.
 *
 * Flow:
 *   1. BlackBox broadcasts ACTION_REQUEST (nonce = WhatsApp's requestHash) here.
 *   2. We prepare a StandardIntegrityTokenProvider once (warm-up) for WhatsApp's
 *      cloud project number, then request(requestHash) to get a token quickly.
 *   3. We broadcast ACTION_RESPONSE back to the requestor (BlackBox host pkg).
 *
 * The token is encrypted for WhatsApp's Google Cloud project, so the project
 * number MUST match the one WhatsApp uses (see configuredCloudProject()).
 */
public class IntegrityBridge extends Feature {

    public static final String ACTION_REQUEST  = "com.blackbox.integrity.REQUEST";
    public static final String ACTION_RESPONSE = "com.blackbox.integrity.RESPONSE";
    public static final String EXTRA_NONCE         = "nonce";
    public static final String EXTRA_REQUESTOR     = "requestor";
    public static final String EXTRA_TOKEN         = "token";
    public static final String EXTRA_ERROR         = "error";
    public static final String EXTRA_CLOUD_PROJECT = "cloud_project";

    /** LSPosed pref key (string or long) holding WhatsApp's cloud project number. */
    private static final String PREF_CLOUD_PROJECT = "integrity_cloud_project";

    /**
     * WhatsApp's Google Cloud project number. The Standard Integrity token is
     * encrypted for THIS project, so it must match the project WhatsApp's servers
     * decrypt with — otherwise the token is rejected. 0 = not configured.
     *
     * Set it one of three ways (first non-zero wins):
     *   - broadcast long extra "cloud_project" (BlackBox forwards the value it sees
     *     the virtual WhatsApp use — most robust, no hardcoding), OR
     *   - LSPosed pref "integrity_cloud_project", OR
     *   - hardcode below (found in WA dex near the "cloudProjectNumber" string).
     */
    // Confirmed from logcat: PlayCore "warmUpIntegrityToken(293955441834)" in the
    // virtual WhatsApp process — this is WhatsApp's real cloud project number.
    private static final long DEFAULT_CLOUD_PROJECT = 293955441834L;

    private static final long PREPARE_TIMEOUT_SEC = 25;
    private static final long REQUEST_TIMEOUT_SEC = 20;

    private final ExecutorService mExec = Executors.newSingleThreadExecutor();
    private final Object mPrepLock = new Object();

    private volatile StandardIntegrityManager mManager;
    private volatile StandardIntegrityTokenProvider mProvider;
    private volatile long mPreparedCpn = 0L;

    private Context mCtx;

    public IntegrityBridge(@NonNull ClassLoader classLoader,
                           @NonNull XSharedPreferences preferences) {
        super(classLoader, preferences);
    }

    @Override
    public void doHook() throws Throwable {
        Context app = Utils.getApplication();
        if (app == null) {
            log("WhatsApp context null — skipping");
            return;
        }
        mCtx = app;

        BroadcastReceiver receiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context ctx, Intent intent) {
                if (!ACTION_REQUEST.equals(intent.getAction())) return;
                final String nonce       = intent.getStringExtra(EXTRA_NONCE);
                final String requestor   = intent.getStringExtra(EXTRA_REQUESTOR);
                final long   cpnOverride = intent.getLongExtra(EXTRA_CLOUD_PROJECT, 0L);
                if (nonce == null || requestor == null) {
                    log("Bad request: missing nonce/requestor");
                    return;
                }
                log("Request nonce=" + preview(nonce) + " from " + requestor
                        + (cpnOverride > 0 ? " cpn=" + cpnOverride : ""));
                mExec.execute(() -> handleRequest(nonce, requestor, cpnOverride));
            }
        };
        ContextCompat.registerReceiver(app, receiver,
                new IntentFilter(ACTION_REQUEST), ContextCompat.RECEIVER_EXPORTED);

        // Warm up ahead of the first request if we already know the project number.
        long cpn = configuredCloudProject();
        if (cpn > 0) {
            mExec.execute(() -> {
                try {
                    ensureProvider(cpn);
                    log("Warm-up prepared for cpn=" + cpn);
                } catch (Throwable t) {
                    log("Warm-up failed: " + t);
                }
            });
        } else {
            log("cloudProjectNumber not set — configure pref '" + PREF_CLOUD_PROJECT
                    + "', or send long extra '" + EXTRA_CLOUD_PROJECT + "' with the request");
        }
        log("Ready — bundled StandardIntegrityManager, listening for BlackBox requests");
    }

    private void handleRequest(String nonce, String requestor, long cpnOverride) {
        try {
            long cpn = cpnOverride > 0 ? cpnOverride : configuredCloudProject();
            if (cpn <= 0) {
                sendResponse(requestor, null, "cloudProjectNumber not configured");
                return;
            }
            StandardIntegrityTokenProvider provider = ensureProvider(cpn);
            StandardIntegrityTokenRequest req = StandardIntegrityTokenRequest.builder()
                    .setRequestHash(nonce)
                    .build();
            StandardIntegrityToken tok = Tasks.await(
                    provider.request(req), REQUEST_TIMEOUT_SEC, TimeUnit.SECONDS);
            String token = tok != null ? tok.token() : null;
            log("Token obtained len=" + (token != null ? token.length() : 0));
            sendResponse(requestor, token, token == null ? "token() returned null" : null);
        } catch (Throwable t) {
            log("handleRequest error: " + t);
            sendResponse(requestor, null, String.valueOf(t));
        }
    }

    /** Prepare (and cache) a token provider for the given cloud project number. */
    private StandardIntegrityTokenProvider ensureProvider(long cpn) throws Exception {
        StandardIntegrityTokenProvider p = mProvider;
        if (p != null && mPreparedCpn == cpn) return p;
        synchronized (mPrepLock) {
            if (mProvider != null && mPreparedCpn == cpn) return mProvider;
            if (mManager == null) {
                mManager = IntegrityManagerFactory.createStandard(mCtx);
            }
            PrepareIntegrityTokenRequest prep = PrepareIntegrityTokenRequest.builder()
                    .setCloudProjectNumber(cpn)
                    .build();
            StandardIntegrityTokenProvider np = Tasks.await(
                    mManager.prepareIntegrityToken(prep), PREPARE_TIMEOUT_SEC, TimeUnit.SECONDS);
            mProvider = np;
            mPreparedCpn = cpn;
            return np;
        }
    }

    private long configuredCloudProject() {
        try {
            Object v = prefs.getAll().get(PREF_CLOUD_PROJECT);
            if (v instanceof Long)    return (Long) v;
            if (v instanceof Integer) return ((Integer) v).longValue();
            if (v instanceof String && !((String) v).isEmpty())
                return Long.parseLong(((String) v).trim());
        } catch (Throwable ignored) {
        }
        return DEFAULT_CLOUD_PROJECT;
    }

    private void sendResponse(String pkg, String token, String error) {
        try {
            Intent resp = new Intent(ACTION_RESPONSE);
            resp.setPackage(pkg);
            if (token != null) resp.putExtra(EXTRA_TOKEN, token);
            if (error != null) resp.putExtra(EXTRA_ERROR, error);
            Context c = mCtx != null ? mCtx : Utils.getApplication();
            if (c != null) c.sendBroadcast(resp);
            if (error != null) log("Responded error: " + error);
        } catch (Throwable t) {
            log("sendResponse failed: " + t);
        }
    }

    private static String preview(String s) {
        if (s == null) return "null";
        return s.length() <= 16 ? s : s.substring(0, 16) + "...";
    }

    @NonNull
    @Override
    public String getPluginName() {
        return "Integrity Bridge";
    }
}
