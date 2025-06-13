package grapheneos.srtpermtests.internet.appthataccessesinternet;

import android.Manifest;
import android.app.Service;
import android.content.Intent;
import android.hardware.SensorManager;
import android.net.ConnectivityManager;
import android.net.NetworkCapabilities;
import android.os.IBinder;
import android.os.Process;
import android.util.Log;

import java.net.InetAddress;

public class AccessInternetOnCommand extends Service {
    private static final String TAG = AccessInternetOnCommand.class.getSimpleName();

    private final IAccessInternetOnCommand.Stub mBinder = new IAccessInternetOnCommand.Stub() {
        @Override
        public InternetAccessResult accessInternet() {
            Log.d(TAG, "accessInternet, pid " + Process.myPid());
            var result = AccessInternetOnCommand.this.getPackageManager().checkPermission(
                    Manifest.permission.INTERNET,
                    AccessInternetOnCommand.this.getPackageName()
            );
            Log.d(TAG, "permissions result=" + result);
            var internetResult = new InternetAccessResult();
            try {
                InetAddress.getByName("grapheneos.org");
                internetResult.isSuccessfulAccess = true;
            } catch (Exception e) {
                internetResult.isSuccessfulAccess = false;
                internetResult.exceptionClass = e.getClass().getName();
                internetResult.exceptionMessage = e.getMessage();
            }
            return internetResult;
        }

        @Override
        public boolean isConnected() {
            Log.d(TAG, "isConnected, pid " + Process.myPid());
            final var cm = AccessInternetOnCommand.this.getSystemService(ConnectivityManager.class);
            final var network = cm.getActiveNetwork();
            if (network == null) return false;
            final var caps = cm.getNetworkCapabilities(network);
            if (caps == null) return false;
            return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET);
        }

        @Override
        public boolean getSensorInfo(int sensorType, int sensorListIndex, long timeoutMillis) {
            Log.d(TAG, "getSensorInfo, sensorType = " + sensorType + ", pid " + Process.myPid());
            final var sm = getSystemService(SensorManager.class);
            var sensorList = sm.getSensorList(sensorType);
            if (sensorList == null || sensorListIndex >= sensorList.size()) {
                Log.d(TAG, "getSensorInfo, sensorType = " + sensorType + " has no sensor of index " + sensorListIndex);
                return false;
            }
            var sensor = sensorList.get(sensorListIndex);
            Log.d(TAG, "getSensorInfo, obtained sensor " + sensor.getName());
            var sensorEvent = SensorUtil.getSensorEvent(sm, sensor, timeoutMillis);
            Log.d(TAG, "sensorEvent=" + sensorEvent);
            return sensorEvent != null && sensorEvent.values.length > 0;
        }
    };

    @Override
    public IBinder onBind(Intent intent) {
        return mBinder;
    }

    @Override
    public boolean onUnbind(Intent intent) {
        return true;
    }
}
