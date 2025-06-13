package grapheneos.srtpermtests.internet.appthataccessesinternet;

import grapheneos.srtpermtests.internet.appthataccessesinternet.InternetAccessResult;

interface IAccessInternetOnCommand {
    InternetAccessResult accessInternet();

    boolean isConnected();

    boolean getSensorInfo(int sensorType, int sensorListIndex, long timeoutMillis);
}
