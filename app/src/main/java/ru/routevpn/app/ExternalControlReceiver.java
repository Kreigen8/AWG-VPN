package ru.routevpn.app;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/** Exported only behind CONTROL_TUNNELS. No configs, keys or profile lists are returned. */
public final class ExternalControlReceiver extends BroadcastReceiver {
    private static final ThreadPoolExecutor COMMANDS = new ThreadPoolExecutor(1, 1, 30, TimeUnit.SECONDS,
            new ArrayBlockingQueue<>(8), action -> {
                Thread thread = new Thread(action, "ExternalVpnControl"); thread.setDaemon(true); return thread;
            });
    static { COMMANDS.allowCoreThreadTimeOut(true); }
    @Override public void onReceive(Context context, Intent intent) {
        if (!new ConnectionPreferences(context).externalControl()) {
            if (isOrderedBroadcast()) setResult(ExternalControl.Result.DISABLED.code, "DISABLED", null);
            return;
        }
        boolean ordered = isOrderedBroadcast();
        long generation = new ConnectionPreferences(context).externalControlGeneration();
        PendingResult pending = goAsync();
        Context app = context.getApplicationContext();
        try {
            COMMANDS.execute(() -> {
                try {
                    ExternalControl.Result result;
                    try { result = new ExternalControl(app).handle(intent, generation); }
                    catch (RuntimeException unavailable) { result = ExternalControl.Result.UNAVAILABLE; }
                    if (ordered) pending.setResult(result.code, result.name(), null);
                } finally { pending.finish(); }
            });
        } catch (RejectedExecutionException full) {
            if (ordered) pending.setResult(ExternalControl.Result.BUSY.code, "BUSY", null);
            pending.finish();
        }
    }
}
