package grapheneos.srtpermtests

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.IBinder
import android.util.Log
import com.android.compatibility.common.util.SystemUtil
import grapheneos.srtpermtests.internet.appthataccessesinternet.IAccessInternetOnCommand
import grapheneos.srtpermtests.packageinstaller.TestApks
import kotlin.test.assertTrue
import kotlinx.coroutines.suspendCancellableCoroutine
import org.junit.rules.ExternalResource

/**
 * A rule meant to be used as a [org.junit.ClassRule] for managing the install, uninstall, and
 * service bindings for IPC in the internet access test app.
 */
class AppThatAccessesInternetRule : ExternalResource() {
    companion object {
        private const val TAG = "AppThatAccessesInternetRule"
        val TEST_APP_PKG = TestApks.appThatAccessesInternet.packageName
        val TEST_APP_SERVICE = "$TEST_APP_PKG.AccessInternetOnCommand"
    }

    val context: Context = androidx.test.InstrumentationRegistry.getTargetContext();
    private var serviceConn: ServiceConnection? = null
    private var accessor: IAccessInternetOnCommand? = null

    private fun installBackgroundAccessApp() {
        val output = SystemUtil.runShellCommandOrThrow(
            // -g means grant all runtime permissions
            "pm install -r -g " + TestApks.appThatAccessesInternet.apkPath
        )
        assertTrue(output.contains("Success"))
    }

    private fun uninstallBackgroundAccessApp() {
        val output = SystemUtil.runShellCommandOrThrow(
            "pm uninstall $TEST_APP_PKG"
        )
        assertTrue(output.contains("Success"))
    }

    private fun setIdleAllowlist(enabled: Boolean) {
        val prefix = if (enabled) "+" else "-"
        val command = "cmd deviceidle whitelist $prefix$TEST_APP_PKG"
        SystemUtil.runShellCommand(command)
    }

    suspend fun bindService(): IAccessInternetOnCommand {
        if (serviceConn != null && accessor != null) {
            return accessor!!
        }

        return suspendCancellableCoroutine { cont ->
            serviceConn = object : ServiceConnection {
                override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
                    val acc = IAccessInternetOnCommand.Stub.asInterface(service)
                    accessor = acc
                    cont.resume(acc) {
                        unbindService()
                    }
                }

                override fun onServiceDisconnected(name: ComponentName?) {
                    serviceConn = null
                    accessor = null
                    cont.cancel()
                }
            }
            val intent = Intent()
            intent.component = ComponentName(TEST_APP_PKG, TEST_APP_SERVICE)
            context.bindService(
                intent,
                serviceConn!!,
                // adding Context.BIND_NOT_FOREGROUND will make test app service unable to
                // get sensor readings
                Context.BIND_AUTO_CREATE
            )
        }
    }

    fun unbindService() {
        serviceConn?.let {
            context.unbindService(it)
            serviceConn = null
        }
        accessor = null
    }

    fun wakeUpAndDismissKeyguard() {
        SystemUtil.runShellCommand("input keyevent KEYCODE_WAKEUP")
        SystemUtil.runShellCommand("wm dismiss-keyguard")
    }

    override fun before() {
        Log.d(TAG, "before()")
        installBackgroundAccessApp()
        // Might be needed to allow test app to do internet calls in Service.
        // Note: Commenting this out alone seems to result in all tests still passing.
        // Removing this and adding Context.BIND_NOT_FOREGROUND to the service binding,
        // will cause some of the internet granted tests to fail.
        setIdleAllowlist(true)
    }

    override fun after() {
        setIdleAllowlist(false)
        uninstallBackgroundAccessApp()
        unbindService()
    }
}