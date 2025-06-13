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
package grapheneos.srtpermtests.packageinstaller;

import static android.app.PendingIntent.FLAG_MUTABLE;
import static android.app.PendingIntent.FLAG_UPDATE_CURRENT;
import static android.content.Intent.FLAG_ACTIVITY_CLEAR_TASK;
import static android.content.Intent.FLAG_ACTIVITY_NEW_TASK;

import static com.android.compatibility.common.util.SystemUtil.runWithShellPermissionIdentity;
import static com.android.server.pm.shortcutmanagertest.ShortcutManagerTestUtils.getDefaultLauncher;
import static com.android.server.pm.shortcutmanagertest.ShortcutManagerTestUtils.setDefaultLauncher;

import static com.google.common.truth.Truth.assertThat;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assume.assumeTrue;

import android.Manifest;
import android.app.Activity;
import android.app.Instrumentation;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.IntentSender;
import android.content.pm.LauncherApps;
import android.content.pm.LauncherApps.ArchiveCompatibilityParams;
import android.content.pm.PackageInstaller;
import android.content.pm.PackageInstaller.UnarchivalState;
import android.content.pm.PackageManager;
import android.content.pm.PackageManager.NameNotFoundException;
import android.os.Handler;
import android.os.Looper;
import android.platform.test.flag.junit.CheckFlagsRule;
import android.platform.test.flag.junit.DeviceFlagsValueProvider;
import android.text.TextUtils;
import android.util.Log;

import androidx.annotation.CallSuper;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.test.platform.app.InstrumentationRegistry;
import androidx.test.uiautomator.By;
import androidx.test.uiautomator.SearchCondition;
import androidx.test.uiautomator.UiDevice;
import androidx.test.uiautomator.UiObject2;
import androidx.test.uiautomator.Until;

import com.android.compatibility.common.util.AppOpsUtils;
import com.android.compatibility.common.util.FeatureUtil;
import com.android.compatibility.common.util.SystemUtil;
import com.android.cts.install.lib.InstallUtils;
import com.android.cts.install.lib.LocalIntentSender;

import org.junit.After;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Rule;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import grapheneos.test.common.SettingsUtil;

/**
 * Based on cts/tests/tests/packageinstaller/uninstall/src/android/packageinstaller/uninstall/cts/ArchiveTest.java
 * <p>
 * Tests that the granted states of special runtime permissions are preserved after unarchiving.
 */
public abstract class BaseInstallerTest {
    private static final String LOG_TAG = BaseInstallerTest.class.getSimpleName();

    protected static final String SYSTEM_PACKAGE_NAME = "android";

    protected static final long TIMEOUT_MS = 30000;

    protected static CompletableFuture<Integer> sUnarchiveId;
    protected static CompletableFuture<String> sUnarchiveReceiverPackageName;
    protected static CompletableFuture<Boolean> sUnarchiveReceiverAllUsers;
    protected static CompletableFuture<Integer> sInstallResult;
    protected static CompletableFuture<String> sInstallResultMessage;

    protected Instrumentation mInstrumentation;
    protected Context mContext;
    protected UiDevice mUiDevice;
    protected PackageManager mPackageManager;
    protected PackageInstaller mPackageInstaller;
    protected LauncherApps mLauncherApps;
    protected String mDefaultHome;

    @Rule
    public final CheckFlagsRule mCheckFlagsRule = DeviceFlagsValueProvider.createCheckFlagsRule();

    @Before
    @CallSuper
    public void setup() throws Exception {
        assumeTrue("Form factor is not supported", isFormFactorSupported());
        Instrumentation instrumentation = InstrumentationRegistry.getInstrumentation();
        mInstrumentation = instrumentation;
        mContext = instrumentation.getTargetContext();
        mPackageManager = mContext.getPackageManager();
        mPackageInstaller = mPackageManager.getPackageInstaller();
        mLauncherApps = mContext.getSystemService(LauncherApps.class);
        mContext.getPackageManager().setComponentEnabledSetting(
                new ComponentName(mContext, UnarchiveBroadcastReceiver.class),
                PackageManager.COMPONENT_ENABLED_STATE_ENABLED, PackageManager.DONT_KILL_APP);

        // Unblock UI
        mUiDevice = UiDevice.getInstance(instrumentation);
        if (!mUiDevice.isScreenOn()) {
            mUiDevice.wakeUp();
        }
        mUiDevice.executeShellCommand("wm dismiss-keyguard");
        AppOpsUtils.reset(mContext.getPackageName());
        sUnarchiveId = new CompletableFuture<>();
        sUnarchiveReceiverPackageName = new CompletableFuture<>();
        sUnarchiveReceiverAllUsers = new CompletableFuture<>();
        sInstallResult = new CompletableFuture<>();
        sInstallResultMessage = new CompletableFuture<>();
        mDefaultHome = getDefaultLauncher(instrumentation);
        ArchiveCompatibilityParams options = new ArchiveCompatibilityParams();
        options.setEnableUnarchivalConfirmation(false);
        mLauncherApps.setArchiveCompatibility(options);
        // Prepare device to same state to make tests more independent.
        prepareDevice();
        for (final String pkg : getTestAppPackageNames()) {
            abandonPendingUnarchivalSessions(pkg);
        }
    }

    protected abstract Set<String> getTestAppPackageNames();

    protected void withShellPermissionIdentity(SettingsUtil.ThrowableRunnable runnable)
            throws Exception {
        mInstrumentation.getUiAutomation().adoptShellPermissionIdentity();
        try {
            runnable.run();
        } finally {
            mInstrumentation.getUiAutomation().dropShellPermissionIdentity();
        }
    }

    @After
    @CallSuper
    public void tearDown() {
        // uninstallPackage(ARCHIVE_APP_PACKAGE_NAME);
        for (final String pkg : getTestAppPackageNames()) {
            uninstallPackage(pkg);
        }
        if (mDefaultHome != null) {
            setDefaultLauncher(InstrumentationRegistry.getInstrumentation(), mDefaultHome);
        }
    }

    protected void uninstallPackage(String packageName) {
        SystemUtil.runShellCommand(
                String.format("pm uninstall %s", packageName));
    }

    protected void dumpWindowHierarchy() throws InterruptedException, IOException {
        ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
        mUiDevice.dumpWindowHierarchy(outputStream);
        String windowHierarchy = outputStream.toString(StandardCharsets.UTF_8.name());

        Log.w(LOG_TAG, "Window hierarchy:");
        for (String line : windowHierarchy.split("\n")) {
            Thread.sleep(10);
            Log.w(LOG_TAG, line);
        }
    }

    protected UiObject2 waitFor(SearchCondition<UiObject2> condition)
            throws IOException, InterruptedException {
        final long OneSecond = TimeUnit.SECONDS.toMillis(1);
        final long start = System.currentTimeMillis();
        while (System.currentTimeMillis() - start < TIMEOUT_MS) {
            try {
                var result = mUiDevice.wait(condition, OneSecond);
                if (result == null) {
                    continue;
                }
                return result;
            } catch (Throwable e) {
                Thread.sleep(OneSecond);
            }
        }
        dumpWindowHierarchy();
        return null;
    }

    protected int getExpectedPermissionResult(boolean isExpected) {
        return isExpected ? PackageManager.PERMISSION_GRANTED : PackageManager.PERMISSION_DENIED;
    }

    protected void archiveThenMakeUnarchiveRequest(final String packageName) throws Exception {
        LocalIntentSender archiveSender = new LocalIntentSender();
        runWithShellPermissionIdentity(
                () -> {
                    mPackageInstaller.requestArchive(packageName,
                            archiveSender.getIntentSender());
                    Intent archiveIntent = archiveSender.getResult();
                    assertThat(archiveIntent.getIntExtra(PackageInstaller.EXTRA_STATUS,
                            -100)).isEqualTo(
                            PackageInstaller.STATUS_SUCCESS);
                },
                Manifest.permission.DELETE_PACKAGES);

        SessionListener sessionListener = new SessionListener();
        mPackageInstaller.registerSessionCallback(sessionListener,
                new Handler(Looper.getMainLooper()));

        LocalIntentSender unarchiveSender = new LocalIntentSender();
        mPackageInstaller.requestUnarchive(packageName,
                unarchiveSender.getIntentSender());
        Intent unarchiveIntent = unarchiveSender.pollResult(5, TimeUnit.SECONDS);
        assertThat(unarchiveIntent.getStringExtra(PackageInstaller.EXTRA_PACKAGE_NAME)).isEqualTo(
                packageName);
        assertThat(unarchiveIntent.getIntExtra(PackageInstaller.EXTRA_UNARCHIVE_STATUS,
                -100)).isEqualTo(
                PackageInstaller.STATUS_PENDING_USER_ACTION);

        Intent unarchiveExtraIntent = unarchiveIntent.getParcelableExtra(Intent.EXTRA_INTENT,
                Intent.class);
        unarchiveExtraIntent.addFlags(FLAG_ACTIVITY_CLEAR_TASK | FLAG_ACTIVITY_NEW_TASK);
        prepareDevice();
        mContext.startActivity(unarchiveExtraIntent);
        mUiDevice.waitForIdle();

        assertThat(waitFor(Until.findObject(By.textContains("Restore")))).isNotNull();

        UiObject2 clickableView = mUiDevice.findObject(By.res(SYSTEM_PACKAGE_NAME, "button1"));
        if (clickableView == null) {
            Assert.fail("Restore button not shown");
        }
        clickableView.click();
    }

    protected void completeUnarchiveRequest(final String packageName, final String apkPath,
            int unarchiveId) throws NameNotFoundException, IOException {
        // Complete the unarchive request by installing the app back. Assert that the installation
        // goes through without any additional confirmation dialog.
        mPackageInstaller.reportUnarchivalState(
                UnarchivalState.createOkState(unarchiveId));
        PackageInstaller.SessionParams params = new PackageInstaller.SessionParams(
                PackageInstaller.SessionParams.MODE_FULL_INSTALL);
        params.setAppPackageName(packageName);
        final int sessionId = mPackageInstaller.createSession(params);
        assertThat(sessionId).isEqualTo(unarchiveId);
        PackageInstaller.Session session = mPackageInstaller.openSession(sessionId);
        File apkFile = new File(apkPath);
        try (OutputStream os = session.openWrite("base.apk", 0, apkFile.length());
             InputStream is = new FileInputStream(apkFile)) {
            writeFullStream(is, os, apkFile.length());
        }
        var installResultReceiver = new InstallResultReceiver();
        session.commit(installResultReceiver.getIntentSender(mContext));
    }

    protected void installApkByInstallerSession(final TestApk testApk) throws Exception {
        withShellPermissionIdentity(() -> {
            installApkByInstallerSession(testApk.getPackageName(), testApk.getApkPath(),
                    PackageInstaller.STATUS_SUCCESS, null);
        });
    }

    private void installApkByInstallerSession(final String packageName, final String apk,
            @Nullable final Integer expectedStatus,
            @Nullable final String expectedMsg) throws Exception {
        final PackageInstaller installer = mPackageInstaller;
        final PackageInstaller.SessionParams params = new PackageInstaller.SessionParams(
                PackageInstaller.SessionParams.MODE_FULL_INSTALL);
        params.setAppPackageName(packageName);
        params.setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED);
        // Allow apps to be updated
        var replaceExistingFlag = 0x00000002;
        InstallUtils.mutateInstallFlags(params, replaceExistingFlag);
        // params.setAutoInstallDependenciesEnabled(enableAutoInstallDependencies);

        final int sessionId = installer.createSession(params);
        PackageInstaller.Session session = installer.openSession(sessionId);

        File file = new File(apk);
        try (OutputStream os = session.openWrite("test", 0, file.length());
             InputStream is = new FileInputStream(file)) {
            writeFullStream(is, os, file.length());
        }

        LocalIntentSender unarchiveSender = new LocalIntentSender();
        session.commit(unarchiveSender.getIntentSender());
        if (expectedStatus != null) {
            Intent unarchiveIntent = unarchiveSender.pollResult(10, TimeUnit.SECONDS);
            assertThat(unarchiveIntent.getIntExtra(PackageInstaller.EXTRA_STATUS,
                    Integer.MIN_VALUE)).isEqualTo(expectedStatus);
        }
        /*
        session.commit(new IntentSender((IIntentSender) new IIntentSender.Stub() {
            @Override
            public void send(int code, Intent intent, String resolvedType,
                    IBinder allowlistToken, IIntentReceiver finishedReceiver,
                    String requiredPermission, Bundle options) {
                status.complete(
                        intent.getIntExtra(PackageInstaller.EXTRA_STATUS, Integer.MIN_VALUE));
                statusMessage.complete(
                        intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE));
            }
        }));
        */
    }

    protected static void writeFullStream(InputStream inputStream, OutputStream outputStream,
                                        long expected)
            throws IOException {
        byte[] buffer = new byte[1024];
        long total = 0;
        int length;
        while ((length = inputStream.read(buffer)) != -1) {
            outputStream.write(buffer, 0, length);
            total += length;
        }
        if (expected > 0) {
            assertThat(total).isEqualTo(expected);
        }
    }

    protected void prepareDevice() throws Exception {
        mUiDevice.waitForIdle();
        // wake up the screen
        mUiDevice.wakeUp();
        // unlock the keyguard or the expected window is by systemui or other alert window
        mUiDevice.pressMenu();
        // dismiss the system alert window for requesting permissions
        mUiDevice.pressBack();
        // return to home/launcher to prevent from being obscured by systemui or other alert window
        mUiDevice.pressHome();
        // Wait for device idle
        mUiDevice.waitForIdle();
    }

    protected void installPackage(@NonNull String path) {
        installPackage(path, mContext.getPackageName());
    }

    protected void installPackage(@NonNull String path, @NonNull String installerPackageName) {
        // note: using -g will grant all requested permissions, including OTHER_SENSORS (even
        // if auto grant OTHER_SENSORS is disabled)
        assertEquals("Success\n", SystemUtil.runShellCommand(
                String.format("pm install -r -i %s -t -g %s", installerPackageName, path)));
    }

    protected boolean isInstalled(String packageName) {
        Log.d(LOG_TAG, "Testing if package " + packageName + " is installed for user "
                + mContext.getUser());
        try {
            mContext.getPackageManager().getPackageInfo(packageName, /* flags= */ 0);
            return true;
        } catch (NameNotFoundException e) {
            Log.v(LOG_TAG, "Package " + packageName + " not installed for user "
                    + mContext.getUser() + ": " + e);
            return false;
        }
    }

    protected void abandonPendingUnarchivalSessions(final String appPackageName) {
        List<PackageInstaller.SessionInfo> sessions = mPackageInstaller.getAllSessions();
        for (PackageInstaller.SessionInfo session : sessions) {
            if (TextUtils.equals(appPackageName, session.getAppPackageName())
                    && TextUtils.equals(mContext.getPackageName(),
                        session.getInstallerPackageName())) {
                // The test app cannot abandon draft sessions
                try {
                    mPackageInstaller.abandonSession(session.getSessionId());
                } catch (SecurityException ignored) {
                }
            }
        }
    }

    private static boolean isFormFactorSupported() {
        return !FeatureUtil.isArc()
                && !FeatureUtil.isAutomotive()
                && !FeatureUtil.isTV()
                && !FeatureUtil.isWatch()
                && !FeatureUtil.isVrHeadset();
    }

    public static class UnarchiveBroadcastReceiver extends BroadcastReceiver {

        @Override
        public void onReceive(Context context, Intent intent) {
            if (!TextUtils.equals(Intent.ACTION_UNARCHIVE_PACKAGE, intent.getAction())) {
                return;
            }
            if (sUnarchiveId == null) {
                sUnarchiveId = new CompletableFuture<>();
            }
            sUnarchiveId.complete(intent.getIntExtra(PackageInstaller.EXTRA_UNARCHIVE_ID, -1));
            if (sUnarchiveReceiverPackageName == null) {
                sUnarchiveReceiverPackageName = new CompletableFuture<>();
            }
            sUnarchiveReceiverPackageName.complete(
                    intent.getStringExtra(PackageInstaller.EXTRA_UNARCHIVE_PACKAGE_NAME));
            if (sUnarchiveReceiverAllUsers == null) {
                sUnarchiveReceiverAllUsers = new CompletableFuture<>();
            }
            sUnarchiveReceiverAllUsers.complete(
                    intent.getBooleanExtra(PackageInstaller.EXTRA_UNARCHIVE_ALL_USERS,
                            true /* defaultValue */));
        }
    }

    protected static class InstallResultReceiver extends BroadcastReceiver {
        @Override
        public void onReceive(Context context, Intent intent) {
            sInstallResult.complete(intent.getIntExtra(PackageInstaller.EXTRA_STATUS,
                    PackageInstaller.STATUS_FAILURE));
            sInstallResultMessage.complete(intent.getStringExtra(
                    PackageInstaller.EXTRA_STATUS_MESSAGE));

        }

        public IntentSender getIntentSender(Context context) {
            // Generate a unique string to ensure each LocalIntentSender gets its own results.
            String action = InstallResultReceiver.class.getName();
            context.registerReceiver(this, new IntentFilter(action),
                    Context.RECEIVER_EXPORTED);
            Intent intent = new Intent(action).setPackage(context.getPackageName())
                    .addFlags(Intent.FLAG_RECEIVER_FOREGROUND);
            PendingIntent pending = PendingIntent.getBroadcast(context, 0, intent,
                    FLAG_UPDATE_CURRENT | FLAG_MUTABLE);
            return pending.getIntentSender();
        }
    }

    static class SessionListener extends PackageInstaller.SessionCallback {

        final CompletableFuture<Integer> mSessionIdCreated = new CompletableFuture<>();
        final CompletableFuture<Integer> mSessionIdFinished = new CompletableFuture<>();

        @Override
        public void onCreated(int sessionId) {
            mSessionIdCreated.complete(sessionId);
        }

        @Override
        public void onBadgingChanged(int sessionId) {
        }

        @Override
        public void onActiveChanged(int sessionId, boolean active) {
        }

        @Override
        public void onProgressChanged(int sessionId, float progress) {
        }

        @Override
        public void onFinished(int sessionId, boolean success) {
            mSessionIdFinished.complete(sessionId);
        }
    }

    public static class Launcher extends Activity {
    }
}
