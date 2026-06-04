package com.datacollector;

import android.app.admin.DeviceAdminReceiver;
import android.content.Context;
import android.content.Intent;
import android.widget.Toast;

/**
 * Device Admin Receiver.
 *
 * Grants the app elevated privileges so it cannot be uninstalled by the user
 * without first deactivating Device Admin in Settings → Security → Device Admin Apps.
 *
 * This does NOT give silent/remote control. The user still chooses to activate
 * it during app setup via a standard Android system dialog.
 */
public class DeviceAdminReceiver extends DeviceAdminReceiver {

    @Override
    public void onEnabled(Context context, Intent intent) {
        Toast.makeText(context,
                "Device Admin enabled — app is now protected.",
                Toast.LENGTH_SHORT).show();
    }

    @Override
    public void onDisabled(Context context, Intent intent) {
        Toast.makeText(context,
                "Device Admin disabled.",
                Toast.LENGTH_SHORT).show();
    }

    @Override
    public CharSequence onDisableRequested(Context context, Intent intent) {
        return "Disabling Device Admin will allow this app to be uninstalled. Are you sure?";
    }
}
