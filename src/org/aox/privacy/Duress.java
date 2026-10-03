package org.aox.privacy;

import android.os.RemoteException;
import android.os.ServiceManager;
import android.util.Base64;

import com.android.internal.widget.ILockSettings;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;

import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;

/**
 * Stores the duress credential in the lock settings database. The hash format
 * ("&lt;b64 salt&gt;:&lt;b64 PBKDF2WithHmacSHA256, 10000 iterations, 256 bit&gt;") must match
 * isDuressCredential() in the LockSettingsService duress patch.
 */
final class Duress {
    private static final String KEY = "ax_duress_credential";
    private static final int ITERATIONS = 10000;

    private Duress() {}

    private static ILockSettings lock() {
        return ILockSettings.Stub.asInterface(ServiceManager.getService("lock_settings"));
    }

    static boolean isSet(int userId) {
        try {
            return !lock().getString(KEY, "", userId).isEmpty();
        } catch (RemoteException e) {
            return false;
        }
    }

    static void set(String credential, int userId) throws RemoteException {
        final byte[] salt = new byte[16];
        new SecureRandom().nextBytes(salt);
        try {
            final byte[] hash = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
                    .generateSecret(new PBEKeySpec(credential.toCharArray(), salt, ITERATIONS, 256))
                    .getEncoded();
            lock().setString(KEY, Base64.encodeToString(salt, Base64.NO_WRAP) + ":"
                    + Base64.encodeToString(hash, Base64.NO_WRAP), userId);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }

    static void clear(int userId) throws RemoteException {
        lock().setString(KEY, "", userId);
    }
}
