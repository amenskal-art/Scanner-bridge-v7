# UVC Cam Pro

Android app for USB-C UVC webcams: live preview + full manual controls (exposure, white balance, brightness, contrast, saturation, sharpness, zoom). Dark "optics bench" UI. Auto-launches when the camera is plugged in.

## Build it (GitHub Actions — no Android Studio)

1. Create a new GitHub repo (private is fine).
2. Upload **all** the contents of this folder via **Add file → Upload files** in the GitHub web UI.
   - Important: make sure the hidden folder `.github/workflows/build-apk.yml` gets uploaded. If your file manager hides it, enable "show hidden files" before dragging the folder contents in. You can also create it manually in the GitHub web UI (Add file → Create new file → type `.github/workflows/build-apk.yml` as the name and paste the contents).
3. Commit. The **Actions** tab will start a "Build APK" run automatically.
4. When it finishes (~3–5 min), open the run and download the **UVC-Cam-Pro-debug-apk** artifact.
5. Unzip it, copy `app-debug.apk` to your phone, and install (allow unknown sources).

## Using the app

- Plug the webcam into the USB-C port → the app launches by itself with USB permission already granted. (Opening it manually works too — you'll just get one system "allow USB device" dialog.)
- Auto exposure / auto white balance are switches. Turn them off to unlock the manual sliders.
- All sliders are 0–100 (percent of your camera's supported range), same scale the camera exposes over UVC.
- **Reset all controls** puts everything back to camera defaults and re-reads the real values.

## Safe apply

Some camera firmwares refuse control writes while the stream is running (the picture freezes or the camera hangs when you move a slider). If that happens, turn on **Safe apply** in the bottom card — it briefly pauses the stream around each write, then resumes. You'll see a short black flicker per adjustment; that's normal.

## If auto-launch doesn't trigger

The app matches standard UVC device classes (239/2/1 and 14). If your specific camera reports something unusual, find its vendor ID (any USB info app, or `lsusb` on PC) and add a line to `app/src/main/res/xml/device_filter.xml`:

```xml
<usb-device vendor-id="XXXX" />
```

(vendor-id is decimal, not hex.)

## Tech notes

- Library: AUSBC 3.3.3, **built from source** — `ci/prepare-ausbc.sh` downloads the official 3.3.3 source at build time and compiles it as local modules (JitPack's published artifacts for 3.3.3 are broken, so the build doesn't touch JitPack at all).
- The libuvc native code uses the prebuilt `.so` files shipped in the source tree; only a small CMake module (libnative) compiles on the runner.
- Exposure has no public API in AUSBC 3.3.3 — `UvcExposure.kt` reaches the private native methods via reflection and maps exposure to the same 0–100 percent scale as the other controls.
- Preview: 1280×720, OpenGL render mode. ABIs: armeabi-v7a + arm64-v8a.
- minSdk 26, targetSdk 34.
