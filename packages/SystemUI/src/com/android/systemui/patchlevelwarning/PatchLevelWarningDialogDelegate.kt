package com.android.systemui.patchlevelwarning

import android.app.ActivityManager
import android.app.ActivityTaskManager
import android.app.INotificationManager
import android.content.Context
import android.content.Intent
import android.ext.settings.ExtSettings
import android.net.Uri
import android.os.UserHandle
import android.util.Log
import android.widget.Toast
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.LinkInteractionListener
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import com.android.compose.PlatformButton
import com.android.systemui.dialog.ui.composable.AlertDialogContent
import com.android.systemui.plugins.ActivityStarter
import com.android.systemui.res.R
import com.android.systemui.shared.system.TaskStackChangeListener
import com.android.systemui.shared.system.TaskStackChangeListeners
import com.android.systemui.statusbar.phone.SystemUIDialog
import com.android.systemui.statusbar.phone.SystemUIDialogFactory
import com.android.systemui.statusbar.phone.create
import com.android.systemui.statusbar.policy.DeviceProvisionedController
import com.android.systemui.user.data.model.SelectedUserModel
import com.android.systemui.user.data.repository.UserRepository
import javax.inject.Inject
import kotlin.coroutines.resume
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine

private const val TAG = "PatchLevelWarningDialog"

/**
 * Full-screen popup dialog to warn the user about their patch level being expired. Will show up
 * on keyguard unlock and if the user taps on the system notification that also appears.
 *
 * For the main / system user, it will direct them to either check updates manually and review the
 * updater settings, or to enable the updater if it is disabled. For non-system users, it'll still
 * display the updater status, but it'll mention automatic updates can only be managed by system
 * user. See [PeriodicPatchLevelExpiryCheck.getWarningDescriptionParagraphs] for the exact strings
 * that are used on a case-by-case basis.
 *
 * The dialog is done in SystemUI. **It's important that this dialog is tested so that it doesn't
 * crash (e.g. from a upstream SystemUI change);** this popup is shown automatically after a
 * keyguard unlock, and any crashes from this dialog would crash SystemUI on an unlock as a
 * result. It wouldn't permanently prevent the user from getting into their phone, as it's only
 * displayed once a week for expired patch level dates (the [PeriodicPatchLevelExpiryCheck] job
 * that marks the keyguard popup is not rescheduled). Test this by building with the constant
 * [DEBUG_ALWAYS_EXPIRED] set to true and then using Settings -> Security & privacy -> More
 * security & privacy -> toggling Disable patch level expiry warnings to retrigger the check. Can
 * also toggle with [ExtSettings.USER_DISABLE_PATCH_LEVEL_EXPIRY_WARNING] using adb directly:
 *
 *     adb shell settings put global grapheneos_patch_level_warning_disabled 1
 *     adb shell settings put global grapheneos_patch_level_warning_disabled 0
 *
 * The alternative to doing this in SystemUI that would maintain the show-on-unlock behavior would
 * be to have an app that receives [Intent.ACTION_USER_PRESENT] broadcasts. However, this is not
 * broadcast to to implicit receivers, so there would likely have to be an app running just to
 * receive it. This dialog wouldn't even show up for users that regularly update, so it doesn't seem
 * worth it. If we do ditch the behavior that it shows up on keyguard unlock and just use a
 * non-dismissible notification that opens this full-screen dialog or an Activity, then a separate
 * app could be feasible. However, it could result in code duplication. Doing it in SystemUI allows
 * us to reuse other classes available here, which also makes consistency in theming super easy with
 * future Android updates (since we're using the same code that launches SystemUI dialogs in
 * general).
 *
 * Assuming the user hasn't opened the popup from the notification, the popup dialog will only show
 * up once after keyguard unlock for all non-system users and also once for the system user. We
 * split it like this to compromise between two cases:
 *
 * - Someone using multiple system users for themselves: It could be annoying to get the popup for
 *   every single user that they log into. But we also want to inform them that they should do
 *   updates in the main user (especially for the use case where they don't daily drive the main
 *   user)
 * - Sharing a phone with multiple people: The other people using the phone should be aware that the
 *   device is behind on security updates. Right now, only the owner and 1 other person will get the
 *   notification; if there are 3+ users accounts for 3+ different people, only 2 of them will be
 *   notified.
 *
 * While we could expose a setting for this, it might actually encourage users to turn warnings off
 * entirely. The user really should just update their phone! (Might be better just to have a
 * per-user setting to indicate whether it's used by the same person as the main user, and then we
 * read this setting. However, this approach could leak info about how the phone is used.)
 *
 * We don't show buttons or links that would launch an intent when the device is not setup (in
 * SetupWizard) or otherwise not provisioned.
 *
 * We also have considerations for the screen pinning mode that can be launched via System UI as
 * well as the fully locked mode that can be achieved on fully managed devices. No intents should
 * be launched in locked task mode (especially because we include a web browser intent). Through
 * testing, it doesn't seem like the popup is triggered when screen is pinned, since powering on the
 * screen doesn't count as a keyguard unlock (i.e. whenever [Intent.ACTION_USER_PRESENT] would be
 * sent out). Nevertheless, still need to be safe and check the locked task state.
 */
class PatchLevelWarningDialogDelegate @Inject constructor(
    private val systemUIDialogFactory: SystemUIDialogFactory,
    private val activityStarter: ActivityStarter,
    private val userRepository: UserRepository,
    private val deviceProvisionedController: DeviceProvisionedController,
    private val systemNotificationManager: INotificationManager,
    private val taskStackChangedListeners: TaskStackChangeListeners,
    private val activityTaskManager: ActivityTaskManager,
) : SystemUIDialog.Delegate {

    private var showOnKeyguardForSystemUser: Boolean = false
    private var showOnKeyguardForNonSystemUser: Boolean = false

    // called when USER_PRESENT broadcast would be sent out to determine if it popup should be shown
    fun shouldShowOnThisKeyguardUnlock(): Boolean {
        // using simple booleans since this is called every time the user unlocks their device
        // though the userRepository call is just a StateFlow value read so that's also low cost
        if (showOnKeyguardForSystemUser && userRepository.getSelectedUserInfo().userHandle.isSystem) {
            return true
        }
        if (showOnKeyguardForNonSystemUser && !userRepository.getSelectedUserInfo().userHandle.isSystem) {
            return true
        }
        return false
    }

    fun markShowOnKeyguard() {
        Log.d(TAG, "markShowOnKeyguard")
        showOnKeyguardForSystemUser = true
        showOnKeyguardForNonSystemUser = true
    }

    /**
     * Typically used when the expiry warnings are disabled.
     */
    fun markDontShowOnKeyguard() {
        Log.d(TAG, "markDontShowOnKeyguard")
        showOnKeyguardForSystemUser = false
        showOnKeyguardForNonSystemUser = false
    }

    fun beforeDialogShown() {
        if (userRepository.getSelectedUserInfo().userHandle.isSystem) {
            showOnKeyguardForSystemUser = false
        } else {
            showOnKeyguardForNonSystemUser = false
        }
    }

    data class DialogInfo(
        val style: TextLinkStyles?,
        val linkInteractionListener: LinkInteractionListener,
    )

    override fun createDialog(): SystemUIDialog {
        return systemUIDialogFactory.create(dismissOnDeviceLock = true) {
            WarningDialogContent(it)
        }
    }

    @Composable
    private fun registerUserSetupAndDeviceProvisionedChanges(): Boolean {
        var isCurrentUserSetup by remember {
            mutableStateOf(deviceProvisionedController.isCurrentUserSetup)
        }
        var isDeviceProvisioned by remember {
            mutableStateOf(deviceProvisionedController.isDeviceProvisioned())
        }
        DisposableEffect(deviceProvisionedController) {
            val callback = object : DeviceProvisionedController.DeviceProvisionedListener {
                override fun onUserSetupChanged() {
                    isCurrentUserSetup = deviceProvisionedController.isCurrentUserSetup()
                }

                override fun onDeviceProvisionedChanged() {
                    isDeviceProvisioned = deviceProvisionedController.isDeviceProvisioned()
                }
            }
            deviceProvisionedController.addCallback(callback)
            onDispose { deviceProvisionedController.removeCallback(callback) }
        }

        return isCurrentUserSetup && isDeviceProvisioned
    }

    @Composable
    private fun registerIsInLockTaskMode(): Boolean {
        var isInLockTaskMode by remember { mutableStateOf(activityTaskManager.isInLockTaskMode) }
        DisposableEffect(taskStackChangedListeners) {
            val callback = object : TaskStackChangeListener {
                override fun onLockTaskModeChanged(mode: Int) {
                    isInLockTaskMode = (mode != ActivityManager.LOCK_TASK_MODE_NONE)
                }
            }
            taskStackChangedListeners.registerTaskStackListener(callback)
            onDispose { taskStackChangedListeners.unregisterTaskStackListener(callback) }
        }

        return isInLockTaskMode
    }

    @Composable
    private fun WarningDialogContent(dialog: SystemUIDialog) {
        val context = LocalContext.current
        val scope = rememberCoroutineScope()
        val selectedUser: SelectedUserModel by userRepository.selectedUser.collectAsState()
        val updaterStatus: SystemUpdaterStatus = remember(selectedUser) {
            PeriodicPatchLevelExpiryCheck.getSystemUpdaterStatus(context)
        }

        LaunchedEffect(null) {
            // Possible repost a silent version of system notification in case the user has
            // dismissed the dialog. We want to do this since notification can be used to reopen the
            // dialog if they dismiss it (accidentally or from clicking a link)
            PeriodicPatchLevelExpiryCheck.postNotification(
                context, System.currentTimeMillis(),
                forPopupOpen = true,
                systemNotificationManager,
                currentUser = selectedUser.userInfo.userHandle
            )
        }

        val isCurrentlySystemUser = selectedUser.userInfo.userHandle == UserHandle.SYSTEM
        val isCurrentUserSetupAndDeviceProvisioned = registerUserSetupAndDeviceProvisionedChanges()

        val isInLockTaskMode = registerIsInLockTaskMode()
        LaunchedEffect(isCurrentUserSetupAndDeviceProvisioned, isInLockTaskMode) {
            Log.d(
                TAG,
                "isCurrentUserSetupAndDeviceProvisioned=$isCurrentUserSetupAndDeviceProvisioned, " +
                        "isInLockTaskMode=$isInLockTaskMode"
            )
        }

        val canLaunchIntents = !isInLockTaskMode && isCurrentUserSetupAndDeviceProvisioned

        // If unable to be parsed, we won't bombard the user with info about updater status.
        // This should never be false, but it's here just in case
        val isPatchLevelParsable = remember {
            PeriodicPatchLevelExpiryCheck.parseSecurityPatchAsLocalDate() != null
        }

        val isUpdaterAccessible = isCurrentlySystemUser
                && isCurrentUserSetupAndDeviceProvisioned
                && isPatchLevelParsable
                && !isInLockTaskMode
                && canLaunchIntents

        var errorTextResId: Int? by remember { mutableStateOf(null) }

        val linkColor = MaterialTheme.colorScheme.primary

        val paragraphs: List<CharSequence> = remember(
            context.resources,
            scope,
            linkColor,
            selectedUser,
            canLaunchIntents,
            updaterStatus
        ) {
            // Don't let users leave SetupWizard
            val spanStyle = if (canLaunchIntents) {
                TextLinkStyles(
                    style = SpanStyle(
                        color = linkColor,
                        textDecoration = TextDecoration.Underline
                    )
                )
            } else {
                null
            }
            PeriodicPatchLevelExpiryCheck.getWarningDescriptionParagraphs(
                context,
                isForNotification = false,
                selectedUser = selectedUser,
                dialogInfo = DialogInfo(spanStyle) { link ->
                    // Make links a no-op in SetupWizard.
                    // if !isCurrentUserSetup, don't use a null LinkInteractionListener, because
                    // that will make Compose default to attempting to open the link for
                    // LinkAnnotation.Url via AndroidUriHandler (and this will actually crash
                    // SystemUI, because it launches it without the NEW_TASK flag)
                    if (!canLaunchIntents) return@DialogInfo

                    if (link is LinkAnnotation.Url) {
                        openLink(
                            scope,
                            link,
                            onSuccess = {
                                Toast.makeText(
                                    context,
                                    R.string.patch_level_expiry_warning_dialog_tap_on_notification_to_reopen_dialog,
                                    Toast.LENGTH_LONG
                                ).show()
                                dialog.dismiss()
                            },
                            onFailure = {
                                Toast.makeText(
                                    context,
                                    R.string.patch_level_expiry_warning_dialog_webpage_unavailable_error_toast,
                                    Toast.LENGTH_SHORT
                                ).show()
                                errorTextResId = R.string.patch_level_expiry_warning_dialog_webpage_unavailable_error_toast
                            }
                        )
                    } else {
                        Log.w(TAG, "unknown link annotation $link")
                    }
                }
            )
        }

        val title: @Composable () -> Unit = {
            if (isUpdaterAccessible) {
                when (updaterStatus) {
                    SystemUpdaterStatus.Disabled -> Text(stringResource(R.string.patch_level_expiry_warning_dialog_title_updater_disabled))
                    is SystemUpdaterStatus.Enabled -> Text(stringResource(R.string.patch_level_expiry_warning_dialog_title_updater_enabled))
                    SystemUpdaterStatus.Missing -> Text(stringResource(R.string.patch_level_expiry_warning_dialog_title_updater_missing))
                }
            } else {
                Text(stringResource(R.string.patch_level_expiry_warning_notification_title))
            }
        }
        val content: @Composable () -> Unit = {
            // the alert dialog already puts us in a scrollable column
            Column {
                paragraphs.forEachIndexed { index, paragraph ->
                    if (paragraph is AnnotatedString) {
                        Text(paragraph)
                    } else {
                        Text(paragraph.toString())
                    }
                    if (index != paragraphs.lastIndex) {
                        Spacer(Modifier.height(8.dp))
                    }
                }
                errorTextResId?.let { errorResId ->
                    if (paragraphs.isNotEmpty()) {
                        Spacer(Modifier.height(8.dp))
                    }
                    Text(stringResource(errorResId), color = MaterialTheme.colorScheme.error)
                }
            }
        }
        val positiveButton: @Composable (() -> Unit)?
        val neutralButton: @Composable (() -> Unit)?
        val onDismissClicked = {
            dialog.dismiss()
            // if can't launch intents, give the user a chance to access the updater buttons and
            // links later
            if (canLaunchIntents) {
                cancelNotification(context, selectedUser)
            }
        }
        if (isUpdaterAccessible) {
            when (updaterStatus) {
                SystemUpdaterStatus.Disabled, is SystemUpdaterStatus.Enabled -> {
                    // make the updater button the priority
                    positiveButton = {
                        OpenUpdaterButton(
                            scope = scope,
                            updaterStatus = updaterStatus,
                            isPrimaryButton = true,
                            context = context,
                            onDismissClicked = onDismissClicked,
                            onErrorTextChange = { errorTextResId = it }
                        )
                    }
                    neutralButton = {
                        DismissDialogButton(isPrimaryButton = false, onDismissClicked)
                    }
                }
                SystemUpdaterStatus.Missing -> {
                    positiveButton = {
                        DismissDialogButton(isPrimaryButton = true, onDismissClicked)
                    }
                    neutralButton = null
                }
            }
        } else {
            positiveButton = { DismissDialogButton(isPrimaryButton = true, onDismissClicked) }
            neutralButton = null
        }

        AlertDialogContent(
            title = title,
            content = content,
            positiveButton = positiveButton,
            neutralButton = neutralButton,
        )
    }

    private fun cancelNotification(context: Context, selectedUser: SelectedUserModel) {
        PeriodicPatchLevelExpiryCheck.cancelNotification(context, selectedUser)
    }

    @Composable
    private fun DismissDialogButton(isPrimaryButton: Boolean, onDismissClicked: () -> Unit) {
        val content: @Composable RowScope.() -> Unit =  {
            Text(stringResource(R.string.dismiss_dialog))
        }
        if (isPrimaryButton) {
            PlatformButton(onClick = onDismissClicked, content = content)
        } else {
            TextButton(onClick = onDismissClicked, content = content)
        }
    }

    @Composable
    private fun OpenUpdaterButton(
        scope: CoroutineScope,
        updaterStatus: SystemUpdaterStatus,
        isPrimaryButton: Boolean,
        context: Context,
        onDismissClicked: () -> Unit,
        onErrorTextChange: (stringRes: Int) -> Unit,
    ) {
        val content: @Composable RowScope.() -> Unit = {
            Text(
                stringResource(
                    if (updaterStatus is SystemUpdaterStatus.Enabled) {
                        R.string.patch_level_expiry_warning_dialog_updater_open_button
                    } else {
                        R.string.patch_level_expiry_warning_dialog_updater_enable_button
                    }
                )
            )
        }
        val onClick = {
            openUpdaterSettings(
                scope, updaterStatus,
                onSuccess = onDismissClicked,
                onFailure = {
                    Toast.makeText(
                        context,
                        R.string.patch_level_expiry_warning_dialog_updater_unavailable_error_toast,
                        Toast.LENGTH_SHORT
                    ).show()

                    onErrorTextChange(
                        R.string.patch_level_expiry_warning_dialog_updater_unavailable_error_toast
                    )
                }
            )
        }
        if (isPrimaryButton) {
            PlatformButton(onClick = onClick, content = content)
        } else {
            OutlinedButton(onClick = onClick, content = content)
        }
    }

    private fun openLink(
        scope: CoroutineScope,
        link: LinkAnnotation.Url,
        onSuccess: () -> Unit,
        onFailure: () -> Unit,
    ) {
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(link.url)).apply {
            addCategory(Intent.CATEGORY_BROWSABLE)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        scope.launch {
            val success = launchActivity(intent)
            if (success) {
                onSuccess()
            } else {
                onFailure()
            }
        }
    }

    private fun openUpdaterSettings(
        scope: CoroutineScope,
        status: SystemUpdaterStatus?,
        onSuccess: () -> Unit,
        onFailure: () -> Unit,
    ) {
        val intent: Intent? = when (status) {
            is SystemUpdaterStatus.Enabled -> status.launchUpdaterSettingsIntent
            is SystemUpdaterStatus.Disabled -> status.launchAppSettingsForEnablingIntent
            SystemUpdaterStatus.Missing, null -> null
        }

        scope.launch {
            val success = intent?.let { launchActivity(it) }
            if (success == true) {
                onSuccess()
            } else {
                onFailure()
            }
        }
    }

    private suspend fun launchActivity(intent: Intent): Boolean {
        val currentUser = userRepository.getSelectedUserInfo().userHandle
        val resultCode = suspendCancellableCoroutine { continuation ->
            activityStarter.startActivityDismissingKeyguard(
                /* intent = */ intent,
                /* onlyProvisioned = */ true,
                /* dismissShade = */ true,
                /* disallowEnterPictureInPictureWhileLaunching = */ false,
                /* callback = */ { resultCode -> continuation.resume(resultCode) },
                /* flags = */ 0,
                /* animationController = */ null,
                /* userHandle = */ currentUser
            )
            // suspends until continuation.resume in callback
        }
        val success = ActivityManager.isStartResultSuccessful(resultCode)
        if (!success) {
            Log.d(TAG, "launchActivity: failed to launch $intent (resultCode=$resultCode)")
        }
        return success
    }
}
