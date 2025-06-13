/*
 * Copyright (C) 2023 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package grapheneos.test.common.notifications;

import android.service.notification.NotificationListenerService;
import android.service.notification.StatusBarNotification;
import android.util.Log;

/**
 * Implementation of {@link NotificationListenerService} for CTS tests.
 *
 * <p>In order to use this service in a test suite, ensure this service is declared in the test
 * suite's AndroidManifest.xml as follows:
 *
 * <pre>{@code
 * <service android:name="grapheneos.test.common.notifications.GtsNotificationListenerService"
 *      android:exported="true"
 *      android:permission="android.permission.BIND_NOTIFICATION_LISTENER_SERVICE">
 *      <intent-filter>
 *          <action android:name="android.service.notification.NotificationListenerService"/>
 *      </intent-filter>
 * </service>
 * }</pre>
 *
 * <p>Consider also disabling the System setting "notification_cooldown_enabled", e.g. adding to
 * AndroidTest.xml:
 *
 * <pre>{@code
 * <option name="set-system-setting" key="notification_cooldown_enabled" value="0" />
 * }</pre>
 */
public class GtsNotificationListenerService extends NotificationListenerService {
    private static final String LOG_TAG = GtsNotificationListenerService.class.getSimpleName();

    private static final Object sLock = new Object();

    private static GtsNotificationListenerService sService;

    @Override
    public void onListenerConnected() {
        Log.i(LOG_TAG, "connected");
        synchronized (sLock) {
            sService = this;
            sLock.notifyAll();
        }
    }

    public static NotificationListenerService getInstance() throws Exception {
        synchronized (sLock) {
            if (sService == null) {
                sLock.wait(5000);
            }

            return sService;
        }
    }

    @Override
    public void onListenerDisconnected() {
        Log.i(LOG_TAG, "disconnected");

        synchronized (sLock) {
            sService = null;
        }
    }
}
