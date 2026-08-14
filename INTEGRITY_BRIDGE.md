# WaEnhancer — IntegrityBridge Development Log

## Purpose

`IntegrityBridge` is a Play Integrity token proxy for BlackBox Reborn's virtual
WhatsApp. A virtual WhatsApp runs under BlackBox's package identity, so it cannot
mint a Play Integrity token scoped to `com.whatsapp`; WhatsApp's servers reject
such tokens. This bridge mints a genuine `com.whatsapp` token from inside the
real WhatsApp process and hands it back to BlackBox.

## Approach (2026-08-14) — Bundled StandardIntegrityManager

The earlier DexKit-into-WhatsApp reflection approach failed: WA 2.26.30.97 ships
Play Core with obfuscated class names, and DexKit only ever found the string
constants holder (`X.HIq`), never the real manager.

New approach — **bundle the official Play Integrity library into WaEnhancer** and
call it directly:

- `app/build.gradle.kts`: `implementation(libs.play.integrity)`
- `gradle/libs.versions.toml`: `play-integrity = com.google.android.play:integrity:1.4.0`

Because the classes now ship inside WaEnhancer's own APK (loaded by the module
classloader), there is nothing to de-obfuscate. The call runs inside the real
`com.whatsapp` process, so Google mints a token scoped to `com.whatsapp` with
Meta's signing cert — exactly what the virtual app cannot produce.

### Flow

```
BlackBox (virtual WA)                 WaEnhancer (real WA / LSPosed)
      │  ACTION_REQUEST                       │
      │  extras: nonce (=requestHash),        │
      │          requestor (=host pkg),       │
      │          cloud_project (optional) ───►│  StandardIntegrityManager
      │                                       │   .prepareIntegrityToken(cpn)   (warm-up, cached)
      │                                       │   provider.request(requestHash) (fast)
      │◄──── ACTION_RESPONSE ─────────────────│   → token()
      │  extras: token / error                │
```

### API used (Play Integrity 1.4.0, verified via javap + compile check)

- `IntegrityManagerFactory.createStandard(Context)` → `StandardIntegrityManager`
- `StandardIntegrityManager.prepareIntegrityToken(PrepareIntegrityTokenRequest)`
  → `Task<StandardIntegrityTokenProvider>`
- `PrepareIntegrityTokenRequest.builder().setCloudProjectNumber(long).build()`
- `StandardIntegrityTokenProvider.request(StandardIntegrityTokenRequest)`
  → `Task<StandardIntegrityToken>`
- `StandardIntegrityTokenRequest.builder().setRequestHash(String).build()`
- `StandardIntegrityToken.token()` → `String`
- `com.google.android.gms.tasks.Tasks.await(task, timeout, unit)`

The provider is prepared once and cached (keyed by cloud project number); each
request reuses it, so token generation after warm-up is sub-second.

## REQUIRED: WhatsApp's cloud project number

The Standard Integrity token is **encrypted for a specific Google Cloud project**.
It must be the project WhatsApp's servers decrypt with, or the token is rejected.
`DEFAULT_CLOUD_PROJECT` is `0` (unset). Provide it (first non-zero wins):

1. **Broadcast extra `cloud_project` (long)** — BlackBox can forward the value it
   sees the virtual WhatsApp pass to `setCloudProjectNumber` (most robust).
2. **LSPosed pref `integrity_cloud_project`** (string or long).
3. **Hardcode `DEFAULT_CLOUD_PROJECT`** — located in the WA APK dex near the
   `cloudProjectNumber` string (offset ~1678365 in classes9.dex per prior DexKit).

Until this is set, the bridge logs `cloudProjectNumber not configured` and replies
with that error instead of a token.

## Build / deploy notes

- Build the **whatsapp** flavor release APK, signed with `waenhancer-release.jks`.
- LSPosed scope: enable for `com.whatsapp` only (not business).
- Every reinstall needs a **full device reboot** for LSPosed to load the new dex —
  batch all changes per reboot cycle.
- `findAllMethodUsingStrings` is gone; no more slow DexKit scans at startup.

## Open items

- Set the real cloud project number (see above) — without it no token is minted.
- Verify end-to-end: BlackBox must extract WhatsApp's real `requestHash` from the
  intercepted Express Integrity binder transaction and pass it as `nonce`. See
  BlackBox `IntegrityProxy` / `DEVELOPMENT.md` — the Express service is
  callback-based, so this is the remaining hard part on the BlackBox side.
