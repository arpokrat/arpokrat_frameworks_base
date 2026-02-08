/*
 * Copyright (C) 2021 The Android Open Source Project
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

package com.android.server.pm;

import static android.content.pm.PackageManager.CERT_INPUT_SHA256;

import static com.android.server.pm.PackageManagerService.TAG;

import android.annotation.Nullable;
import android.content.pm.ApplicationInfo;
import android.content.pm.GosPackageState;
import android.content.pm.GosPackageStateFlag;
import android.content.pm.IPackageManagerNative;
import android.content.pm.IStagedApexObserver;
import android.content.pm.MicrophoneScopeInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageInfoNative;
import android.content.pm.PackageManager;
import android.content.pm.Signature;
import android.content.pm.SignatureNative;
import android.content.pm.SigningInfoNative;
import android.content.pm.StagedApexInfo;
import android.os.Binder;
import android.os.RemoteException;
import android.os.UserHandle;
import android.system.virtualmachine.BuildFlags;
import android.text.TextUtils;
import android.util.Slog;

import com.android.server.pm.pkg.PackageStateInternal;

import android.os.SELinux;

import android.system.Os;

import java.io.File;
import java.util.Arrays;

final class PackageManagerNative extends IPackageManagerNative.Stub {
    private final PackageManagerService mPm;

    PackageManagerNative(PackageManagerService pm) {
        mPm = pm;
    }

    @Override
    public String[] getNamesForUids(int[] uids) throws RemoteException {
        String[] names = null;
        String[] results = null;
        try {
            if (uids == null || uids.length == 0) {
                return null;
            }
            names = mPm.snapshotComputer().getNamesForUids(uids);
            results = (names != null) ? names : new String[uids.length];
            // massage results so they can be parsed by the native binder
            for (int i = results.length - 1; i >= 0; --i) {
                if (results[i] == null) {
                    results[i] = "";
                }
            }
            return results;
        } catch (Throwable t) {
            // STOPSHIP(186558987): revert addition of try/catch/log
            Slog.e(TAG, "uids: " + Arrays.toString(uids));
            Slog.e(TAG, "names: " + Arrays.toString(names));
            Slog.e(TAG, "results: " + Arrays.toString(results));
            Slog.e(TAG, "throwing exception", t);
            throw t;
        }
    }

    @Override
    public PackageInfoNative getPackageInfoWithSigningInfo(String packageName, int userId) {
        PackageInfo pInfo = mPm.snapshotComputer().getPackageInfo(packageName,
                PackageManager.GET_SIGNING_CERTIFICATES, userId);
        if (pInfo == null)  {
            return null;
        }

        PackageInfoNative result = new PackageInfoNative();
        result.packageName = packageName;

        if (BuildFlags.SUPPORT_AVF_ADVANCE_MULTITENANCY && pInfo.applicationInfo != null) {
            result.sourceDir = pInfo.applicationInfo.sourceDir;
        }
        if (pInfo.signingInfo == null) {
            return result;
        }
        result.signingInfo = new SigningInfoNative();

        Signature[] signatures = pInfo.signingInfo.hasMultipleSigners()
                ? pInfo.signingInfo.getApkContentsSigners()
                : pInfo.signingInfo.getSigningCertificateHistory();
        if (signatures == null) {
            return result;
        }

        SignatureNative[] apkContentSigners = new SignatureNative[signatures.length];
        for (int i = 0; i < signatures.length; i++) {
            SignatureNative sig = new SignatureNative();
            sig.signature = signatures[i].toByteArray();
            apkContentSigners[i] = sig;
        }
        result.signingInfo.apkContentSigners = apkContentSigners;

        return result;
    }

    @Override
    public PackageInfoNative[] getPackageInfoWithSigningInfoForUid(int uid) throws RemoteException {
        String[] packageNames = mPm.snapshotComputer().getPackagesForUid(uid);
        if (packageNames == null) {
            return null;
        }

        int userId = UserHandle.getUserId(uid);
        PackageInfoNative[] result = new PackageInfoNative[packageNames.length];
        for (int i = 0; i < packageNames.length; i++) {
            result[i] = getPackageInfoWithSigningInfo(packageNames[i], userId);
        }
        return result;
    }

    @Override
    public int getPackageUid(String packageName, long flags, int userId) throws RemoteException {
        return mPm.snapshotComputer().getPackageUid(packageName, flags, userId);
    }

    @Override
    public int checkPermission(String permName, String packageName, int userId)
            throws RemoteException {
        return mPm.checkPermission(permName, packageName, userId);
    }

    // NB: this differentiates between preloads and sideloads
    @Override
    public String getInstallerForPackage(String packageName) throws RemoteException {
        final Computer snapshot = mPm.snapshotComputer();
        final int callingUser = UserHandle.getUserId(Binder.getCallingUid());
        final String installerName = snapshot.getInstallerPackageName(packageName, callingUser);
        if (!TextUtils.isEmpty(installerName)) {
            return installerName;
        }
        // differentiate between preload and sideload
        ApplicationInfo appInfo = snapshot.getApplicationInfo(packageName,
                /*flags*/ 0,
                /*userId*/ callingUser);
        if (appInfo != null && (appInfo.flags & ApplicationInfo.FLAG_SYSTEM) != 0) {
            return "preload";
        }
        return "";
    }

    @Override
    public long getVersionCodeForPackage(String packageName) throws RemoteException {
        try {
            int callingUser = UserHandle.getUserId(Binder.getCallingUid());
            PackageInfo pInfo = mPm.snapshotComputer()
                    .getPackageInfo(packageName, 0, callingUser);
            if (pInfo != null) {
                return pInfo.getLongVersionCode();
            }
        } catch (Exception e) {
        }
        return 0;
    }

    @Override
    public int getTargetSdkVersionForPackage(String packageName) throws RemoteException {
        int targetSdk = mPm.snapshotComputer().getTargetSdkVersion(packageName);
        if (targetSdk != -1) {
            return targetSdk;
        }

        throw new RemoteException("Couldn't get targetSdkVersion for package " + packageName);
    }

    @Override
    public boolean isPackageDebuggable(String packageName) throws RemoteException {
        int callingUser = UserHandle.getCallingUserId();
        ApplicationInfo appInfo = mPm.snapshotComputer()
                .getApplicationInfo(packageName, 0, callingUser);
        if (appInfo != null) {
            return (0 != (appInfo.flags & ApplicationInfo.FLAG_DEBUGGABLE));
        }

        throw new RemoteException("Couldn't get debug flag for package " + packageName);
    }

    @Override
    public boolean[] isAudioPlaybackCaptureAllowed(String[] packageNames)
            throws RemoteException {
        int callingUser = UserHandle.getUserId(Binder.getCallingUid());
        final Computer snapshot = mPm.snapshotComputer();
        boolean[] results = new boolean[packageNames.length];
        for (int i = results.length - 1; i >= 0; --i) {
            ApplicationInfo appInfo = snapshot.getApplicationInfo(packageNames[i], 0, callingUser);
            results[i] = appInfo != null && appInfo.isAudioPlaybackCaptureAllowed();
        }
        return results;
    }

    @Override
    public int getLocationFlags(String packageName) throws RemoteException {
        int callingUser = UserHandle.getUserId(Binder.getCallingUid());
        ApplicationInfo appInfo = mPm.snapshotComputer().getApplicationInfo(packageName,
                /*flags*/ 0,
                /*userId*/ callingUser);
        if (appInfo == null) {
            throw new RemoteException(
                    "Couldn't get ApplicationInfo for package " + packageName);
        }
        return ((appInfo.isSystemApp() ? IPackageManagerNative.LOCATION_SYSTEM : 0)
                | (appInfo.isVendor() ? IPackageManagerNative.LOCATION_VENDOR : 0)
                | (appInfo.isProduct() ? IPackageManagerNative.LOCATION_PRODUCT : 0));
    }

    @Override
    public String getModuleMetadataPackageName() throws RemoteException {
        return mPm.getModuleMetadataPackageName();
    }

    @Override
    public boolean hasSha256SigningCertificate(String packageName, byte[] certificate)
            throws RemoteException {
        return mPm.snapshotComputer()
                .hasSigningCertificate(packageName, certificate, CERT_INPUT_SHA256);
    }

    @Override
    public boolean hasSystemFeature(String featureName, int version) {
        return mPm.hasSystemFeature(featureName, version);
    }

    @Override
    public void registerStagedApexObserver(IStagedApexObserver observer) {
        mPm.mInstallerService.getStagingManager().registerStagedApexObserver(observer);
    }

    @Override
    public void unregisterStagedApexObserver(IStagedApexObserver observer) {
        mPm.mInstallerService.getStagingManager().unregisterStagedApexObserver(observer);
    }

    @Override
    public StagedApexInfo[] getStagedApexInfos() {
        return mPm.mInstallerService.getStagingManager().getStagedApexInfos().toArray(
                new StagedApexInfo[0]);
    }

    public void onDeniedSpecialRuntimePermissionOp(String permissionName, int uid, String packageName) {
        if (Binder.getCallingUid() != android.os.Process.SYSTEM_UID) {
            throw new SecurityException();
        }

        com.android.server.ext.MissingSpecialRuntimePermissionNotification
                .maybeShow(mPm.getContext(), permissionName, uid, packageName);
    }

    @Override
    @Nullable
    public MicrophoneScopeInfo getMicrophoneScopeInfo(int uid, int userId) throws RemoteException {
        final String micScopesDir = "/data/system/microphone_scopes/";
        int callingUid = Binder.getCallingUid();
        if (callingUid != android.os.Process.SYSTEM_UID
                && callingUid != android.os.Process.AUDIOSERVER_UID
                && callingUid != android.os.Process.MEDIA_UID) {
            Slog.e(TAG, "getMicrophoneScopeInfo not allowed from uid " + callingUid);
            throw new SecurityException("getMicrophoneScopeInfo not allowed from uid " + callingUid);
        }
        Slog.i(TAG, "getMicrophoneScopeInfo called for uid=" + uid + ", userId=" + userId + " from callingUid=" + callingUid);

        final Computer snapshot = mPm.snapshotComputer();
        String[] packages = snapshot.getPackagesForUid(uid);
        if (packages == null || packages.length == 0) {
            return null;
        }

        for (String packageName : packages) {
            PackageStateInternal psi = snapshot.getPackageStates().get(packageName);
            if (psi == null) {
                continue;
            }
            GosPackageState gps = psi.getUserStateOrDefault(userId).getGosPackageState();
            if (gps != null && gps.hasFlag(GosPackageStateFlag.MICROPHONE_SCOPES_ENABLED)) {
                Slog.i(TAG, "getMicrophoneScopeInfo: microphone scopes enabled for " + packageName);
                MicrophoneScopeInfo info = new MicrophoneScopeInfo();
                info.enabled = true;

                File baseDir = new File(micScopesDir);
                File audioDir = new File(baseDir, userId + "/" + packageName);
                File audioFile = new File(audioDir, "audio.dat");

                byte[] scopeData = gps.microphoneScopes;
                if (scopeData != null && scopeData.length > 0) {
                    java.io.DataInputStream dis = new java.io.DataInputStream(
                            new java.io.ByteArrayInputStream(scopeData));
                    try {
                        String uriString = dis.readUTF();
                        String resolvedPath = null;
                        try {
                            resolvedPath = dis.readUTF();
                        } catch (Exception e) {
                            // old format without resolved path
                        }

                        if (resolvedPath != null) {
                            if (resolvedPath.startsWith("/storage/emulated/")) {
                                resolvedPath = resolvedPath.replaceFirst(
                                        "/storage/emulated/", "/data/media/");
                            }

                            audioFile.delete();

                            baseDir.mkdir();
                            baseDir.setExecutable(true, false);
                            new File(baseDir, String.valueOf(userId)).mkdir();
                            new File(baseDir, String.valueOf(userId)).setExecutable(true, false);
                            audioDir.mkdir();
                            audioDir.setExecutable(true, false);

                            Os.symlink(resolvedPath, audioFile.getAbsolutePath());
                            SELinux.restoreconRecursive(baseDir);
                            Slog.i(TAG, "Created symlink " + audioFile.getAbsolutePath() + " -> " + resolvedPath);

                            info.audioFilePath = audioFile.getAbsolutePath();
                            Slog.i(TAG, "Returning audio file path: " + info.audioFilePath);
                        }
                    } catch (Exception e) {
                        Slog.w(TAG, "Failed to create microphone scope audio symlink", e);
                    }
                } else {
                    audioFile.delete();
                }

                return info;
            }
        }

        return null;
    }
}
