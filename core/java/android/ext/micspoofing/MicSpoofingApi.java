package android.ext.micspoofing;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.annotation.SystemApi;
import android.app.ActivityThread;
import android.app.Application;
import android.app.ApplicationPackageManager;
import android.content.ComponentName;
import android.content.Intent;
import android.content.pm.GosPackageState;
import android.content.pm.GosPackageStateFlag;
import android.net.Uri;
import android.os.ParcelFileDescriptor;
import android.provider.MediaStore;
import android.util.Log;
import android.util.Slog;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.FileNotFoundException;
import java.io.IOException;

/** @hide */
@SystemApi
public final class MicSpoofingApi {

    private static final String TAG = "MicSpoofingApi";

    private static final String MEDIA_PROVIDER_OPEN_SOURCE_PATH = "mic_spoofing_source";

    public static final int VERSION = 1;

    public static final int MODE_DEFAULT = 0;
    public static final int MODE_CUSTOM_PATH = 1;

    private MicSpoofingApi() {
    }

    @NonNull
    public static Intent createConfigActivityIntent(@NonNull String targetPkg) {
        var intent = new Intent();
        var componentName = ComponentName.createRelative(
                ApplicationPackageManager.PERMISSION_CONTROLLER_RESOURCE_PACKAGE,
                ".micspoofing.MicSpoofingActivity"
        );
        intent.setComponent(componentName);
        intent.putExtra(Intent.EXTRA_PACKAGE_NAME, targetPkg);

        return intent;
    }

    @NonNull
    public static byte[] buildDefaultConfig() {
        return new byte[]{VERSION, MODE_DEFAULT};
    }

    @NonNull
    public static byte[] buildCustomPathConfig(@NonNull String path) {
        var byteArrayOutputStream = new ByteArrayOutputStream(2 + path.length() * 3);
        var dataOutputStream = new DataOutputStream(byteArrayOutputStream);
        try {
            dataOutputStream.writeByte(VERSION);
            dataOutputStream.writeByte(MODE_CUSTOM_PATH);
            dataOutputStream.writeUTF(path);
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
        return byteArrayOutputStream.toByteArray();
    }

    public static int getSourceMode(@Nullable byte[] config) {
        if (config == null || config.length < 2) {
            return MODE_DEFAULT;
        }

        if (config[0] != VERSION) {
            return MODE_DEFAULT;
        }

        return config[1] & 0xFF;
    }

    @Nullable
    public static String getCustomAudioPathForApp(
            @NonNull String packageName,
            int userId
    ) {
        var gosPs = GosPackageState.get(packageName, userId);
        if (!gosPs.hasFlag(GosPackageStateFlag.MIC_SPOOFING_ENABLED)) {
            Log.d(TAG, "getCustomAudioPathForApp: MIC_SPOOFING_ENABLED not set for "
                    + packageName + " userId " + userId);
            return null;
        }

        return MicSpoofingApi.getPath(gosPs.micSpoofingConfig);
    }

    @Nullable
    public static String getPath(@Nullable byte[] config) {
        if (config == null || config.length < 2) {
            return null;
        }
        if (config[0] != VERSION) {
            return null;
        }
        if ((config[1] & 0xFF) != MODE_CUSTOM_PATH) {
            return null;
        }

        var byteArrayInputStream = new ByteArrayInputStream(config, 2, config.length - 2);
        var dataInputStream = new DataInputStream(byteArrayInputStream);

        try {
            return dataInputStream.readUTF();
        } catch (IOException e) {
            Slog.w(TAG, "Failed to read custom path from config", e);
            return null;
        }
    }

    @Nullable
    public static ParcelFileDescriptor openCustomSourceFdForSelf() {
        Application application = ActivityThread.currentApplication();
        if (application == null) {
            Log.w(TAG, "openCustomSourceFdForSelf: currentApplication is null");
            return null;
        }

        Uri uri = MediaStore.AUTHORITY_URI
                .buildUpon()
                .appendPath(MEDIA_PROVIDER_OPEN_SOURCE_PATH)
                .build();
        try {
            return application.getContentResolver().openFileDescriptor(uri, "r");
        } catch (FileNotFoundException e) {
            Log.w(TAG, "openCustomSourceFdForSelf: unable to open " + uri, e);
            return null;
        } catch (SecurityException e) {
            Log.w(TAG, "openCustomSourceFdForSelf: access denied for " + uri, e);
            return null;
        }
    }
}
