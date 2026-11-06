package com.creatorbooth.admin;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Build;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyPermanentlyInvalidatedException;
import android.security.keystore.KeyProperties;
import android.util.Base64;

import androidx.annotation.NonNull;
import androidx.biometric.BiometricManager;
import androidx.biometric.BiometricPrompt;
import androidx.core.content.ContextCompat;
import androidx.fragment.app.FragmentActivity;

import com.getcapacitor.JSObject;
import com.getcapacitor.Plugin;
import com.getcapacitor.PluginCall;
import com.getcapacitor.PluginMethod;
import com.getcapacitor.annotation.CapacitorPlugin;

import java.nio.charset.StandardCharsets;
import java.security.KeyStore;

import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;

/**
 * Keeps the admin key in the phone's secure hardware store. The AES key never leaves the Android Keystore and can only be
 * used after a fingerprint (BiometricPrompt with a CryptoObject). Adding a new fingerprint to the phone invalidates it.
 * JS: Capacitor.Plugins.CbBiometric.{isAvailable, isEnrolled, enroll({secret}), unlock(), clear()}
 */
@CapacitorPlugin(name = "CbBiometric")
public class CbBiometricPlugin extends Plugin {
    private static final String ALIAS = "cb_admin_key_v1";
    private static final String PREFS = "cb_admin_bio";
    private static final int STRONG = BiometricManager.Authenticators.BIOMETRIC_STRONG;

    private SharedPreferences prefs() { return getContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE); }

    @PluginMethod
    public void isAvailable(PluginCall call) {
        int r = BiometricManager.from(getContext()).canAuthenticate(STRONG);
        JSObject o = new JSObject();
        o.put("available", r == BiometricManager.BIOMETRIC_SUCCESS);
        o.put("code", r);
        call.resolve(o);
    }

    @PluginMethod
    public void isEnrolled(PluginCall call) {
        boolean has = prefs().contains("ct") && prefs().contains("iv");
        try {
            if (has) { KeyStore ks = KeyStore.getInstance("AndroidKeyStore"); ks.load(null); has = ks.containsAlias(ALIAS); }
        } catch (Exception e) { has = false; }
        JSObject o = new JSObject();
        o.put("enrolled", has);
        call.resolve(o);
    }

    @PluginMethod
    public void clear(PluginCall call) {
        wipe();
        call.resolve();
    }

    private void wipe() {
        prefs().edit().clear().apply();
        try { KeyStore ks = KeyStore.getInstance("AndroidKeyStore"); ks.load(null); if (ks.containsAlias(ALIAS)) ks.deleteEntry(ALIAS); } catch (Exception ignored) { }
    }

    private SecretKey createKey() throws Exception {
        KeyGenerator kg = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore");
        KeyGenParameterSpec.Builder b = new KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .setUserAuthenticationRequired(true);
        if (Build.VERSION.SDK_INT >= 30) b.setUserAuthenticationParameters(0, KeyProperties.AUTH_BIOMETRIC_STRONG);
        else b.setUserAuthenticationValidityDurationSeconds(-1);
        if (Build.VERSION.SDK_INT >= 24) b.setInvalidatedByBiometricEnrollment(true);
        kg.init(b.build());
        return kg.generateKey();
    }

    private SecretKey loadKey() throws Exception {
        KeyStore ks = KeyStore.getInstance("AndroidKeyStore");
        ks.load(null);
        return (SecretKey) ks.getKey(ALIAS, null);
    }

    private interface Done { void ok(Cipher cipher) throws Exception; }

    /** Shows the fingerprint sheet bound to this cipher, then hands the unlocked cipher to `done`. */
    private void prompt(final PluginCall call, final Cipher cipher, final Done done) {
        if (!(getActivity() instanceof FragmentActivity)) { call.reject("Fingerprint is not available in this window."); return; }
        final FragmentActivity act = (FragmentActivity) getActivity();
        act.runOnUiThread(() -> {
            try {
                BiometricPrompt.PromptInfo info = new BiometricPrompt.PromptInfo.Builder()
                        .setTitle("Creator Booth admin")
                        .setSubtitle("Use your fingerprint")
                        .setNegativeButtonText("Cancel")
                        .setAllowedAuthenticators(STRONG)
                        .build();
                BiometricPrompt bp = new BiometricPrompt(act, ContextCompat.getMainExecutor(getContext()), new BiometricPrompt.AuthenticationCallback() {
                    @Override public void onAuthenticationSucceeded(@NonNull BiometricPrompt.AuthenticationResult result) {
                        try { done.ok(result.getCryptoObject().getCipher()); }
                        catch (Exception e) { call.reject("Could not use the secure key: " + e.getMessage()); }
                    }
                    @Override public void onAuthenticationError(int code, @NonNull CharSequence msg) { call.reject("Fingerprint cancelled: " + msg); }
                });
                bp.authenticate(info, new BiometricPrompt.CryptoObject(cipher));
            } catch (Exception e) { call.reject("Fingerprint failed: " + e.getMessage()); }
        });
    }

    @PluginMethod
    public void enroll(final PluginCall call) {
        final String secret = call.getString("secret");
        if (secret == null || secret.isEmpty()) { call.reject("Nothing to save."); return; }
        try {
            wipe();
            SecretKey key = createKey();
            Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
            c.init(Cipher.ENCRYPT_MODE, key);
            prompt(call, c, cipher -> {
                byte[] ct = cipher.doFinal(secret.getBytes(StandardCharsets.UTF_8));
                prefs().edit().putString("iv", Base64.encodeToString(cipher.getIV(), Base64.NO_WRAP)).putString("ct", Base64.encodeToString(ct, Base64.NO_WRAP)).apply();
                call.resolve();
            });
        } catch (Exception e) { wipe(); call.reject("Could not set up the secure key: " + e.getMessage()); }
    }

    @PluginMethod
    public void unlock(final PluginCall call) {
        String iv = prefs().getString("iv", null), ct = prefs().getString("ct", null);
        if (iv == null || ct == null) { call.reject("Fingerprint unlock is not set up."); return; }
        try {
            SecretKey key = loadKey();
            if (key == null) { wipe(); call.reject("Fingerprint unlock is not set up."); return; }
            Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
            c.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(128, Base64.decode(iv, Base64.NO_WRAP)));
            final byte[] data = Base64.decode(ct, Base64.NO_WRAP);
            prompt(call, c, cipher -> {
                JSObject o = new JSObject();
                o.put("secret", new String(cipher.doFinal(data), StandardCharsets.UTF_8));
                call.resolve(o);
            });
        } catch (KeyPermanentlyInvalidatedException e) {
            wipe(); call.reject("Your fingerprints changed, so the saved key was removed (invalidated). Sign in with the key and turn fingerprint on again.");
        } catch (Exception e) { call.reject("Could not open the secure key: " + e.getMessage()); }
    }
}
