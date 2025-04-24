package grapheneos.srtpermtests.packageinstaller

data class TestApk(
    val packageName: String,
    val apkPath: String,
)

object TestApks {
    const val SAMPLE_APK_BASE: String = "/data/local/tmp/cts/uninstall/"

    @JvmField
    val helloWorldV1 = TestApk(
        packageName = "com.example.helloworld",
        apkPath = SAMPLE_APK_BASE + "GosHelloWorldAppV1.apk",
    )

    @JvmField
    val helloWorldV2 = TestApk(
        packageName = helloWorldV1.packageName,
        apkPath = SAMPLE_APK_BASE + "GosHelloWorldAppV2.apk",
    )

    @JvmField
    val appThatAccessesInternet = TestApk(
        packageName = "grapheneos.srtpermtests.internet.appthataccessesinternet",
        apkPath = SAMPLE_APK_BASE + "GosAppThatAccessesInternetOnCommand.apk",
    )
}
