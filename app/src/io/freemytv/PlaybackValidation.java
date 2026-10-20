package io.freemytv;

/** A short placeholder must not replace an expected full episode or movie. */
final class PlaybackValidation {
    static boolean shortPlaceholder(long expected, long actual) {
        return expected >= 10 * 60000L && actual > 0 && actual < 2 * 60000L && actual < expected / 5;
    }
}
