package grapheneos.srtpermtests.internet.appthataccessesinternet

import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.util.Log
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull

object SensorUtil {
    private const val TAG = "SensorUtil"

    @JvmStatic
    fun getSensorEvent(
        sensorManager: SensorManager,
        sensor: Sensor,
        timeoutMillis: Long,
    ): SensorEvent? {
        return runBlocking {
            getSensorEventSuspend(sensorManager, sensor, timeoutMillis)
        }
    }

    suspend fun getSensorEventSuspend(
        sensorManager: SensorManager,
        sensor: Sensor,
        timeoutMillis: Long
    ): SensorEvent? {
        return withTimeoutOrNull(timeoutMillis) {
            val sensorEventFlow = callbackFlow<SensorEvent?> {
                val sensorEventListener = object : SensorEventListener {
                    override fun onSensorChanged(event: SensorEvent?) {
                        Log.d(TAG, "onSensorChanged: ${event?.values?.asList()}")
                        channel.trySend(event)
                    }

                    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}
                }
                sensorManager.registerListener(
                    sensorEventListener,
                    sensor,
                    SensorManager.SENSOR_DELAY_FASTEST
                )
                Log.d(TAG, "registered sensor listener")
                awaitClose {
                    sensorManager.unregisterListener(sensorEventListener)
                    Log.d(TAG, "unregistered sensor listener")
                }
            }
            sensorEventFlow.first()
        }
    }
}
