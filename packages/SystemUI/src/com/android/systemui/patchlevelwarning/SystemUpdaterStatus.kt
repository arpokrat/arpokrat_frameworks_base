package com.android.systemui.patchlevelwarning

import android.content.ComponentName
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.compose.runtime.Immutable

@Immutable
sealed class SystemUpdaterStatus {
    @Immutable
    data class Enabled(val launchComponent: ComponentName) : SystemUpdaterStatus() {
        val launchUpdaterSettingsIntent: Intent
            get() = Intent().setComponent(launchComponent)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)
    }

    @Immutable
    data object Disabled : SystemUpdaterStatus() {
        val launchAppSettingsForEnablingIntent: Intent
            get() = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
                .setData(Uri.fromParts("package", UPDATER_PACKAGE, null))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)
    }

    @Immutable
    data object Missing : SystemUpdaterStatus()
}
