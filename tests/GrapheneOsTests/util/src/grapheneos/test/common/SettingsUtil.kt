package grapheneos.test.common

import android.app.Instrumentation
import android.util.Log
import com.android.compatibility.common.util.SystemUtil

private const val TAG = "SettingsUtil"

object SettingsUtil {
    enum class SettingDomain {
        SECURE, GLOBAL, SYSTEM;

        val nameForShell: String
            get() = this.name.lowercase()
    }

    fun interface ThrowableRunnable {
        @Throws(Exception::class)
        fun run()
    }

    @JvmStatic
    fun withSetting(
        instrumentation: Instrumentation,
        settingName: String,
        settingDomain: SettingDomain,
        settingForScope: Boolean,
        scope: ThrowableRunnable
    ) {
        val prevValue = getSetting(instrumentation, settingName, settingDomain)
        try {
            setSetting(instrumentation, settingName, settingDomain, settingForScope)
            scope.run()
        } finally {
            setSetting(instrumentation, settingName, settingDomain, prevValue)
        }
    }

    @JvmStatic
    fun setSetting(
        instrumentation: Instrumentation,
        settingName: String,
        settingDomain: SettingDomain,
        isEnabled: Boolean?
    ) {
        val nameForShell = settingDomain.nameForShell
        if (isEnabled == null) {
            SystemUtil.runShellCommand(
                instrumentation,
                "settings delete $nameForShell $settingName"
            )
        } else {
            val value = if (isEnabled) "1" else "0"
            SystemUtil.runShellCommand(
                instrumentation,
                "settings put $nameForShell $settingName $value"
            )
        }
    }

    @JvmStatic
    fun getSetting(
        instrumentation: Instrumentation,
        settingName: String,
        settingDomain: SettingDomain,
    ): Boolean? {
        val returnValue = SystemUtil.runShellCommand(
            instrumentation,
            "settings get ${settingDomain.nameForShell} $settingName"
        ).trim()
        Log.d(TAG, "actual returnValue for $settingName is <$returnValue>")
        if (returnValue == "null") {
            return null
        }
        return returnValue == "1" || returnValue == "true"
    }
}