package io.freemytv;

import android.os.Bundle;
import android.os.SystemClock;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;
import org.json.JSONArray;

/** Verify shared requests, fresh retries, and recovery after a failed request. */
public class AddonRequestTests extends TvTest {
    @Override public void onCreate(Bundle args) { super.onCreate(args); start(); }
    @Override public void onStart() {
        Bundle output = new Bundle(); CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1);
        AtomicInteger calls = new AtomicInteger(); JSONArray[] answers = new JSONArray[2]; Throwable[] errors = new Throwable[2];
        java.util.concurrent.Callable<JSONArray> load = () -> { calls.incrementAndGet(); entered.countDown(); release.await(); return new JSONArray().put("fixture"); };
        Thread first = new Thread(() -> { try { answers[0] = AddonStreams.sharedRequest("fixture-request", load); } catch (Throwable error) { errors[0] = error; } });
        Thread second = new Thread(() -> { try { answers[1] = AddonStreams.sharedRequest("fixture-request", load); } catch (Throwable error) { errors[1] = error; } });
        try {
            first.start(); if (!entered.await(2, java.util.concurrent.TimeUnit.SECONDS)) throw new AssertionError("The first request did not start");
            second.start(); long end = SystemClock.elapsedRealtime() + 2000;
            while (second.getState() != Thread.State.WAITING && SystemClock.elapsedRealtime() < end) SystemClock.sleep(10);
            if (calls.get() != 1 || second.getState() != Thread.State.WAITING) throw new AssertionError("Concurrent clients did not share one request");
            release.countDown(); first.join(2000); second.join(2000);
            if (first.isAlive() || second.isAlive() || errors[0] != null || errors[1] != null || answers[0] != answers[1]) throw new AssertionError("The clients did not receive the same response");
            AddonStreams.sharedRequest("fixture-request", () -> { calls.incrementAndGet(); return new JSONArray(); });
            if (calls.get() != 2) throw new AssertionError("A later retry did not make a fresh request");
            try { AddonStreams.sharedRequest("failed-fixture", () -> { throw new Exception("private-test-value"); }); throw new AssertionError("The failed request was accepted"); }
            catch (Exception error) { if (!"network".equals(error.getMessage())) throw new AssertionError("The failure exposed its original message"); }
            if (AddonStreams.sharedRequest("failed-fixture", JSONArray::new) == null) throw new AssertionError("A failed request blocked its retry");
            output.putString("stream", "Passed 5 shared add-on request checks.\n");
        } catch (Throwable error) { output.putString("stream", "FAILED shared requests: " + error.getClass().getSimpleName() + ": " + error.getMessage() + "\n"); }
        finally { release.countDown(); finish(output.getString("stream", "").startsWith("Passed") ? -1 : 0, output); }
    }
}
