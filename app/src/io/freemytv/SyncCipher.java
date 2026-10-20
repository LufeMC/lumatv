package io.freemytv;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

/** Authenticate encrypted sync messages and their direction. */
final class SyncCipher {
    private static final SecureRandom RANDOM = new SecureRandom();
    static byte[] seal(byte[] secret, String aad, byte[] plain) throws Exception {
        byte[] iv = new byte[12];
        RANDOM.nextBytes(iv);
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(Pake.hmac(secret, "encrypt"), "AES"), new GCMParameterSpec(128, iv));
        cipher.updateAAD(aad.getBytes(StandardCharsets.UTF_8));
        byte[] ct = cipher.doFinal(plain);
        byte[] out = new byte[12 + ct.length];
        System.arraycopy(iv, 0, out, 0, 12);
        System.arraycopy(ct, 0, out, 12, ct.length);
        return out;
    }

    static byte[] open(byte[] secret, String aad, byte[] sealed) throws Exception {
        if (sealed.length < 28) throw new IOException("short");
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.DECRYPT_MODE, new SecretKeySpec(Pake.hmac(secret, "encrypt"), "AES"), new GCMParameterSpec(128, sealed, 0, 12));
        cipher.updateAAD(aad.getBytes(StandardCharsets.UTF_8));
        return cipher.doFinal(sealed, 12, sealed.length - 12);
    }

}
