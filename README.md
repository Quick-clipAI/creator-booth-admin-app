# Creator Booth Admin (Android)

A small Android shell that opens https://creator-boot.vercel.app/admin.html full-screen. Nothing is bundled: it always shows the live admin page, so deploying the website updates the admin and this app never needs re-installing for admin changes. If the phone is offline it shows a "Can't reach the admin page" screen with a Try again button.

## Install
1. Open the repo's **Releases** page and download `creator-booth-admin.apk` from the **Creator Booth Admin (latest)** release.
2. Open the file and allow "install from this source" if the phone asks.
3. Open **CB Admin** and unlock with your admin key as usual.

## Updating the app itself
Only needed if this wrapper changes (icon, name). If a new file will not install over the old one ("app not installed"), uninstall CB Admin once and install again: the signing key is kept in GitHub's cache and is recreated if the cache is cleared. To make it permanent, add the repo secrets `ANDROID_KEYSTORE_BASE64`, `ANDROID_KEYSTORE_PASSWORD`, `ANDROID_KEY_ALIAS`, `ANDROID_KEY_PASSWORD`.

## Build
GitHub Actions builds on every push to `main` (Actions → Build Admin APK). No Firebase or other secrets are needed.

## Fingerprint unlock (v1.1.0)
The admin page asks this app's own plugin (`native/CbBiometricPlugin.java`, added to the generated Android project by the workflow) to keep the admin key in the phone's secure hardware store. The key can only be released with a fingerprint (Android BiometricPrompt), and adding a new fingerprint to the phone invalidates it. Use: sign in with the key once, tap **Fingerprint** at the top of the admin page, approve; next time the app opens with the fingerprint sheet. Leaving the app for 10 seconds locks it again. Needs a fingerprint set up on the phone (Android "strong" biometrics).
Installing 1.1.0 over 1.0.0 works only while the signing key is still in GitHub's cache; if Android says "app not installed", uninstall CB Admin once and install again.
