package com.android.server.pm;

import android.app.ActivityManager;
import android.app.PropertyInvalidatedCache;
import android.content.Context;
import android.content.pm.UserInfo;
import android.content.res.Resources;
import android.os.Looper;
import android.os.UserHandle;
import android.os.UserManager;
import android.platform.test.annotations.Presubmit;

import androidx.test.InstrumentationRegistry;
import androidx.test.filters.MediumTest;

import com.android.server.LocalServices;

import com.google.common.truth.Expect;

import junitparams.JUnitParamsRunner;
import junitparams.Parameters;

import org.junit.After;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.util.List;

import static android.content.pm.UserInfo.FLAG_PROFILE;
import static android.content.pm.UserInfo.FLAG_FULL;
import static android.os.UserManager.USER_TYPE_FULL_DEMO;
import static android.os.UserManager.USER_TYPE_FULL_GUEST;
import static android.os.UserManager.USER_TYPE_FULL_RESTRICTED;
import static android.os.UserManager.USER_TYPE_FULL_SECONDARY;
import static android.os.UserManager.USER_TYPE_FULL_SYSTEM;
import static android.os.UserManager.USER_TYPE_PROFILE_CLONE;
import static android.os.UserManager.USER_TYPE_PROFILE_MANAGED;
import static android.os.UserManager.USER_TYPE_PROFILE_PRIVATE;

/**
 * Run with
 * {@code atest FrameworksServicesTests:com.android.server.pm.UserManagerServiceUserRestrictionsFallbackTest}.
 */
@Presubmit
@MediumTest
@RunWith(JUnitParamsRunner.class)
@SuppressWarnings("deprecation")
public class UserManagerServiceUserRestrictionsFallbackTest {

    @Rule
    public final Expect expect = Expect.create();

    private UserManagerService mUms;
    private final Context mContext = InstrumentationRegistry.getInstrumentation().getContext();
    private Resources mResources;

    @Before
    public void setup() throws Exception {
        // Currently UserManagerService cannot be instantiated twice inside a VM without a cleanup
        // TODO: Remove once UMS supports proper dependency injection
        if (Looper.myLooper() == null) {
            Looper.prepare();
        }
        // Disable binder caches in this process.
        PropertyInvalidatedCache.disableForTestMode();

        LocalServices.removeServiceForTest(UserManagerInternal.class);
        mUms = new UserManagerService(InstrumentationRegistry.getContext());
        // Put the current user to mUsers. UMS can't find userlist.xml, and fallbackToSingleUserLP.
        mUms.putUserInfo(
                new UserInfo(ActivityManager.getCurrentUser(), "Current User", 0));
        mResources = InstrumentationRegistry.getTargetContext().getResources();
    }

    @After
    public void tearDown() {
        removeUsers();
    }

    private static final int MINIMUM_USER_ID_FOR_TEST = 300;

    private void removeUsers() {
        List<UserInfo> users = mUms.getUsers(/* excludeDying */ false);
        for (UserInfo user: users) {
            if (user.id >= MINIMUM_USER_ID_FOR_TEST) {
                mUms.removeUserInfo(user.id);
            }
        }
    }

    @Parameters({
            USER_TYPE_FULL_SYSTEM,
            USER_TYPE_FULL_SECONDARY,
            USER_TYPE_FULL_GUEST,
            USER_TYPE_FULL_RESTRICTED,
            USER_TYPE_FULL_DEMO
    })
    @Test
    @MediumTest
    public void testStandardFullUserTypesRestrictionsFallback_OnParentRequiredProfiles(String fullUserType) {
        int fullUserId = MINIMUM_USER_ID_FOR_TEST;
        UserInfo fullUser = createUserOfStandardType(fullUserType, fullUserId, fullUserId);
        mUms.putUserInfo(fullUser);
        UserInfo managedProfile = createUserOfStandardType(
                USER_TYPE_PROFILE_MANAGED, fullUserId + 1, fullUserId);
        mUms.putUserInfo(managedProfile);
        UserInfo cloneProfile = createUserOfStandardType(
                USER_TYPE_PROFILE_CLONE, fullUserId + 2, fullUserId);
        mUms.putUserInfo(cloneProfile);
        UserInfo privateProfile = createUserOfStandardType(
                USER_TYPE_PROFILE_PRIVATE, fullUserId + 3, fullUserId);
        mUms.putUserInfo(privateProfile);
        testRestrictionsFallbackForUsers(fullUser, List.of(managedProfile, cloneProfile, privateProfile));
    }

    @Parameters({
            USER_TYPE_FULL_SYSTEM,
            USER_TYPE_FULL_SECONDARY,
            USER_TYPE_FULL_GUEST,
            USER_TYPE_FULL_RESTRICTED,
            USER_TYPE_FULL_DEMO
    })
    @Test
    @MediumTest
    public void testStandardFullUserTypesRestrictionsFallback_OnParentRequiredCustomProfiles(String fullUserType) {
        int fullUserId = MINIMUM_USER_ID_FOR_TEST + 100;
        UserInfo fullUser = createUserOfStandardType(fullUserType, fullUserId, fullUserId);
        mUms.putUserInfo(fullUser);
        UserTypeDetails userTypeDetails1 = new UserTypeDetails.Builder()
                .setName("app.testing.profile.PROFILE_TYPE_1")
                .setBaseType(FLAG_PROFILE)
                .createUserTypeDetails();
        UserInfo profile1 = createUserFromDetails(
                userTypeDetails1, fullUserId + 1, fullUserId);
        mUms.putUserInfo(profile1);
        UserTypeDetails userTypeDetails2 = new UserTypeDetails.Builder()
                .setName("app.testing.profile.PROFILE_TYPE_2")
                .setProfileParentRequired(true)
                .setBaseType(FLAG_PROFILE)
                .createUserTypeDetails();
        UserInfo profile2 = createUserFromDetails(
                userTypeDetails2, fullUserId + 2, fullUserId);
        mUms.putUserInfo(profile2);
        testRestrictionsFallbackForUsers(fullUser, List.of(profile1, profile2));
    }

    private void testRestrictionsFallbackForUsers(UserInfo fullUser,
            List<UserInfo> profileInfoList) {
        for (UserInfo profileInfo: profileInfoList) {
            int fullUserId = fullUser.id;
            int profileUserId = profileInfo.id;
            for (String key: UserRestrictionsUtils.USER_RESTRICTIONS_FALLBACK_TO_PARENT) {
                try {
                    mUms.setUserRestriction(key, true, fullUserId);
                    expect.withMessage("hasUserRestriction(%s, %s)", key, profileUserId)
                            .that(mUms.hasUserRestriction(key, profileUserId)).isTrue();
                    expect.withMessage("hasBaseUserRestriction(%s, %s)", key, profileUserId)
                            .that(mUms.hasBaseUserRestriction(key, profileUserId)).isTrue();
                    List<UserManager.EnforcingUser> restrictionSources =
                            mUms.getUserRestrictionSources(key, profileUserId);
                    expect.withMessage("restrictionSources(%s, %s).size() == 1", key, profileUserId)
                            .that(restrictionSources.size()).isEqualTo(1);
                    expect.withMessage("restrictionSources(%s, %s) | "
                                    + "UserManager.RESTRICTION_SOURCE_SYSTEM "
                                    + "== UserManager.RESTRICTION_SOURCE_SYSTEM",
                                    key, profileUserId)
                            .that(restrictionSources.getFirst().getUserRestrictionSource() | UserManager.RESTRICTION_SOURCE_SYSTEM)
                            .isEqualTo(UserManager.RESTRICTION_SOURCE_SYSTEM);
                } finally {
                    mUms.setUserRestriction(key, false, fullUserId);
                }

                // This sets user restriction on device policy management restriction set.
                try {
                    mUms.setUserRestrictionInner(fullUserId, key, true);
                    expect.withMessage("hasUserRestriction(%s, %s)", key, profileUserId)
                            .that(mUms.hasUserRestriction(key, profileUserId)).isTrue();
                    List<UserManager.EnforcingUser> restrictionSources =
                            mUms.getUserRestrictionSources(key, profileUserId);
                    expect.withMessage("restrictionSources(%s, %s).size() == 0", key, profileUserId)
                            .that(restrictionSources.size()).isEqualTo(0);
                } finally {
                    mUms.setUserRestrictionInner(fullUserId, key, false);
                }
            }
        }
    }

    private UserInfo createUserOfStandardType(String userType, int userId, int profileGroupId) {
        UserTypeDetails userTypeDetails = UserTypeFactory.getUserTypes().get(userType);
        String msg = String.format("userTypeDetails for %s is non-null", userType);
        Assert.assertNotNull(msg, userTypeDetails);
        return createUserFromDetails(userTypeDetails, userId, profileGroupId);
    }

    private UserInfo createUserFromDetails(
            UserTypeDetails userTypeDetails, int userId, int profileGroupId) {
        String userType = userTypeDetails.getName();
        int flags = userTypeDetails.getDefaultUserInfoFlags();
        return createUserOfType(userType, flags, userId, profileGroupId);
    }

    private UserInfo createUserOfType(String userType, int flags, int userId, int profileGroupId) {
        UserInfo userInfo = new UserInfo(userId, "A Name", "A path", flags, userType);
        userInfo.profileGroupId = profileGroupId;
        return userInfo;
    }
}
