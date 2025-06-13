package grapheneos.srtpermtests

import android.Manifest
import android.app.Instrumentation
import android.content.Context
import android.content.pm.PackageManager
import android.hardware.Sensor
import android.hardware.SensorManager
import android.os.SystemClock
import android.service.notification.StatusBarNotification
import android.util.Log
import androidx.test.filters.FlakyTest
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.UiDevice
import com.android.internal.messages.nano.SystemMessageProto
import grapheneos.srtpermtests.AppThatAccessesInternetRule.Companion.TEST_APP_PKG
import grapheneos.srtpermtests.InternetPermissionTest.Companion.appThatAccessesInternetRule
import grapheneos.test.common.DeadObjectExceptionRetryRule
import grapheneos.test.common.notifications.GtsNotificationListenerHelperRule
import grapheneos.test.common.notifications.GtsNotificationListenerServiceUtils
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.After
import org.junit.Assume
import org.junit.Before
import org.junit.ClassRule
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.Parameterized

private const val TAG = "SensorsPermissionTest"
private const val SENSORS_TEST_TIMEOUT_MILLIS = 4_000L
private const val SENSORS_FAIL_TEST_TIMEOUT_MILLIS = 2_000L

/**
 * Based on
 * packages/modules/Permission/tests/cts/permission/src/android/permission/cts/LocationAccessCheckTest.java
 *
 * Runs code in the test app via IPC (AIDL). Cannot do test code in instrumentation app, since
 * changing runtime permissions can stop the instrumentation app.
 *
 * The service binding for IPC is managed by the [ClassRule], [appThatAccessesInternetRule]
 */
@RunWith(Parameterized::class)
@FlakyTest
class SensorsPermissionTest(private val sensorId: Int) {
    private val mContext: Context = androidx.test.InstrumentationRegistry.getTargetContext()
    val mInstrumentation: Instrumentation = InstrumentationRegistry.getInstrumentation()
    val mPackageManager: PackageManager = mContext.packageManager
    val uiDevice: UiDevice = UiDevice.getInstance(mInstrumentation)

    companion object {
        @JvmStatic
        @Parameterized.Parameters(name = "{index}: sensorId={0}")
        fun sensorsToTest(): List<Array<*>> {
            return sequenceOf(
                Sensor.TYPE_ACCELEROMETER,
                Sensor.TYPE_ACCELEROMETER_UNCALIBRATED,
                Sensor.TYPE_ACCELEROMETER_LIMITED_AXES,
                Sensor.TYPE_ACCELEROMETER_LIMITED_AXES_UNCALIBRATED,
                Sensor.TYPE_GRAVITY,
                Sensor.TYPE_GYROSCOPE,
                Sensor.TYPE_GYROSCOPE_UNCALIBRATED,
                Sensor.TYPE_LINEAR_ACCELERATION,
                Sensor.TYPE_ROTATION_VECTOR,
                // Emits no events; uses TriggerEventListener (not covered atm; seems more
                // complicated)
                // Sensor.TYPE_SIGNIFICANT_MOTION,

                // Apparently emulator is missing this sensor?
                Sensor.TYPE_STEP_COUNTER,

                // Emits no events?
                // Also must declare the ACTIVITY_RECOGNITION permission to use on devices running
                // Android 10 (API level 29) or higher.
                // Sensor.TYPE_STEP_DETECTOR,

                // Position sensors
                Sensor.TYPE_GAME_ROTATION_VECTOR,
                Sensor.TYPE_GEOMAGNETIC_ROTATION_VECTOR,
                Sensor.TYPE_MAGNETIC_FIELD,
                Sensor.TYPE_MAGNETIC_FIELD_UNCALIBRATED,
                Sensor.TYPE_ORIENTATION,
                Sensor.TYPE_PROXIMITY,

                // Environment sensors
                Sensor.TYPE_AMBIENT_TEMPERATURE,
                Sensor.TYPE_LIGHT,
                Sensor.TYPE_PRESSURE,
                Sensor.TYPE_RELATIVE_HUMIDITY,
                Sensor.TYPE_TEMPERATURE, // deprecated
            ).map { arrayOf(it) }.toList()
        }

        @ClassRule
        @JvmField
        val appThatAccessesInternetRule = AppThatAccessesInternetRule()

        private suspend fun bindService() = appThatAccessesInternetRule.bindService()

        private fun wakeUpAndDismissKeyguard() {
            appThatAccessesInternetRule.wakeUpAndDismissKeyguard()
        }
    }

    @get:Rule
    val ctsNotificationListenerHelper = GtsNotificationListenerHelperRule(mContext)

    @get:Rule
    val deadObjectRetryRule = DeadObjectExceptionRetryRule(retryCount = 2)

    private var lastTimeNotificationPosted = 0L

    @Before
    fun beforeEachTest() {
        mInstrumentation.uiAutomation.grantRuntimePermission(
            TEST_APP_PKG,
            Manifest.permission.OTHER_SENSORS
        )
        wakeUpAndDismissKeyguard()

        val sm = mContext.getSystemService(SensorManager::class.java)
        Assume.assumeTrue(
            "default sensor for $sensorId not available on device",
            sm.getDefaultSensor(sensorId) != null
        )
    }

    @After
    fun afterEachTest() {
        appThatAccessesInternetRule.unbindService()
    }

    @Test
    fun sensors_granted_get_success() = runTest {
        val aidlService = bindService()
        val sensorsList: List<Sensor> = mContext.getSystemService(SensorManager::class.java)
            .getSensorList(sensorId);
        for (sensorIndex in sensorsList.indices) {
            assertTrue(
                aidlService.getSensorInfo(sensorId, sensorIndex, SENSORS_TEST_TIMEOUT_MILLIS)
            )
        }
    }

    @Test
    fun sensors_denied_get_fail_with_notif() = runTest {
        GtsNotificationListenerServiceUtils.cancelNotification(
            "android",
            SystemMessageProto.SystemMessage.NOTE_MISSING_PERMISSION_OTHER_SENSORS
        )

        mInstrumentation.uiAutomation.revokeRuntimePermission(
            TEST_APP_PKG,
            Manifest.permission.OTHER_SENSORS
        )

        val aidlService = bindService()
        val sensorsList: List<Sensor> = mContext.getSystemService(SensorManager::class.java)
            .getSensorList(sensorId);
        for (sensorIndex in sensorsList.indices) {
            val actualSensor = sensorsList[sensorIndex]
            val sensorInfoPresent = aidlService.getSensorInfo(
                sensorId,
                sensorIndex,
                SENSORS_FAIL_TEST_TIMEOUT_MILLIS
            )
            assertFalse(sensorInfoPresent, "expected getSensorInfo to fail/timeout when OTHER_SENSORS denied, for sensor ${actualSensor.name}")

            // the notification has a cooldown, so we only check it for some subset of sensors
            val timestamp = SystemClock.uptimeMillis()
            if (lastTimeNotificationPosted != 0L && timestamp - lastTimeNotificationPosted < 30_000L) {
                // note: the notif isn't meant to be shown if OTHER_SENSORS explicitly denied by user, but
                // mInstrumentation.uiAutomation.revokeRuntimePermission doesn't seem to treat it that way
                val notif: StatusBarNotification? = withTimeoutOrNull(6000) {
                    while (isActive) {
                        GtsNotificationListenerServiceUtils.getNotificationForPackageAndId(
                            "android",
                            SystemMessageProto.SystemMessage.NOTE_MISSING_PERMISSION_OTHER_SENSORS,
                            true
                        )?.let { return@withTimeoutOrNull it }
                            ?: delay(100)
                    }
                    null
                }
                assertNotNull(
                    notif,
                    "missing notification for when sensors access denied by OTHER_SENSORS permission, for sensor ${actualSensor.name}"
                )
                // only update timestamp if notification successful
                lastTimeNotificationPosted = timestamp
            } else {
                Log.d(TAG, "skipping notification check due to recent notification")
            }
        }
    }
}
