package com.android.internal.app;

import android.app.admin.DevicePolicyManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import java.util.Objects;

public class DeviceAdminAppHooks {
    static ComponentName pkg;
    static String pkgName;
    static DevicePolicyManager mDPM;
    // Instrumentation#execStartActivity(Context, IBinder, IBinder, Activity, Intent, int, Bundle)
    public static void maybeModifyActivityIntent(Context ctx, Intent i) {
        mDPM = ctx.getSystemService(DevicePolicyManager.class);
        String action = i.getAction();
        if (Objects.equals(action, DevicePolicyManager.ACTION_ADD_DEVICE_ADMIN)) {
            ComponentName pkgComponent = i.getParcelableExtra(
                    DevicePolicyManager.EXTRA_DEVICE_ADMIN, ComponentName.class);

            if (pkgComponent != null) {
                pkg = pkgComponent;
                pkgName = pkgComponent.getPackageName();
            }


            if (pkg == null || pkgName == null) {
                return;
            }

            if (!pkgName.equals(ctx.getPackageName())) {
                return;
            }

            if (!mDPM.isAdminActive(pkg)) {
                i.setAction(action + "_PROMPT");
            }
        }
    }
}
