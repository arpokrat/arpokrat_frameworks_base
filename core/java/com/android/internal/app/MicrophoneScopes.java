package com.android.internal.app;

import android.Manifest;
import android.annotation.AnyThread;
import android.app.AppOpsManager;
import android.content.Context;
import android.content.pm.GosPackageState;
import android.content.pm.GosPackageStateFlag;
import android.ext.DerivedPackageFlag;

public class MicrophoneScopes {
    private static volatile boolean isEnabled;
    private static int gosPsDerivedFlags;

    public static boolean isEnabled() {
        return isEnabled;
    }

    @AnyThread
    public static void maybeEnable(Context ctx, GosPackageState ps) {
        synchronized (MicrophoneScopes.class) {
            if (isEnabled) {
                return;
            }

            if (ps.hasFlag(GosPackageStateFlag.MICROPHONE_SCOPES_ENABLED)) {
                gosPsDerivedFlags = ps.derivedFlags;
                isEnabled = true;
            }
        }
    }

    // call only if isEnabled is true
    private static boolean shouldSpoofPermissionCheckInner(int permDflag) {
        if (permDflag == 0) {
            return false;
        }
        return (gosPsDerivedFlags & permDflag) != 0;
    }

    public static boolean shouldSpoofSelfPermissionCheck(String permName) {
        if (!isEnabled) {
            return false;
        }
        return shouldSpoofPermissionCheckInner(getSpoofablePermissionDflag(permName));
    }

    public static boolean shouldSpoofSelfAppOpCheck(int op) {
        if (!isEnabled) {
            return false;
        }
        return shouldSpoofPermissionCheckInner(getSpoofableAppOpPermissionDflag(op));
    }

    public static int getSpoofablePermissionDflag(String permName) {
        switch (permName) {
            case Manifest.permission.RECORD_AUDIO:
                return DerivedPackageFlag.HAS_RECORD_AUDIO_DECLARATION;
            default:
                return 0;
        }
    }

    private static int getSpoofableAppOpPermissionDflag(int op) {
        switch (op) {
            case AppOpsManager.OP_RECORD_AUDIO:
                return DerivedPackageFlag.HAS_RECORD_AUDIO_DECLARATION;
            default:
                return 0;
        }
    }
}
