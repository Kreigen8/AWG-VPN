package ru.routevpn.app;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/** Never starts a VPN that the user previously stopped. No configs are opened in the receiver. */
public final class RestoreReceiver extends BroadcastReceiver {
    @Override public void onReceive(Context context, Intent intent) {
        if (intent != null && (Intent.ACTION_BOOT_COMPLETED.equals(intent.getAction())
                || Intent.ACTION_MY_PACKAGE_REPLACED.equals(intent.getAction())
                || Intent.ACTION_USER_UNLOCKED.equals(intent.getAction()))) RouteVpnService.restore(context, false);
    }
}
