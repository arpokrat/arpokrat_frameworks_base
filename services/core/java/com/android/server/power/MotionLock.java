package com.android.server.power;

import android.content.ContentResolver;
import android.content.Context;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.os.SystemClock;
import android.os.UserHandle;
import android.os.Handler;
import android.provider.Settings;
import android.util.Slog;
import android.database.ContentObserver;
import android.widget.Toast;
import com.android.server.LocalServices;
import com.android.server.wm.WindowManagerInternal;

/**
 * Handles motion-based device locking functionality.
 * Monitors device acceleration and triggers device lock when sudden motion is detected.
 */
public class MotionLock {
    private static final String TAG = "MotionLock";
    private static final String MOTION_LOCK_ENABLED_SETTING_KEY = "motion_lock_enabled";
    private static final String MOTION_LOCK_SENSITIVITY_SETTING_KEY = "motion_lock_sensitivity";
    private static final int SENSITIVITY_LOW = 0;
    private static final int SENSITIVITY_MEDIUM = 1;
    private static final int SENSITIVITY_HIGH = 2;
    private static final float THRESHOLD_LOW = 16.0f;
    private static final float THRESHOLD_MEDIUM = 21.0f;
    private static final float THRESHOLD_HIGH = 30.0f;
    // EMA smoothing factor for ~600ms window
    private static final float EMA_ALPHA = 0.065f;

    private final PowerManagerService mPowerManagerService;
    private final Context mContext;
    private final SensorManager mSensorManager;
    private final Sensor mAccelerometerSensor;
    private SensorEventListener mSensorListener;
    private boolean mIsEnabled;
    private int mSensitivity;
    private long mLastLockTime;
    private float mEma = 0;

    private final ContentObserver mSettingsObserver = new ContentObserver(new Handler()) {
        @Override
        public void onChange(boolean selfChange) {
            updateSettings();
        }
    };

    public MotionLock(PowerManagerService powerManagerService, SensorManager sensorManager, Sensor accelerometerSensor) {
        mPowerManagerService = powerManagerService;
        mContext = powerManagerService.getContext();
        mSensorManager = sensorManager;
        mAccelerometerSensor = accelerometerSensor;
        mIsEnabled = false;
        mSensitivity = SENSITIVITY_MEDIUM;
        mLastLockTime = 0;

        // Register for settings changes
        ContentResolver resolver = mContext.getContentResolver();
        resolver.registerContentObserver(Settings.Secure.getUriFor(MOTION_LOCK_ENABLED_SETTING_KEY), false, mSettingsObserver, UserHandle.USER_SYSTEM);
        resolver.registerContentObserver(Settings.Secure.getUriFor(MOTION_LOCK_SENSITIVITY_SETTING_KEY), false, mSettingsObserver, UserHandle.USER_SYSTEM);
    }

    public void startMonitoring() {
        if (mSensorListener == null) {
            mSensorListener = new SensorEventListener() {
                @Override
                public void onSensorChanged(SensorEvent event) {
                    if (!mIsEnabled) return;
                    float x = event.values[0];
                    float y = event.values[1];
                    float z = event.values[2];
                    float magnitude = (float) Math.sqrt(x * x + y * y + z * z);

                    // Update EMA
                    mEma = EMA_ALPHA * magnitude + (1 - EMA_ALPHA) * mEma;

                    float threshold = getThresholdForSensitivity(mSensitivity);
                    Slog.d(TAG, "MotionLock EMA=" + mEma + ", threshold=" + threshold + ", enabled=" + mIsEnabled);

                    if (mEma > threshold) {
                        long now = SystemClock.uptimeMillis();
                        if (now - mLastLockTime > 6000) { // Prevent rapid re-locking
                            mLastLockTime = now;
                            lockDevice();
                        }
                    }
                }

                @Override
                public void onAccuracyChanged(Sensor sensor, int accuracy) {
                    // Not used
                }
            };
        }
        mSensorManager.registerListener(mSensorListener, mAccelerometerSensor, SensorManager.SENSOR_DELAY_NORMAL);
        updateSettings();
    }

    public void stopMonitoring() {
        if (mSensorListener != null) {
            mSensorManager.unregisterListener(mSensorListener);
        }
        // Unregister settings observer
        mContext.getContentResolver().unregisterContentObserver(mSettingsObserver);
    }

    private float getThresholdForSensitivity(int sensitivity) {
        switch (sensitivity) {
            case SENSITIVITY_LOW:
                return THRESHOLD_HIGH;    // Least sensitive (hardest to trigger)
            case SENSITIVITY_MEDIUM:
                return THRESHOLD_MEDIUM; // Middle
            case SENSITIVITY_HIGH:
                return THRESHOLD_LOW;    // Most sensitive (easiest to trigger)
            default:
                return THRESHOLD_MEDIUM;
        }
    }

    private void updateSettings() {
        ContentResolver resolver = mContext.getContentResolver();
        mIsEnabled = Settings.Secure.getIntForUser(resolver, MOTION_LOCK_ENABLED_SETTING_KEY, 0, UserHandle.USER_SYSTEM) == 1;
        mSensitivity = Settings.Secure.getIntForUser(resolver, MOTION_LOCK_SENSITIVITY_SETTING_KEY, SENSITIVITY_MEDIUM, UserHandle.USER_SYSTEM);
    }

    private void lockDevice() {
        Slog.d(TAG, "Motion lock triggered, locking device.");
        WindowManagerInternal wmi = LocalServices.getService(WindowManagerInternal.class);
        if (wmi != null) {
            wmi.lockNow();
        } else {
            Slog.w(TAG, "WindowManagerInternal not available, cannot lock device.");
        }
    }
} 