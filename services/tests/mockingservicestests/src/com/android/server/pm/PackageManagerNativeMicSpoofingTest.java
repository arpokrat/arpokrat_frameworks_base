package com.android.server.pm;

import static org.junit.Assert.assertThrows;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.when;

import android.content.pm.GosPackageState;
import android.content.pm.GosPackageStateFlag;
import android.ext.micspoofing.MicSpoofingApi;
import android.os.UserHandle;
import android.util.ArrayMap;

import androidx.test.ext.junit.runners.AndroidJUnit4;

import com.android.server.pm.pkg.PackageStateInternal;
import com.android.server.pm.pkg.PackageUserStateInternal;

import com.google.common.truth.Truth;

import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.junit.MockitoJUnit;
import org.mockito.junit.MockitoRule;

@RunWith(AndroidJUnit4.class)
public class PackageManagerNativeMicSpoofingTest {

    private static final String TEST_PKG = "com.android.test.app";
    private static final int TEST_UID = UserHandle.getUid(10, 12345);
    private static final String TEST_FUSE_PATH = "/storage/emulated/10/Music/test.wav";

    @Rule
    public final MockitoRule mockitoRule = MockitoJUnit.rule();

    @Mock
    private PackageManagerService packageManagerService;

    @Mock
    private Computer computer;

    @Mock
    private PackageStateInternal packageState;

    @Mock
    private PackageUserStateInternal userState;

    @Test
    public void canAccessMicSpoofingStateForUid_sameUid_returnsTrue() {
        var localUid = UserHandle.getUid(0, 34567);

        var canAccess = PackageManagerNative.canAccessMicSpoofingStateForUid(
                TEST_UID, TEST_UID, localUid);

        Truth.assertThat(canAccess).isTrue();
    }

    @Test
    public void canAccessMicSpoofingStateForUid_callerIsLocalProcess_returnsTrue() {
        var callingUid = UserHandle.getUid(11, 23456);

        var canAccess = PackageManagerNative.canAccessMicSpoofingStateForUid(
                callingUid, TEST_UID, callingUid);

        Truth.assertThat(canAccess).isTrue();
    }

    @Test
    public void canAccessMicSpoofingStateForUid_systemUidCrossUid_returnsTrue() {
        var systemUid = UserHandle.getUid(0, android.os.Process.SYSTEM_UID);
        var localUid = UserHandle.getUid(0, 34567);

        var canAccess = PackageManagerNative.canAccessMicSpoofingStateForUid(
                systemUid, TEST_UID, localUid);

        Truth.assertThat(canAccess).isTrue();
    }

    @Test
    public void canAccessMicSpoofingStateForUid_mediaUidCrossUid_returnsTrue() {
        var mediaUid = UserHandle.getUid(0, android.os.Process.MEDIA_UID);
        var localUid = UserHandle.getUid(0, 34567);

        var canAccess = PackageManagerNative.canAccessMicSpoofingStateForUid(
                mediaUid, TEST_UID, localUid);

        Truth.assertThat(canAccess).isTrue();
    }

    @Test
    public void canAccessMicSpoofingStateForUid_audioserverUidCrossUid_returnsTrue() {
        var audioserverUid = UserHandle.getUid(0, android.os.Process.AUDIOSERVER_UID);
        var localUid = UserHandle.getUid(0, 34567);

        var canAccess = PackageManagerNative.canAccessMicSpoofingStateForUid(
                audioserverUid, TEST_UID, localUid);

        Truth.assertThat(canAccess).isTrue();
    }

    @Test
    public void canAccessMicSpoofingStateForUid_untrustedCrossUid_returnsFalse() {
        var callingUid = UserHandle.getUid(11, 23456);
        var localUid = UserHandle.getUid(0, 34567);

        var canAccess = PackageManagerNative.canAccessMicSpoofingStateForUid(
                callingUid, TEST_UID, localUid);

        Truth.assertThat(canAccess).isFalse();
    }

    @Test
    public void isMicSpoofingEnabledForUid_enabled_returnsTrue() {
        mockUidState(createGosPackageState(
                true, MicSpoofingApi.buildCustomPathConfig(TEST_FUSE_PATH)));

        var pmNative = new PackageManagerNative(packageManagerService);

        Truth.assertThat(pmNative.isMicSpoofingEnabledForUid(TEST_UID)).isTrue();
    }

    @Test
    public void isMicSpoofingEnabledForUid_disabled_returnsFalse() {
        mockUidState(createGosPackageState(false, null));

        var pmNative = new PackageManagerNative(packageManagerService);

        Truth.assertThat(pmNative.isMicSpoofingEnabledForUid(TEST_UID)).isFalse();
    }

    @Test
    public void isMicSpoofingEnabledForUid_noPackages_returnsFalse() {
        when(packageManagerService.snapshotComputer()).thenReturn(computer);
        when(computer.getPackagesForUid(TEST_UID)).thenReturn(null);

        var pmNative = new PackageManagerNative(packageManagerService);

        Truth.assertThat(pmNative.isMicSpoofingEnabledForUid(TEST_UID)).isFalse();
    }

    @Test
    public void isMicSpoofingEnabledForUid_emptyPackages_returnsFalse() {
        when(packageManagerService.snapshotComputer()).thenReturn(computer);
        when(computer.getPackagesForUid(TEST_UID)).thenReturn(new String[0]);

        var pmNative = new PackageManagerNative(packageManagerService);

        Truth.assertThat(pmNative.isMicSpoofingEnabledForUid(TEST_UID)).isFalse();
    }

    @Test
    public void isMicSpoofingEnabledForUid_privilegedCrossUidCallers_allowed() {
        var privilegedAppIds = new int[]{
                android.os.Process.SYSTEM_UID,
                android.os.Process.MEDIA_UID,
                android.os.Process.AUDIOSERVER_UID
        };
        var localUid = UserHandle.getUid(0, 34567);
        mockUidState(createGosPackageState(
                true, MicSpoofingApi.buildCustomPathConfig(TEST_FUSE_PATH)));

        for (var appId : privilegedAppIds) {
            var callingUid = UserHandle.getUid(11, appId);
            var pmNative = new PackageManagerNative(
                    packageManagerService,
                    () -> callingUid,
                    () -> localUid
            );

            Truth.assertThat(pmNative.isMicSpoofingEnabledForUid(TEST_UID)).isTrue();
        }
    }

    @Test
    public void isMicSpoofingEnabledForUid_untrustedCrossUid_throwsSecurityException() {
        var callingUid = UserHandle.getUid(11, 23456);
        var localUid = UserHandle.getUid(0, 34567);
        var pmNative = new PackageManagerNative(
                packageManagerService,
                () -> callingUid,
                () -> localUid
        );

        var exception = assertThrows(
                SecurityException.class,
                () -> pmNative.isMicSpoofingEnabledForUid(TEST_UID)
        );
        Truth.assertThat(exception).hasMessageThat().contains("UID " + callingUid);
        Mockito.verify(packageManagerService, never()).snapshotComputer();
    }

    private void mockUidState(GosPackageState gosPackageState) {
        var userId = UserHandle.getUserId(TEST_UID);
        when(packageManagerService.snapshotComputer()).thenReturn(computer);
        when(computer.getPackagesForUid(TEST_UID)).thenReturn(new String[]{TEST_PKG});
        when(packageState.getUserStateOrDefault(userId)).thenReturn(userState);
        when(userState.getGosPackageState()).thenReturn(gosPackageState);
        var packageStates = new ArrayMap<String, PackageStateInternal>();
        packageStates.put(TEST_PKG, packageState);
        Mockito.doReturn(packageStates).when(computer).getPackageStates();
    }

    private static GosPackageState createGosPackageState(
            boolean enabled,
            byte[] micSpoofingConfig
    ) {
        var flagStorage1 = enabled ? (1L << GosPackageStateFlag.MIC_SPOOFING_ENABLED) : 0L;
        return new GosPackageState(flagStorage1, 0L, null, null, micSpoofingConfig);
    }
}
