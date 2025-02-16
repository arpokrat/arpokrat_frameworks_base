package com.android.systemui.patchlevelwarning

import android.annotation.UserIdInt
import android.app.INotificationManager
import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.job.JobInfo
import android.app.job.JobParameters
import android.app.job.JobScheduler
import android.app.job.JobService
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ParceledListSlice
import android.ext.settings.ExtSettings
import android.icu.text.MessageFormat
import android.net.Uri
import android.os.Build
import android.os.Parcelable
import android.os.Process
import android.os.RemoteException
import android.os.UserHandle
import android.provider.Settings
import android.service.notification.StatusBarNotification
import android.text.format.DateFormat
import android.util.Log
import androidx.annotation.StringRes
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.buildAnnotatedString
import com.android.settingslib.DeviceInfoUtils
import com.android.settingslib.Utils
import com.android.systemui.SystemUIApplication
import com.android.systemui.keyguard.KeyguardViewMediator
import com.android.systemui.patchlevelwarning.PeriodicPatchLevelExpiryCheck.Companion.getWarningStartDate
import com.android.systemui.res.R
import com.android.systemui.user.data.model.SelectedUserModel
import com.android.systemui.user.data.repository.UserRepository
import com.android.systemui.util.NotificationChannels
import java.text.ParseException
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import java.util.Locale
import javax.inject.Inject
import kotlin.time.Duration
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.minutes

private const val TAG = "PatchLevelExpiryCheck"
private const val JOB_ID = 450000

// pm resolve-activity -a android.settings.SYSTEM_UPDATE_SETTINGS
// or check the manifest in packages/apps/Updater
const val UPDATER_PACKAGE = "app.seamlessupdate.client"
private const val UPDATER_ACTIVITY_NAME = "app.seamlessupdate.client.Settings"

private const val UPDATE_USAGE_INFO_URL = "https://grapheneos.org/usage#updates"
private const val CONTACT_INFO_URL = "https://grapheneos.org/contact"

// Set to true to always show as expired. Ignored when not in userdebug or eng
private val DEBUG_ALWAYS_EXPIRED = false && !Build.IS_USER

/** **Minimum** amount of time to hold off warnings. See [CHECK_INTERVAL] for maximum. */
private val EXPIRY_GRACE_PERIOD: Duration = 14.days

/**
 * How often to check patch level. Also serves as the reminder interval once the device has an
 * expired patch level.
 *
 * Implicitly adds to the grace period for the first warning.
 * Worst-case grace period is [EXPIRY_GRACE_PERIOD] + [CHECK_INTERVAL] plus Doze restrictions
 *
 * e.g. if the patch level is 2025-01-05, [EXPIRY_GRACE_PERIOD]=2 weeks and [CHECK_INTERVAL]=1 week,
 * then expiry warning starts showing anytime in between 2 weeks and 3 weeks after the same date in
 * the next month (i.e. 2025-02-05 + 2 to 3 weeks)
 */
private val CHECK_INTERVAL: Duration = if (DEBUG_ALWAYS_EXPIRED) {
    // JobScheduler has a minimum of around 15 min
    17.minutes
} else {
    7.days
}

private const val SECURITY_PATCH_DATE_FORMAT = "yyyy-MM-dd"

private const val NOTIFICATION_TAG = "grapheneos_patch_level_warning"
private const val NOTIFICATION_ID = 18000003

private const val PENDING_INTENT_REQUEST_CODE_OPEN_DIALOG = 18000005

/**
 * Periodically checks for patch level expiry, with checks occurring every [CHECK_INTERVAL]. Since
 * AOSP and GrapheneOS issue monthly security updates, a patch level older than one month plus
 * [EXPIRY_GRACE_PERIOD] is considered expired. See [getWarningStartDate] for details on how the
 * warning start date is calculated.
 *
 * If expired, a system alert notification (a standard Android notification with high priority) is
 * displayed to all users, and a popup warning (a SystemUI-style dialog that appears over
 * everything) is shown upon the next keyguard unlock. The warnings are re-triggered at each
 * [CHECK_INTERVAL] or upon device reboot. The warnings will cease once the device is updated to
 * a build with a current patch level. The user can open the popup from the notification; doing this
 * means the popup won't show up again for that user type on keyguard unlock.
 *
 * See [PatchLevelWarningDialogDelegate] for the popup dialog behavior on a per-user basis.
 *
 * If phone is currently being used while the expiry check goes off, only the notification will be
 * shown. The popup is displayed upon the next keyguard unlock. The popup is extremely disruptive
 * and we don't want to block whatever the user is actively doing.
 *
 * This should never happen normally, but if [Build.VERSION.SECURITY_PATCH] cannot be parsed using
 * the [SECURITY_PATCH_DATE_FORMAT], the warnings will begin showing up on a weekly basis indicating
 * that the security patch level cannot be parsed by the system.
 *
 * Users can disable this warning ([ExtSettings.USER_DISABLE_PATCH_LEVEL_EXPIRY_WARNING]), and it
 * can also be disabled on a per-device basis via a read-only device config
 * ([ExtSettings.DEVICE_DISABLED_PATCH_LEVEL_EXPIRY_WARNING]) for EOL devices.
 */
class PeriodicPatchLevelExpiryCheck : JobService() {
    companion object {
        const val OPEN_DIALOG_ACTION = "PeriodicPatchLevelExpiryCheck.OPEN_DIALOG"

        private val SECURITY_PATCH = if (DEBUG_ALWAYS_EXPIRED) {
            "2024-01-05"
        } else {
            Build.VERSION.SECURITY_PATCH
        }

        fun getWarningStartDate(currentPatchDate: LocalDate): LocalDate {
            // AOSP security updates are monthly
            return currentPatchDate
                .plusMonths(1)
                .plusDays(EXPIRY_GRACE_PERIOD.inWholeDays)
        }

        @JvmStatic
        fun isPatchLevelExpiredOrUnparseable(): Boolean {
            val securityPatchLocalDate = parseSecurityPatchAsLocalDate()
            if (securityPatchLocalDate == null) {
                Log.w(TAG, "Failed to get getSecurityPatchLocalDate; treating as expired")
                // treat it as expired, but dialog will show special messages for this
                return true
            }

            return LocalDate.now() >= getWarningStartDate(securityPatchLocalDate)
        }

        @JvmStatic
        fun schedule(context: Context, isFromBoot: Boolean = false) {
            // SystemUI should only be from system user
            Log.d(TAG, "schedule from handle ${Process.myUserHandle()}, check interval $CHECK_INTERVAL, isFromBoot = $isFromBoot")
            if (!isPatchLevelWarningEnabled(context)) {
                Log.d(TAG, "schedule: patch level expiry warning disabled; not scheduling")
                return
            }
            if (!context.user.isSystem) { // shouldn't happen: we're always system user in SystemUI
                Log.d(TAG, "schedule: not system user; not scheduling")
                return
            }

            val jobScheduler = context.getSystemService(JobScheduler::class.java)

            // force reschedule if on boot (though the job is not persisted anymore)
            if (!isFromBoot) {
                jobScheduler.getPendingJob(JOB_ID)?.let { existingJob ->
                    if (existingJob.intervalMillis == CHECK_INTERVAL.inWholeMilliseconds) {
                        Log.d(TAG, "schedule: existing job already scheduled")
                        return
                    }
                }
            }

            val result = jobScheduler.schedule(
                JobInfo.Builder(
                    JOB_ID,
                    ComponentName(context, PeriodicPatchLevelExpiryCheck::class.java)
                ).setPeriodic(CHECK_INTERVAL.inWholeMilliseconds)
                    .build()
            )
            if (result == JobScheduler.RESULT_FAILURE) {
                Log.w(TAG, "failed to schedule periodic check job")
            }
        }

        @JvmStatic
        fun cancel(context: Context) {
            Log.d(TAG, "cancel from handle ${Process.myUserHandle()}")
            val jobScheduler = context.getSystemService(JobScheduler::class.java)
            jobScheduler.cancel(JOB_ID)
        }

        fun parseSecurityPatchAsLocalDate(): LocalDate? {
            val currentPatch = SECURITY_PATCH
            val template = DateTimeFormatter.ofPattern(SECURITY_PATCH_DATE_FORMAT)
            return try {
                LocalDate.parse(currentPatch, template)
            } catch (e: ParseException) {
                // should never happen, but just in case
                Log.e(TAG, "error parsing security patch level", e)
                null
            }
        }

        fun getWarningDescriptionParagraphs(
            context: Context,
            selectedUser: SelectedUserModel?,
            isForNotification: Boolean = false,
            dialogInfo: PatchLevelWarningDialogDelegate.DialogInfo? = null,
        ): List<CharSequence> {
            // dialog isn't expected to dynamically change, so it's not like this will be called
            // repeatedly
            val isCurrentlySystemUser = selectedUser?.userInfo?.userHandle == UserHandle.SYSTEM
            val securityPatchDate = parseSecurityPatchAsLocalDate()
            // Should never happen, but should handle this anyway. Will popup in developer / alpha /
            // beta testing. Tells the user to seek assistance on GitHub / community platforms.
            if (securityPatchDate == null) {
                val patchLevelCantBeParsed = context.getString(
                    R.string.patch_level_expiry_warning_dialog_unable_to_parse_patch_level__s,
                    SECURITY_PATCH
                )

                return if (isForNotification) {
                    val tapToLearnMore = context.getString(R.string.patch_level_expiry_warning_notification_tap_to_learn_more)
                    listOf(patchLevelCantBeParsed, tapToLearnMore)
                } else {
                    val expectedPatchLevelFormat = context.getString(
                        R.string.patch_level_expiry_warning_dialog_expected_patch_level_format__s,
                        SECURITY_PATCH_DATE_FORMAT
                    )
                    val contactCommunityAndGitHubIssues = buildParagraphWithAnnotatedLink(
                        context, isForNotification, dialogInfo,
                        CONTACT_INFO_URL,
                        R.string.patch_level_expiry_warning_dialog_unable_to_parse_patch_level_contact_community_github,
                    )
                    listOf(
                        patchLevelCantBeParsed,
                        expectedPatchLevelFormat,
                        contactCommunityAndGitHubIssues
                    )
                }
            }

            val months = securityPatchDate.until(LocalDate.now(), ChronoUnit.MONTHS)
            // Settings -> About phone -> Android version uses DeviceInfoUtils to
            // format the patch level. DeviceInfoUtils uses DateFormat.getBestDateTimePattern in
            // the following way.
            val patchFormatted = DateFormat
                    .getBestDateTimePattern(Locale.getDefault(), "dMMMMyyyy")
                    .let { pattern ->
                        val formatter = DateTimeFormatter.ofPattern(pattern, Locale.getDefault())
                        securityPatchDate.format(formatter)
                    }
                    ?: DeviceInfoUtils.getSecurityPatch()
                        ?.ifEmpty { null }
                    ?: SECURITY_PATCH
            val monthsSinceLastPatchLevelText = MessageFormat(
                context.getString(R.string.patch_level_expiry_warning_dialog_message_months_since_patch_level),
                Locale.getDefault()
            ).format(mapOf("months" to months, "device_patch_level" to patchFormatted))
                .let { monthsSinceText ->
                    if (DEBUG_ALWAYS_EXPIRED) {
                        monthsSinceText +
                                " " +
                                context.getString(
                                    R.string.patch_level_expiry_warning_dialog_message_debug__s,
                                    Build.VERSION.SECURITY_PATCH
                                )
                    } else {
                        monthsSinceText
                    }
                }

            if (isForNotification) {
                val tapToLearnMore = context.getString(R.string.patch_level_expiry_warning_notification_tap_to_learn_more)

                return listOf(
                    monthsSinceLastPatchLevelText,
                    tapToLearnMore
                )
            }

            val systemUpdateStatusTexts = buildList<CharSequence> {
                val updaterStatus = getSystemUpdaterStatus(context)
                add(
                    context.getString(when (updaterStatus) {
                        is SystemUpdaterStatus.Enabled ->
                            R.string.patch_level_expiry_warning_dialog_message_updater_enabled
                        SystemUpdaterStatus.Disabled ->
                            R.string.patch_level_expiry_warning_dialog_message_updater_disabled
                        SystemUpdaterStatus.Missing ->
                            R.string.patch_level_expiry_warning_dialog_message_updater_missing
                    })
                )

                if (updaterStatus == SystemUpdaterStatus.Missing && isCurrentlySystemUser) {
                    // not normal to be missing the updater app, but still need to inform the user
                    // what to do if it happens
                    val contactCommunityOrGitHub = buildParagraphWithAnnotatedLink(
                        context, isForNotification, dialogInfo,
                        CONTACT_INFO_URL,
                        R.string.patch_level_expiry_warning_dialog_message_updater_missing_recommend_contacting_community_github,
                    )
                    add(contactCommunityOrGitHub)
                }
            }

            val needSystemUserToAccessUpdaterText = context.getString(
                R.string.patch_level_expiry_warning_dialog_message_not_system_user
            )

            val learnMoreAtWebsiteText = buildParagraphWithAnnotatedLink(
                context, isForNotification, dialogInfo,
                UPDATE_USAGE_INFO_URL,
                R.string.patch_level_expiry_warning_dialog_message_learn_more,
            )

            return if (isCurrentlySystemUser) {
                buildList {
                    add(monthsSinceLastPatchLevelText)
                    addAll(systemUpdateStatusTexts)
                    add(learnMoreAtWebsiteText)
                }
            } else {
                buildList {
                    add(monthsSinceLastPatchLevelText)
                    addAll(systemUpdateStatusTexts)
                    add(needSystemUserToAccessUpdaterText)
                    add(learnMoreAtWebsiteText)
                }
            }
        }

        private fun buildParagraphWithAnnotatedLink(
            context: Context,
            isForNotification: Boolean,
            dialogInfo: PatchLevelWarningDialogDelegate.DialogInfo?,
            websiteUrl: String,
            @StringRes paragraphTextStringRes: Int,
        ): CharSequence {
            val linkText = websiteUrl.asUrlWithoutSchemeOrHash()
            return context.getString(paragraphTextStringRes, linkText).let { paragraph ->
                dialogInfo?.takeIf { !isForNotification && linkText.isNotBlank() }?.let {
                    buildAnnotatedString {
                        append(paragraph)
                        val start = paragraph.indexOf(linkText)
                        val end = start + linkText.length
                        if (
                            start in paragraph.indices &&
                            end in start..paragraph.length // end offset is exclusive
                        ) {
                            addLink(
                                LinkAnnotation.Url(
                                    websiteUrl,
                                    styles = dialogInfo.style,
                                    linkInteractionListener = dialogInfo.linkInteractionListener
                                ),
                                start,
                                end
                            )
                        }
                    }
                } ?: paragraph
            }
        }

        fun getSystemUpdaterStatus(context: Context): SystemUpdaterStatus {
            val systemPm = if (context.user.isSystem) {
                context
            } else {
                context.createContextAsUser(UserHandle.SYSTEM, 0)
            }.packageManager

            val enabledStateFromResolve: Boolean? = systemPm.resolveActivity(
                Intent(Settings.ACTION_SYSTEM_UPDATE_SETTINGS), PackageManager.MATCH_SYSTEM_ONLY
            )?.activityInfo?.let { activityInfo ->
                if (activityInfo.enabled) {
                    return SystemUpdaterStatus.Enabled(
                        ComponentName(activityInfo.packageName, activityInfo.name)
                    )
                }
                activityInfo.enabled
            }
            // resolveResult can be null if the updater app is either disabled or not installed.
            // Need to parse the actual updater package to be certain.
            return try {
                val info = systemPm.getApplicationInfo(
                    UPDATER_PACKAGE, PackageManager.MATCH_SYSTEM_ONLY
                )
                if (info.enabled) {
                    Log.w(TAG, "getApplicationInfo shows updater enabled, but resolveActivity returned $enabledStateFromResolve")
                    SystemUpdaterStatus.Enabled(ComponentName(UPDATER_PACKAGE, UPDATER_ACTIVITY_NAME))
                } else {
                    SystemUpdaterStatus.Disabled
                }
            } catch (e: PackageManager.NameNotFoundException) {
                SystemUpdaterStatus.Missing
            }
        }

        @JvmStatic
        fun isPatchLevelWarningEnabled(context: Context): Boolean {
            return !ExtSettings.DEVICE_DISABLED_PATCH_LEVEL_EXPIRY_WARNING.get(context) &&
                    !ExtSettings.USER_DISABLE_PATCH_LEVEL_EXPIRY_WARNING.get(context)
        }

        fun cancelNotification(context: Context, selectedUser: SelectedUserModel) {
            val notificationManager = context.getSystemService(NotificationManager::class.java)
            // note: seems when a user dismisses a notification meant for UserHandle.ALL,
            // the notification for all other uses is still dismissed
            notificationManager.cancelAsUser(
                NOTIFICATION_TAG,
                NOTIFICATION_ID,
                selectedUser.userInfo.userHandle
            )
        }


        /**
         * Posts a notification that warns about patch level expiry and lets the user open the
         * popup dialog by tapping on it.
         *
         * -  If [forPopupOpen] is false, then the notification is for all users via
         *   [UserHandle.ALL]. Note: If one user dismisses this [UserHandle.ALL] notification, it
         *   is dismissed for all other users!
         *
         * -  If [forPopupOpen] is true, then the notification is for the [currentUser]. It will be
         *   silent, since it comes up simultaneously with the popup. But we'll only post it if an
         *   expiry update system update notification of any kind isn't already there. The reason
         *   we do this is that the [UserHandle.ALL] notifications get dismissed for all if one user
         *   does it.
         *
         *    For example, if someone is currently on a non-main user and they see the
         *   popup, the dismiss button will dismiss it for both their current user and the main
         *   user running in the background (because of how [UserHandle.ALL] notifications work).
         *   When they switch to the main user, they will still get the popup, but we need to repost
         *   the notification only for main user so that dialog popup can be reopened if it's closed
         *   accidentally or through clicking a web link.
         *
         *    The [systemNotificationManager] is used in this second case to query for
         *   [UserHandle.ALL] and the specific [currentUser] notifications. Using
         *   [android.app.NotificationManager] from the usual Context system service would only get
         *   it for [Context.getUserId], which is [UserHandle.USER_SYSTEM] because system UI is
         *   always run in system user.
         */
        fun postNotification(
            context: Context,
            timestamp: Long,
            forPopupOpen: Boolean = false,
            systemNotificationManager: INotificationManager? = null,
            currentUser: UserHandle? = null,
        ) {
            if (forPopupOpen) {
                // Need to query INotificationManager directly for UserHandle.USER_ALL notifications
                systemNotificationManager?.let { manager ->
                    val notificationSequence: Sequence<StatusBarNotification> = sequence {
                        yieldAll(
                            systemUiActiveNotificationsForUser(
                                context, manager, UserHandle.USER_ALL
                            )
                        )
                        currentUser?.identifier?.let { userId ->
                            yieldAll(systemUiActiveNotificationsForUser(context, manager, userId))
                        }
                    }
                    val notif = notificationSequence.find {
                        it.id == NOTIFICATION_ID && it.tag == NOTIFICATION_TAG
                    }
                    if (notif != null) {
                        val user = if (notif.user == UserHandle.ALL) {
                            "ALL"
                        } else {
                            notif.user.identifier.toString()
                        }
                        Log.d(TAG, "postNotification(forDialogOpen): notification already exists (for user $user)")
                        return
                    }
                }
            }

            val paragraphs = getWarningDescriptionParagraphs(
                context = context,
                selectedUser = null,
                isForNotification = true
            )
            val msg = paragraphs.joinToString("\n\n")

            val notificationBuilder = Notification.Builder(context, NotificationChannels.ALERTS)
                .setSmallIcon(com.android.settingslib.R.drawable.ic_system_update)
                .setWhen(timestamp)
                .setContentTitle(context.getString(R.string.patch_level_expiry_warning_notification_title))
                .setContentText(msg)
                .setStyle(Notification.BigTextStyle().bigText(msg))
                .setVisibility(Notification.VISIBILITY_PRIVATE)
                .setColor(Utils.getColorAttrDefaultColor(context, android.R.attr.colorError))
            if (forPopupOpen) {
                notificationBuilder.setSilent(true)
            }
            // make it look like it comes from system
            SystemUIApplication.overrideNotificationAppName(context, notificationBuilder, true)

            val intent = Intent(OPEN_DIALOG_ACTION)
                .setPackage(context.packageName) // important so that only sends to receivers in SystemUI
                .addFlags(
                    Intent.FLAG_RECEIVER_REGISTERED_ONLY or Intent.FLAG_RECEIVER_EXCLUDE_BACKGROUND
                )
            val pendingIntent = PendingIntent.getBroadcastAsUser(
                context,
                PENDING_INTENT_REQUEST_CODE_OPEN_DIALOG,
                intent,
                PendingIntent.FLAG_IMMUTABLE,
                // SystemUI is run under the system user
                UserHandle.SYSTEM
            )

            notificationBuilder.setContentIntent(pendingIntent)

            val notificationManager = context.getSystemService(NotificationManager::class.java)
            // cancel any user-specific notifications, since they will have a different handle from
            // UserHandle.ALL and it'll show up as two separate notifications. This should be rare
            // though, since it's only shown according to the [CHECK_INTERVAL]
            if (!forPopupOpen && currentUser != null) {
                notificationManager.cancelAsUser(NOTIFICATION_TAG, NOTIFICATION_ID, currentUser)
            }
            notificationManager.notifyAsUser(
                NOTIFICATION_TAG,
                NOTIFICATION_ID,
                notificationBuilder.build(),
                if (forPopupOpen && currentUser != null) {
                    // won't cause propagation with forwarding notifications feature, since this
                    // is the active user
                    currentUser
                } else {
                    // GrapheneOS notification forwarding feature doesn't forward UserHandle.ALL
                    // across all users
                    UserHandle.ALL
                }
            )
        }

        private fun systemUiActiveNotificationsForUser(
            context: Context,
            manager: INotificationManager,
            @UserIdInt userId: Int,
        ): Sequence<StatusBarNotification> = try {
            val parceledList: ParceledListSlice<Parcelable>? = manager.getAppActiveNotifications(
                context.packageName, userId
            )
            parceledList?.list
                ?.asSequence()
                ?.filterIsInstance<StatusBarNotification>()
                ?: emptySequence()
        } catch (e: RemoteException) {
            throw e.rethrowFromSystemServer()
        }
    }

    @JvmField
    @Inject
    var keyguardViewMediator: KeyguardViewMediator? = null

    @JvmField
    @Inject
    var userRepository: UserRepository? = null

    override fun onCreate() {
        super.onCreate()
        (application as SystemUIApplication).sysUIComponent.inject(this)
    }

    override fun onStartJob(params: JobParameters?): Boolean {
        val expiryWarningEnabled = isPatchLevelWarningEnabled(this)
        Log.d(
            TAG,
            "onStartJob, jobId ${params?.jobId}, expiryWarningEnabled $expiryWarningEnabled"
        )
        if (keyguardViewMediator == null) {
            Log.w(TAG, "keyguardViewMediator is null. popup dialog won't show but notif will")
        }

        if (!expiryWarningEnabled) {
            return false
        }
        val patchDate = parseSecurityPatchAsLocalDate()
        if (patchDate == null) {
            Log.w(TAG, "Failed to parse security patch date")
        }
        val warningDate = patchDate?.let { getWarningStartDate(it) }
        Log.d(TAG, "Current patch date: $patchDate, warning start date $warningDate (grace period $EXPIRY_GRACE_PERIOD)")

        if (!isPatchLevelExpiredOrUnparseable()) {
            return false
        }

        Log.d(TAG, "patch level expired; showing expiry warning on next keyguard unlock")
        keyguardViewMediator?.showPatchLevelExpiryWarningOnNextUnlock()

        val timestamp = System.currentTimeMillis()
        postNotification(
            this,
            timestamp,
            currentUser = userRepository?.getSelectedUserInfo()?.userHandle
        )

        return false
    }

    override fun onStopJob(params: JobParameters?): Boolean {
        return false
    }
}

private fun String.asUrlWithoutSchemeOrHash(): String {
    val uri = Uri.parse(this)
    val authority = uri.authority ?: ""
    val path = uri.path
    return "$authority$path".removePrefix("/")
}
