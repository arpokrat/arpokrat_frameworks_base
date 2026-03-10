package android.content.pm.spoofing;

import android.Manifest;
import android.annotation.NonNull;
import android.app.AppOpsManager;
import android.content.pm.GosPackageState;
import android.content.pm.GosPackageStateFlag;
import android.ext.DerivedPackageFlag;
import android.util.Log;

/** @hide */
public class MicSpoofing {

    private static final String TAG = "MicSpoofing";
    private static final boolean VERBOSE_LOGGING = false;

    private static volatile boolean isEnabled;
    private static int gosPackageStateDerivedFlags;

    private MicSpoofing() {
    }

    public static void onGosPackageStateChanged(GosPackageState gosPackageState) {
        boolean shouldBeEnabled = gosPackageState.hasFlag(GosPackageStateFlag.MIC_SPOOFING_ENABLED);
        if (shouldBeEnabled == isEnabled) return;

        if (shouldBeEnabled) {
            gosPackageStateDerivedFlags = gosPackageState.derivedFlags;
        }

        if (VERBOSE_LOGGING) {
            Log.d(TAG, "Changing mic spoofing to " + shouldBeEnabled);
        }

        isEnabled = shouldBeEnabled;
    }

    public static boolean isEnabled() {
        return isEnabled;
    }

    public static boolean shouldSpoofSelfPermissionCheck(@NonNull String permissionName) {
        if (!isEnabled) return false;

        return shouldSpoofPermissionCheckInner(getSpoofablePermissionDflag(permissionName));
    }

    public static boolean shouldSpoofSelfAppOpCheck(int op) {
        if (!isEnabled) return false;

        return op == AppOpsManager.OP_RECORD_AUDIO && shouldSpoofPermissionCheckInner(
                DerivedPackageFlag.HAS_RECORD_AUDIO_DECLARATION);
    }

    public static int getSpoofablePermissionDflag(@NonNull String permissionName) {
        if (permissionName.equals(Manifest.permission.RECORD_AUDIO)) {
            return DerivedPackageFlag.HAS_RECORD_AUDIO_DECLARATION;
        }

        return 0;
    }

    private static boolean shouldSpoofPermissionCheckInner(int permissionDflag) {
        if (permissionDflag == 0) return false;

        return (gosPackageStateDerivedFlags & permissionDflag) != 0;
    }
}
