# DroneBird

A landscape Flappy-Bird-style game piloted by a **DJI Mavic Air 2 Remote
Controller (RC-N1)** read as a **raw USB CDC/VCOM serial device** — no DJI SDK,
no App Key, no developer account. Full touchscreen fallback when no controller is
attached.

See **[BUILD_GUIDE.md](BUILD_GUIDE.md)** for the full build + on-device discovery
and calibration workflow.

---

## Get an installable APK without Android Studio (GitHub Actions)

This repo ships a CI workflow (`.github/workflows/build-apk.yml`) that builds the
debug APK on GitHub's servers and uploads it for download.

### 1. Put the project on GitHub
Create a new repository (private is fine), then from the unzipped project folder:

```bash
cd DroneBird
git init
git add .
git commit -m "DroneBird v1"
git branch -M main
git remote add origin https://github.com/<your-username>/<your-repo>.git
git push -u origin main
```

(No command line? Use **GitHub Desktop** — "Add Local Repository" → publish — or
the website's **Add file → Upload files** and drag the whole `DroneBird` folder
in, keeping its structure.)

### 2. Let CI build it
- Open the repo's **Actions** tab.
- The **Build DroneBird APK** workflow starts automatically on your push
  (first run ≈ 3–5 min). You can also trigger it manually via **Run workflow**.

### 3. Download the APK
- Click the finished (green ✓) run.
- Scroll to **Artifacts** → download **dronebird-debug-apk**.
- Unzip it → you get **`app-debug.apk`**.

### 4. Install on your phone
- Copy `app-debug.apk` to the phone and open it.
- Allow **install from unknown sources** if prompted.
- This is an unsigned **debug** build — fine for personal installs.

---

## First launch
Open **DIAGNOSTIC / CALIBRATE** first, connect the RC-N1 over a data-capable
USB-C OTG cable, confirm bytes move, set the offsets if needed, and calibrate each
axis. Only then is flight control reliable. Details in **BUILD_GUIDE.md** §3.

## Notes
- Debug APK is unsigned. For a signed **release** APK (longer-term distribution),
  a keystore + a signing step in the workflow are needed.
- If a CI run fails on Android SDK license acceptance, re-run the job.
