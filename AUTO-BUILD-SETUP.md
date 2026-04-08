# WaEnhancer Auto-Build Setup Guide

## What was changed

### 1. `arrays.xml` — Added 2.26.12.xx support
### 2. `android.yml` — Fixed workflow_dispatch trigger, added `main` branch
### 3. `auto-update-versions.yml` — NEW: Daily auto-check for new WhatsApp versions

---

## GitHub Secrets Setup

Go to: **Repository → Settings → Secrets and variables → Actions → New repository secret**

Add these 4 secrets:

| Secret Name        | Value                                    |
|--------------------|------------------------------------------|
| KEY_STORE          | (paste the base64 keystore below)        |
| KEY_STORE_PASSWORD | waenhancer123                            |
| ALIAS              | waenhancer                               |
| KEY_PASSWORD       | waenhancer123                            |

### KEY_STORE base64 value:
Copy the ENTIRE contents of the file `keystore-base64.txt` (included in outputs).

---

## How to push changes

```bash
cd WaEnhancer
git add -A
git commit -m "feat: add support for WA/WB 2.26.12.xx + auto-update workflow"
git push origin master
```

This push will trigger the **Android CI** workflow, which will:
1. Build both WhatsApp and Business flavors
2. Upload artifacts
3. Create a GitHub Release with signed APKs

---

## How the Auto-Update works

The **Auto-Update WhatsApp Versions** workflow runs daily at 06:00 UTC (08:00 SAST):

1. Scrapes APKPure/Uptodown/WABetaInfo for the latest WhatsApp version
2. Compares against currently supported versions in `arrays.xml`
3. If new versions found → patches `arrays.xml`, commits, pushes
4. The push triggers the Android CI workflow → builds + releases signed APKs

You can also trigger it manually from: **Actions → Auto-Update WhatsApp Versions → Run workflow**

The "Force build" option lets you rebuild even if no new version is detected.

---

## Important Notes

- **Signing key**: All future builds will use this keystore. DON'T lose it — if you
  re-sign with a different key, you'll need to uninstall + reinstall on device.
- **DexKit compatibility**: Adding version strings only bypasses the version check.
  If WhatsApp changes internal class structures, some features may break.
  Error toasts in WhatsApp will tell you exactly which plugin/hook failed.
- **GitHub Actions limits**: Free tier gets 2,000 minutes/month. Each build uses ~5-10 min.
  Daily checks that find nothing use <1 min.
