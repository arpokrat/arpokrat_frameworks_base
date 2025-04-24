# SpecialRuntimePermsTestCases

Test cases for GrapheneOS special runtime permissions. 

The tests won't compile if the `OTHER_SENSORS` permission isn't implemeneted (compiles using the new
manifest entry for `android.permission.OTHER_SENSORS` and the system message notification id for
`com.android.server.ext.MissingSpecialRuntimePermissionNotification`).

## Running tests

Run an active device with internet access (might be best done with 
`emulator -wipe-data -read-only` for a clean slate, and on a userdebug build) and then run

```bash
atest SpecialRuntimePermsTestCases
```

This command will handle building and pushing the APKs onto the device (see 
[AndroidTest.xml](AndroidTest.xml) for details).

Specific tests can be run by specifying the test class (and optionally the test function name) 
after `:`, e.g.

```bash
atest SpecialRuntimePermsTestCases:InternetAndSensorsPermissionTest
atest SpecialRuntimePermsTestCases:InternetAndSensorsPermissionTest#sensors_granted_get_success
```

## Overview of tests

- Installing apps with auto grant sensor setting in various states
- Updating apps and ensuring runtime permission states are preserved
- Archiving and unarchiving apps and ensuring runtime permission states are preserved in various 
cases (based on the tests from https://github.com/GrapheneOS/platform_frameworks_base/pull/163)
- Ensuring the general functionality of special runtime permissions
  - AOSP should consider the special runtime permissions to have a dangerous protection level
  - Revoking internet permission should result in the app seeing as the network as unavailable
    instead of throwing errors
  - Revoking sensor permission should result in sensor events not being received, and a notification
    should be posted if an app tries to access sensors when the permission is revoked

Much of the test code and apps are based on existing AOSP CTS tests (`cts/` and
`packages/modules/Permission/tests/cts`). Some CTS test failures were used to build test cases here.

## Known issues

- InternetAndSensorsPermissionTest is based on a CTS test that was marked as flaky 
  (`packages/modules/Permission/tests/cts/permission/src/android/permission/cts/LocationAccessCheckTest.java`).
  Sometimes the tests might fail from a `DeadObjectException` or other misc failures.
- Currently doesn't cover usages of `TriggerEventListener` like for
  `Sensor.TYPE_SIGNIFICANT_MOTION`. Seems more complicated to test (though 
  [here's an existing CTS test for it](https://cs.android.com/android/platform/superproject/main/+/main:cts/apps/CtsVerifier/src/com/android/cts/verifier/sensors/SignificantMotionTestActivity.java))
