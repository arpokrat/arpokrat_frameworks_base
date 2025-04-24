package grapheneos.srtpermtests.packageinstaller

import android.app.Instrumentation
import grapheneos.test.common.SettingsUtil

object SensorsSettingsUtil {
    /**
     * Unable to access [Settings.Secure.AUTO_GRANT_OTHER_SENSORS_PERMISSION] directly due to it
     * being a @hide API
     */
    private const val AUTO_GRANT_SETTING = "auto_grant_OTHER_SENSORS_perm"

    @JvmStatic
    fun withAutoGrantSensorSetting(
        instrumentation: Instrumentation,
        settingForScope: Boolean,
        scope: SettingsUtil.ThrowableRunnable
    ) {
        SettingsUtil.withSetting(
            instrumentation,
            AUTO_GRANT_SETTING,
            SettingsUtil.SettingDomain.SECURE,
            settingForScope,
            scope
        )
    }

    @JvmStatic
    fun setAutoGrantSensorsSetting(instrumentation: Instrumentation, isEnabled: Boolean?) {
        SettingsUtil.setSetting(
            instrumentation,
            AUTO_GRANT_SETTING,
            SettingsUtil.SettingDomain.SECURE,
            isEnabled
        )
    }

    @JvmStatic
    fun getAutoGrantSensorsSetting(instrumentation: Instrumentation): Boolean? {
        return SettingsUtil.getSetting(
            instrumentation,
            AUTO_GRANT_SETTING,
            SettingsUtil.SettingDomain.SECURE
        )
    }
}
