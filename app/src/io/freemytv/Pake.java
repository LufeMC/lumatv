package io.freemytv;

import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * Pairing two TVs with a 4-digit code (SPAKE2 over the RFC 3526 2048-bit group, the order-q subgroup of a safe
 * prime). Someone on the network who watches or even intercepts the pairing learns nothing they could test codes
 * against offline: each attempt is one guess, and the TV showing the code allows only a few. Both TVs end up with the
 * same 256-bit secret, used for every later sync (TvSync).
 */
final class Pake {
    private Pake() {}

    static final BigInteger P = new BigInteger(
            "FFFFFFFFFFFFFFFFC90FDAA22168C234C4C6628B80DC1CD129024E088A67CC74020BBEA63B139B22514A08798E3404DD"
            + "EF9519B3CD3A431B302B0A6DF25F14374FE1356D6D51C245E485B576625E7EC6F44C42E9A637ED6B0BFF5CB6F406B7ED"
            + "EE386BFB5A899FA5AE9F24117C4B1FE649286651ECE45B3DC2007CB8A163BF0598DA48361C55D39A69163FA8FD24CF5F"
            + "83655D23DCA3AD961C62F356208552BB9ED529077096966D670C354E4ABC9804F1746C08CA18217C32905E462E36CE3B"
            + "E39E772C180E86039B2783A2EC07A28FB5C55DF06F4C52C9DE2BCBF6955817183995497CEA956AE515D2261898FA0510"
            + "15728E5A8AACAA68FFFFFFFFFFFFFFFF", 16);
    static final BigInteger Q = P.subtract(BigInteger.ONE).shiftRight(1);
    private static final BigInteger G = BigInteger.valueOf(4);   // a square: generates the order-q subgroup
    private static final BigInteger M = toGroup("LumaTV pairing M"), N = toGroup("LumaTV pairing N");
    private static final SecureRandom RANDOM = new SecureRandom();

    /** One side of a pairing: `initiator` is the TV where the code is typed (A), the other shows it (B). */
    static final class Party {
        final boolean initiator;
        final String idA, idB;
        private final BigInteger w, secret;
        final BigInteger mine;
        private byte[] key;

        Party(boolean initiator, String pin, String idA, String idB) {
            this.initiator = initiator;
            this.idA = idA;
            this.idB = idB;
            w = new BigInteger(1, sha256(("LumaTV pin|" + pin + "|" + idA + "|" + idB).getBytes(StandardCharsets.UTF_8))).mod(Q);
            BigInteger x;
            do { x = new BigInteger(Q.bitLength(), RANDOM); } while (x.signum() == 0 || x.compareTo(Q) >= 0);
            secret = x;
            mine = G.modPow(x, P).multiply((initiator ? M : N).modPow(w, P)).mod(P);
        }

        /** Takes the other side's element; false if it isn't a valid group element. */
        boolean finish(BigInteger theirs) {
            if (!valid(theirs)) return false;
            BigInteger unblind = (initiator ? N : M).modPow(w, P).modInverse(P);
            BigInteger k = theirs.multiply(unblind).mod(P).modPow(secret, P);
            BigInteger x = initiator ? mine : theirs, y = initiator ? theirs : mine;
            key = sha256(("LumaTV pair v1|" + idA + "|" + idB + "|" + x.toString(16) + "|" + y.toString(16) + "|"
                    + k.toString(16) + "|" + w.toString(16)).getBytes(StandardCharsets.UTF_8));
            return true;
        }

        /** Key confirmation: what A sends ("A") and B sends ("B"). */
        byte[] confirm(String who) { return hmac(key, "confirm " + who); }

        /** The secret both TVs keep after a successful pairing. */
        byte[] sharedSecret() { return hmac(key, "sync secret"); }
    }

    static boolean valid(BigInteger e) {
        return e != null && e.compareTo(BigInteger.ONE) > 0 && e.compareTo(P.subtract(BigInteger.ONE)) < 0
                && e.modPow(Q, P).equals(BigInteger.ONE);
    }

    private static BigInteger toGroup(String label) {
        byte[] wide = new byte[288];
        for (int i = 0; i < 9; i++) {
            System.arraycopy(sha256((label + "|" + i).getBytes(StandardCharsets.UTF_8)), 0, wide, i * 32, 32);
        }
        BigInteger h = new BigInteger(1, wide).mod(P);
        return h.multiply(h).mod(P);
    }

    static byte[] sha256(byte[] data) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(data);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    static byte[] hmac(byte[] key, String label) {
        return hmac(key, label.getBytes(StandardCharsets.UTF_8));
    }

    static byte[] hmac(byte[] key, byte[] data) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(key, "HmacSHA256"));
            return mac.doFinal(data);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    /** A random 4-digit code, "0000"–"9999". */
    static String pin() {
        return String.format(java.util.Locale.ROOT, "%04d", RANDOM.nextInt(10_000));
    }
}
