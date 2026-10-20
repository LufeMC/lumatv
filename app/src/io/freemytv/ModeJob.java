package io.freemytv;

import android.app.job.JobInfo;
import android.app.job.JobParameters;
import android.app.job.JobScheduler;
import android.app.job.JobService;
import android.content.ComponentName;
import android.content.Context;
import android.os.PersistableBundle;

/**
 * Runs the mode switches in the background (HomeMode). As a job, the system keeps the app's main process running until
 * the switch finishes, even with the screen showing something else. Also the twice-a-day quiet check (DAILY): keeps
 * the TV's debugging approval alive and looks for updates.
 */
public class ModeJob extends JobService {
    static final String APPLY = "apply", VERIFY = "verify", REVERT = "revert", MEMORY = "memory";
    static final String REAPPROVE = "reapprove", REMOTE_KEYS = "remote-keys", DAILY = "daily", UPDATE = "update",
            AFTER_UPDATE = "after-update";
    private static final int DAILY_ID = 2001;

    @Override public boolean onStartJob(JobParameters params) {
        PersistableBundle extras = params.getExtras();
        final String action = extras.getString("action", "");
        final String mode = extras.getString("mode", "");
        final Context app = getApplicationContext();
        new Thread(() -> {
            try {
                switch (action) {
                    case APPLY: HomeMode.applyNow(app, mode); break;
                    case VERIFY: HomeMode.verifyNow(app); break;
                    case REVERT: HomeMode.revertHomeNow(app); break;
                    case MEMORY: HomeMode.measureMemoryNow(app, mode); break;
                    case REAPPROVE: HomeMode.reapproveNow(app); break;
                    case REMOTE_KEYS: HomeMode.remoteKeysNow(app); break;
                    case DAILY:
                        HomeMode.keepAliveNow(app);
                        Updater.automatic(app);
                        break;
                    case UPDATE:
                        if (Updater.CHECK.equals(mode)) Updater.checkNow(app);
                        else Updater.installNow(app, Updater.AUTO.equals(mode));
                        break;
                    case AFTER_UPDATE: Updater.afterUpdate(app); break;
                    default: break;
                }
            } catch (Throwable t) {
                AppLog.w("job " + action + " failed: " + t);
            }
            jobFinished(params, false);
        }, "lumatv-" + action).start();
        return true;
    }

    @Override public boolean onStopJob(JobParameters params) {
        return false;
    }

    /** Schedules a job (one pending per action: a new request replaces the old one). */
    static void schedule(Context c, String action, String mode, long delayMs) {
        JobScheduler scheduler = c.getSystemService(JobScheduler.class);
        if (scheduler == null) return;
        PersistableBundle extras = new PersistableBundle();
        extras.putString("action", action);
        extras.putString("mode", mode == null ? "" : mode);
        JobInfo.Builder job = new JobInfo.Builder(1000 + Math.abs(action.hashCode() % 1000),
                new ComponentName(c, ModeJob.class)).setExtras(extras);
        if (delayMs > 0) job.setMinimumLatency(delayMs);
        job.setOverrideDeadline(delayMs + 2_000);
        scheduler.schedule(job.build());
    }

    /** An automatic update waits for the TV to be idle (screen off or screensaver for a while); Updater checks the hour. */
    static void scheduleIdleUpdate(Context c, long delayMs) {
        JobScheduler scheduler = c.getSystemService(JobScheduler.class);
        if (scheduler == null) return;
        PersistableBundle extras = new PersistableBundle();
        extras.putString("action", UPDATE);
        extras.putString("mode", Updater.AUTO);
        JobInfo.Builder job = new JobInfo.Builder(2002, new ComponentName(c, ModeJob.class)).setExtras(extras)
                .setRequiresDeviceIdle(true).setPersisted(true);
        if (delayMs > 0) job.setMinimumLatency(delayMs);
        scheduler.schedule(job.build());
    }

    /** The quiet check every 12 hours (kept across reboots). Scheduling it again keeps the existing one. */
    static void scheduleDaily(Context c) {
        JobScheduler scheduler = c.getSystemService(JobScheduler.class);
        if (scheduler == null || scheduler.getPendingJob(DAILY_ID) != null) return;
        PersistableBundle extras = new PersistableBundle();
        extras.putString("action", DAILY);
        extras.putString("mode", "");
        scheduler.schedule(new JobInfo.Builder(DAILY_ID, new ComponentName(c, ModeJob.class)).setExtras(extras)
                .setPeriodic(12 * 60 * 60_000L).setPersisted(true)
                .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY).build());
    }
}
