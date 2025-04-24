package grapheneos.srtpermtests.pm

import android.Manifest
import android.app.Instrumentation
import android.content.Context
import android.content.pm.PackageManager
import android.content.pm.PermissionInfo
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.UiDevice
import kotlin.test.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SrtPermPackageManagerTests {

    lateinit var mInstrumentation: Instrumentation
    lateinit var mContext: Context
    lateinit var mPackageManager: PackageManager
    lateinit var mUiDevice: UiDevice

    @Before
    fun setup() {
        mInstrumentation = InstrumentationRegistry.getInstrumentation()
        mContext = mInstrumentation.getContext()
        mPackageManager = mContext.getPackageManager()
        mUiDevice = UiDevice.getInstance(mInstrumentation)
    }

    /**
     * Based on failing CTS test
     * cts/tests/tests/content/src/android/content/pm/cts/PackageManagerTest.java#testGetPermissionInfo
     */
    @Test
    fun internet_has_dangerous_protection_level() {
        val permissionName = Manifest.permission.INTERNET
        val permissionInfo = mPackageManager.getPermissionInfo(permissionName, 0)
        assertEquals(permissionName, permissionInfo.name)
        assertEquals(
            PermissionInfo.PROTECTION_DANGEROUS.toLong(),
            permissionInfo.protection.toLong()
        )
    }

    @Test
    fun other_sensors_has_dangerous_protection_level() {
        val permissionName = Manifest.permission.OTHER_SENSORS
        val permissionInfo = mPackageManager.getPermissionInfo(permissionName, 0)
        assertEquals(permissionName, permissionInfo.name)
        assertEquals(
            PermissionInfo.PROTECTION_DANGEROUS.toLong(),
            permissionInfo.protection.toLong()
        )
    }
}
