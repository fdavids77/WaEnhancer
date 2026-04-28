package com.wmods.wppenhacer.xposed.features.general;

import android.app.Activity;
import android.webkit.WebSettings;
import android.webkit.WebView;

import androidx.annotation.NonNull;

import com.wmods.wppenhacer.xposed.core.Feature;
import com.wmods.wppenhacer.xposed.core.WppCore;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XSharedPreferences;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;

public class WebWhatsAppLink extends Feature {

    public WebWhatsAppLink(@NonNull ClassLoader loader, @NonNull XSharedPreferences preferences) {
        super(loader, preferences);
    }

    @Override
    public void doHook() throws Throwable {
        if (!prefs.getBoolean("web_whatsapp_link", false)) return;

        String[] activities = {
            "com.whatsapp.companiondevice.qrcode.DevicePairQrScannerActivity",
            "com.whatsapp.companionmode.registration.ui.RegisterAsCompanionActivity"
        };

        for (String activityName : activities) {
            try {
                Class<?> activityClass = XposedHelpers.findClass(activityName, loader);
                XposedBridge.hookAllMethods(activityClass, "onCreate", new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                        Activity activity = (Activity) param.thisObject;
                        WebView webView = new WebView(activity);
                        WebSettings settings = webView.getSettings();
                        settings.setJavaScriptEnabled(true);
                        settings.setDomStorageEnabled(true);
                        settings.setUserAgentString("Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36");
                        settings.setLoadWithOverviewMode(true);
                        settings.setUseWideViewPort(true);
                        activity.setContentView(webView);
                        webView.loadUrl("https://web.whatsapp.com");
                        XposedBridge.log("WebWhatsAppLink: Replaced UI with WebView in " + activityName);
                    }
                });
                XposedBridge.log("WebWhatsAppLink: Hooked " + activityName);
            } catch (Exception e) {
                XposedBridge.log("WebWhatsAppLink: Failed to hook " + activityName + ": " + e.getMessage());
            }
        }
    }

    @Override
    public String getPluginName() {
        return "WebWhatsAppLink";
    }
}
