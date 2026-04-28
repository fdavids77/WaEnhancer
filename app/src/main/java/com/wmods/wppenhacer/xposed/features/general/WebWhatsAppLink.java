package com.wmods.wppenhacer.xposed.features.general;

import android.app.Activity;
import android.os.Bundle;
import android.view.View;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;

import androidx.annotation.NonNull;

import com.wmods.wppenhacer.xposed.core.Feature;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XSharedPreferences;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;

/**
 * WebWhatsAppLink - Enables phone number linking on renamed WhatsApp clones
 *
 * This feature hooks DevicePairQrScannerActivity.onCreate() and replaces the
 * native QR scanner with a WebView loading web.whatsapp.com.
 *
 * The web protocol only requires BASIC Play Integrity (easily spoofable),
 * unlike the native app which requires DEVICE integrity.
 *
 * This allows cloned/renamed WhatsApp packages to link devices via phone number
 * when the native QR linking fails due to integrity checks.
 */
public class WebWhatsAppLink extends Feature {

    private static final String DEVICE_PAIR_QR_SCANNER_ACTIVITY =
            "com.whatsapp.companiondevice.qrcode.DevicePairQrScannerActivity";

    private static final String WEB_WHATSAPP_URL = "https://web.whatsapp.com";

    // Desktop Chrome user agent - required for web.whatsapp.com
    private static final String DESKTOP_USER_AGENT =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
            "(KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36";

    public WebWhatsAppLink(@NonNull ClassLoader classLoader, @NonNull XSharedPreferences preferences) {
        super(classLoader, preferences);
    }

    @Override
    public void doHook() throws Throwable {
        // Check if feature is enabled in preferences
        if (!prefs.getBoolean("web_whatsapp_link", false)) {
            return;
        }

        try {
            // Hook DevicePairQrScannerActivity.onCreate
            XposedHelpers.findAndHookMethod(
                    DEVICE_PAIR_QR_SCANNER_ACTIVITY,
                    classLoader,
                    "onCreate",
                    Bundle.class,
                    new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                            Activity activity = (Activity) param.thisObject;

                            log("Intercepting DevicePairQrScannerActivity, loading web.whatsapp.com");

                            // Create WebView
                            WebView webView = new WebView(activity);

                            // Configure WebSettings
                            WebSettings settings = webView.getSettings();
                            settings.setJavaScriptEnabled(true);
                            settings.setDomStorageEnabled(true);
                            settings.setDatabaseEnabled(true);
                            settings.setUserAgentString(DESKTOP_USER_AGENT);
                            settings.setLoadWithOverviewMode(true);
                            settings.setUseWideViewPort(true);
                            settings.setSupportZoom(true);
                            settings.setBuiltInZoomControls(true);
                            settings.setDisplayZoomControls(false);

                            // Set WebViewClient to handle navigation within WebView
                            webView.setWebViewClient(new WebViewClient() {
                                @Override
                                public boolean shouldOverrideUrlLoading(WebView view, String url) {
                                    // Keep all navigation within the WebView
                                    view.loadUrl(url);
                                    return true;
                                }
                            });

                            // Set WebView as content view (replaces native QR scanner UI)
                            activity.setContentView(webView);

                            // Load web.whatsapp.com
                            webView.loadUrl(WEB_WHATSAPP_URL);

                            log("WebView loaded with web.whatsapp.com");
                        }
                    }
            );

            log("Successfully hooked DevicePairQrScannerActivity");

        } catch (Throwable e) {
            log("Failed to hook DevicePairQrScannerActivity: " + e.getMessage());
            XposedBridge.log(e);
        }
    }

    @NonNull
    @Override
    public String getPluginName() {
        return "Web WhatsApp Link";
    }
}
