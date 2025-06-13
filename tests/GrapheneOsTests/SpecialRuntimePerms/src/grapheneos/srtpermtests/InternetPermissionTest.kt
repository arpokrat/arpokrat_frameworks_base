package grapheneos.srtpermtests

import android.Manifest
import android.app.Instrumentation
import android.content.Context
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import androidx.test.filters.FlakyTest
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.runner.AndroidJUnit4
import androidx.test.uiautomator.UiDevice
import grapheneos.srtpermtests.AppThatAccessesInternetRule.Companion.TEST_APP_PKG
import grapheneos.test.common.DeadObjectExceptionRetryRule
import java.net.InetAddress
import java.net.UnknownHostException
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assume
import org.junit.Before
import org.junit.ClassRule
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Based on
 * packages/modules/Permission/tests/cts/permission/src/android/permission/cts/LocationAccessCheckTest.java
 *
 * Runs code in the test app via IPC (AIDL). Cannot do test code in instrumentation app, since
 * changing runtime permissions can stop the instrumentation app.
 *
 * The service binding for IPC is managed by the [ClassRule], [appThatAccessesInternetRule]
 */
@RunWith(AndroidJUnit4::class)
@FlakyTest
class InternetPermissionTest {
    private val mContext: Context = androidx.test.InstrumentationRegistry.getTargetContext()
    val mInstrumentation: Instrumentation = InstrumentationRegistry.getInstrumentation()
    val mPackageManager: PackageManager = mContext.packageManager
    val uiDevice: UiDevice = UiDevice.getInstance(mInstrumentation)

    companion object {
        @ClassRule
        @JvmField
        val appThatAccessesInternetRule = AppThatAccessesInternetRule()

        private suspend fun bindService() = appThatAccessesInternetRule.bindService()

        private fun wakeUpAndDismissKeyguard() {
            appThatAccessesInternetRule.wakeUpAndDismissKeyguard()
        }
    }

    @get:Rule
    val deadObjectRetryRule = DeadObjectExceptionRetryRule(retryCount = 2)

    @Before
    fun beforeEachTest() {
        mInstrumentation.uiAutomation.grantRuntimePermission(
            TEST_APP_PKG,
            Manifest.permission.INTERNET
        )
        mInstrumentation.uiAutomation.grantRuntimePermission(
            TEST_APP_PKG,
            Manifest.permission.OTHER_SENSORS
        )
        wakeUpAndDismissKeyguard()
    }

    @After
    fun afterEachTest() {
        appThatAccessesInternetRule.unbindService()
    }

    @Test
    fun internet_granted_resolve_name_successful() = runTest {
        assertEquals(
            PackageManager.PERMISSION_GRANTED,
            mPackageManager.checkPermission(
                Manifest.permission.INTERNET,
                TEST_APP_PKG
            ),
            "expected INTERNET to be granted"
        )
        val acc = bindService()
        val result = acc.accessInternet()
        assertTrue(result.isSuccessfulAccess)
    }

    @Test
    fun internet_granted_connectivity_manager_methods_show_connected() = runTest {
        val acc = bindService()
        val isConnected = acc.isConnected()
        assertTrue(isConnected)
    }

    @Test
    fun internet_revoked_resolve_name_throws_exception_like_no_internet() = runTest {
        Assume.assumeTrue(
            "network should be available",
            try {
                InetAddress.getByName("grapheneos.org")
                true
            } catch (e: UnknownHostException) {
                false
            } catch (e: SecurityException) {
                false
            }
        )

        mInstrumentation.uiAutomation.revokeRuntimePermission(
            TEST_APP_PKG,
            Manifest.permission.INTERNET
        )

        val acc = bindService()
        val result = acc.accessInternet()
        assertFalse(result.isSuccessfulAccess)
        val exceptionClass = assertNotNull(result.exceptionClass)
        val msg = assertNotNull(result.exceptionMessage)
        assertEquals("java.net.UnknownHostException", exceptionClass)
        // Note that in AOSP, an app will throw a SecurityException if it doesn't have INTERNET
        // permission. In GrapheneOS, revoking the INTERNET permission will cause the app to be
        // treated as having no internet access
        assertContains(
            msg,
            "Unable to resolve host \"grapheneos.org\": No address associated with hostname"
        )
    }

    @Test
    fun internet_revoked_connectivity_manager_methods_show_not_connected() = runTest {
        val cm = mContext.getSystemService(ConnectivityManager::class.java)
        val network = cm.activeNetwork
        Assume.assumeTrue(network != null)
        val caps = cm.getNetworkCapabilities(network)
        Assume.assumeTrue(caps != null)
        Assume.assumeTrue(caps!!.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET))

        mInstrumentation.uiAutomation.revokeRuntimePermission(
            TEST_APP_PKG,
            Manifest.permission.INTERNET
        )

        val acc = bindService()
        val isConnected: Boolean = acc.isConnected()
        assertFalse(isConnected)
    }
}
