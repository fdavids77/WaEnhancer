# WaEnhancer — IntegrityBridge Development Log

## Purpose

`IntegrityBridge` is a new feature added to act as a Play Integrity token proxy
for BlackBox Reborn's virtual WhatsApp instances. Virtual WhatsApp running inside
BlackBox cannot generate a `com.whatsapp`-signed Play Integrity token (it runs as
`top.niunaijun.blackbox`). WhatsApp's registration server requires a valid
`com.whatsapp` token and returns the "Download official WhatsApp" parole block otherwise.

The bridge routes token requests through the real WhatsApp process (which has the
correct identity), returning a genuine token to BlackBox.

---

## Changes Made

### `app/src/main/res/values/arrays.xml`
Extended `supported_versions_wpp` and `supported_versions_business` from 2.26.15.xx
to 2.26.30.xx to support WhatsApp 2.26.30.97 (current version on test device).

### `app/src/main/java/com/wmods/wppenhacer/xposed/core/FeatureLoader.java`
1. Wrapped `FMessageWpp.initialize(loader)` and `WppCore.Initialize(loader, pref)` in
   individual `try/catch` inside `initComponents()`. This means version-specific
   obfuscation failures (expected with 2.26.30.97 — many internal class names changed)
   do NOT prevent IntegrityBridge from loading via `plugins()`.
2. Added `IntegrityBridge.class` to the `plugins()` array.

### `app/src/main/java/com/wmods/wppenhacer/xposed/features/general/IntegrityBridge.java`
New file. Full source in repo.

---

## Protocol

```
BlackBox broadcasts to com.whatsapp:
  Action:  com.blackbox.integrity.REQUEST
  Extras:
    "nonce"     → String  (the Play Integrity request hash from the server)
    "requestor" → String  (BlackBox's package name, for targeted response)

WaEnhancer broadcasts back:
  Action:  com.blackbox.integrity.RESPONSE
  Package: requestor (targeted to BlackBox only)
  Extras:
    "token" → String  (the real com.whatsapp Play Integrity token), OR
    "error" → String  (failure reason)
```

---

## Current State — BLOCKED

### What works ✅
- BroadcastReceiver registers successfully on WhatsApp startup
- Confirmed via `adb shell am broadcast` test — REQUEST received, logged, processed

### What fails ❌

**Play Core API class resolution:**
WhatsApp 2.26.30.97 has the Play Core library embedded with obfuscated class names.
The canonical class names do not exist in any accessible classloader:

- `com.google.android.play.core.integrity.IntegrityManagerFactory` → NOT FOUND
- `com.google.android.play.core.integrity.StandardIntegrityManager$StandardIntegrityTokenRequest` → NOT FOUND
- `com.google.android.play.core.tasks.Task` → NOT FOUND (SDK restructured)

Tried classloaders: WhatsApp's own, GMS (`com.google.android.gms`), Play Store
(`com.android.vending`), System.

**DexKit investigation:**
- `findFirstClassUsingStrings("expressintegrityservice")` → returns `X.HIq`
- `X.HIq` has 0 declared methods, 0 declared fields — it is a string constants holder
- The actual manager class (equivalent of `StandardIntegrityManager`) is unidentified
- `findAllMethodUsingStrings` scans are too slow for use in `doHook()` — hang WA startup

---

## Recommended Fix for Next Session

### Option A — Raw Binder call (best approach)

Bypass Play Core Java classes entirely. Bind directly to the integrity service:

```java
// In IntegrityBridge.doHook() or requestToken():
Intent intent = new Intent(
    "com.google.android.play.core.expressintegrityservice.BIND_EXPRESS_INTEGRITY_SERVICE");
intent.setPackage("com.android.vending");

context.bindService(intent, new ServiceConnection() {
    @Override
    public void onServiceConnected(ComponentName name, IBinder service) {
        // Use IExpressIntegrityService AIDL transaction
        // AIDL source: https://github.com/google/play-integrity (or decompile Play Core)
        // Transaction 1 = requestIntegrityToken(nonce, cloudProjectNumber, extras)
        Parcel data = Parcel.obtain();
        Parcel reply = Parcel.obtain();
        try {
            data.writeInterfaceToken("com.google.android.finsky.expressintegrityservice.IExpressIntegrityService");
            data.writeString(nonce);
            data.writeLong(0L); // cloudProjectNumber (0 = not set)
            service.transact(1, data, reply, 0);
            reply.readException();
            String token = reply.readString();
            sendResponse(context, requestorPkg, token, null);
        } finally {
            data.recycle(); reply.recycle();
            context.unbindService(this);
        }
    }
    @Override
    public void onServiceDisconnected(ComponentName name) {}
}, Context.BIND_AUTO_CREATE);
```

The interface descriptor and transaction code must be verified from the Play Core SDK
source or by decompiling `com.android.vending`. The AIDL is documented in various
Play Core decompilation projects online.

### Option B — Hook WhatsApp's own integrity calls via DexKit opcode search

Use DexKit's `OpCodesMatcher` to find methods that call `bindService` with the
express integrity intent (by opcode pattern), then hook that method to intercept
both the nonce going in and the token coming out.

---

## LSPosed Setup Requirements

- Enable WaEnhancer for `com.whatsapp` (standard WhatsApp, NOT business) in LSPosed scope
- Every WaEnhancer APK reinstall requires a **full device reboot** for injection to work
  (LSPosed compiles dex at boot time — a force-stop + relaunch is NOT sufficient)
- Plan all WaEnhancer code changes in ONE batch before each reboot to avoid multiple cycles
- WaEnhancer internal bridge (`WaeIIFace`) requires "Android System" / System Framework
  scope — not needed for IntegrityBridge but fixes other WaEnhancer features
