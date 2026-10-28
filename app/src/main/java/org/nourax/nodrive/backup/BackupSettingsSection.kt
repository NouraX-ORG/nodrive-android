/*
 * NoDrive by Nourax
 *
 * SPDX-FileCopyrightText: 2026 Nourax
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
@file:Suppress("DEPRECATION")

package org.nourax.nodrive.backup

import android.app.Activity
import android.app.Application
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.preference.Preference
import android.preference.PreferenceActivity
import android.preference.PreferenceCategory
import android.preference.PreferenceScreen
import android.preference.SwitchPreference
import com.nextcloud.client.account.User
import com.nextcloud.client.core.ClockImpl
import com.nextcloud.client.preferences.AppPreferencesImpl
import com.owncloud.android.R
import com.owncloud.android.datamodel.ArbitraryDataProviderImpl
import com.owncloud.android.datamodel.MediaFolderType
import com.owncloud.android.datamodel.SyncedFolderProvider
import com.owncloud.android.lib.common.utils.Log_OC
import com.owncloud.android.ui.ThemeableSwitchPreference
import com.owncloud.android.ui.activity.SyncedFoldersActivity
import com.owncloud.android.utils.theme.ViewThemeUtils
import org.nourax.nodrive.conflicts.ConflictSettingsItem

/**
 * The "Backup" section at the top of Settings (design 3.4), built in code so upstream's preferences.xml stays as it is.
 * The switches do not change anything themselves: they open [BackupSetupActivity], which asks for permissions first.
 */
object BackupSettingsSection {
    const val KEY_MEDIA = "nodrive_backup_media_switch"
    const val KEY_DOCS = "nodrive_backup_docs_switch"
    const val KEY_FOLDERS = "nodrive_backup_folders"
    const val KEY_STOP_GOOGLE = "nodrive_backup_stop_google"
    const val KEY_MOVE_GOOGLE = "nodrive_backup_move_google"
    private const val ORDER_FIRST = -1
    private const val TAG = "BackupSettingsSection"

    /** Never throws: Settings must open even if the Backup section cannot be built. */
    @JvmStatic
    @Suppress("TooGenericExceptionCaught")
    fun install(activity: PreferenceActivity, screen: PreferenceScreen, user: User?, viewThemeUtils: ViewThemeUtils) {
        try {
            build(activity, screen, user, viewThemeUtils)
        } catch (e: RuntimeException) {
            Log_OC.w(TAG, "backup section not shown: ${e.javaClass.simpleName}")
        }
    }

    private fun build(
        activity: PreferenceActivity,
        screen: PreferenceScreen,
        user: User?,
        viewThemeUtils: ViewThemeUtils
    ) {
        if (!activity.resources.getBoolean(R.bool.nodrive_backup) || user == null || user.isAnonymous) return
        val category = PreferenceCategory(activity).apply {
            title = activity.getString(R.string.nodrive_backup_category)
            order = ORDER_FIRST
        }
        screen.addPreference(category)
        viewThemeUtils.files.themePreferenceCategory(category)

        val media = switchPreference(activity, KEY_MEDIA, R.string.nodrive_backup_media_title, BackupAction.MEDIA_ON)
        val docs = switchPreference(activity, KEY_DOCS, R.string.nodrive_backup_docs_title, BackupAction.DOCS_ON)
        category.addPreference(media)
        category.addPreference(docs)
        // "If a file is already in NoDrive": one choice for every folder the backup owns (round 3, A).
        val conflicts = ConflictSettingsItem.addTo(activity, category, user.accountName)
        category.addPreference(
            linkPreference(
                activity,
                KEY_FOLDERS,
                R.string.nodrive_backup_folders_title,
                R.string.nodrive_backup_folders_summary,
                Intent(activity, SyncedFoldersActivity::class.java)
            )
        )
        category.addPreference(
            linkPreference(
                activity,
                KEY_STOP_GOOGLE,
                R.string.nodrive_backup_stop_google_title,
                R.string.nodrive_backup_stop_google_summary,
                Intent(activity, StopGoogleBackupActivity::class.java)
            )
        )
        category.addPreference(
            linkPreference(
                activity,
                KEY_MOVE_GOOGLE,
                R.string.nodrive_backup_move_google_title,
                R.string.nodrive_backup_move_google_summary,
                Intent(activity, MoveFromGoogleActivity::class.java)
            )
        )

        val refreshNow: () -> Unit = {
            refresh(activity, user, media, docs)
            conflicts?.let { ConflictSettingsItem.refresh(activity, it, user.accountName) }
        }
        refreshNow()
        followResume(activity, refreshNow)
    }

    private fun switchPreference(
        activity: Activity,
        key: String,
        title: Int,
        turnOn: BackupAction
    ): SwitchPreference = ThemeableSwitchPreference(activity).apply {
        this.key = key
        setTitle(title)
        isPersistent = false
        setOnPreferenceChangeListener { _, newValue ->
            val action = if (newValue == true) turnOn else turnOn.opposite()
            activity.startActivity(BackupSetupActivity.intent(activity, action))
            false
        }
    }

    private fun linkPreference(activity: Activity, key: String, title: Int, summary: Int, target: Intent) =
        Preference(activity).apply {
            this.key = key
            setTitle(title)
            setSummary(summary)
            setOnPreferenceClickListener {
                activity.startActivity(target)
                true
            }
        }

    private fun refresh(activity: Activity, user: User, media: SwitchPreference, docs: SwitchPreference) {
        val snapshot = BackupState(user.accountName, ArbitraryDataProviderImpl(activity)).read()
        media.isChecked = snapshot.mediaOn
        docs.isChecked = snapshot.docsOn
        if (snapshot.mediaOn) {
            val count = enabledOwnedMediaFolders(activity, user.accountName, snapshot)
            media.summary = activity.resources.getQuantityString(
                R.plurals.nodrive_backup_media_summary_on,
                count,
                count,
                BackupPaths.root(snapshot.root, null)
            )
        } else {
            media.setSummary(R.string.nodrive_backup_media_summary_off)
        }
        docs.setSummary(
            if (snapshot.docsOn) R.string.nodrive_backup_docs_summary_on else R.string.nodrive_backup_docs_summary_off
        )
    }

    private fun enabledOwnedMediaFolders(context: Context, account: String, snapshot: BackupSnapshot): Int {
        val provider = SyncedFolderProvider(context.contentResolver, AppPreferencesImpl.fromContext(context), ClockImpl())
        val media = setOf(MediaFolderType.IMAGE, MediaFolderType.VIDEO)
        return SyncedFolderStore(provider, ClockImpl()).rows()
            .filter { it.account == account && it.enabled && it.type in media && it.id in snapshot.owned }
            .map { it.path }
            .distinct()
            .size
    }

    /** Re-reads the state whenever this Settings screen comes back, so no hook is needed in SettingsActivity. */
    private fun followResume(activity: Activity, onResume: () -> Unit) {
        val application = activity.application
        application.registerActivityLifecycleCallbacks(object : Application.ActivityLifecycleCallbacks {
            override fun onActivityResumed(resumed: Activity) {
                if (resumed === activity) onResume()
            }

            override fun onActivityDestroyed(destroyed: Activity) {
                if (destroyed === activity) application.unregisterActivityLifecycleCallbacks(this)
            }

            override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) = Unit
            override fun onActivityStarted(activity: Activity) = Unit
            override fun onActivityPaused(activity: Activity) = Unit
            override fun onActivityStopped(activity: Activity) = Unit
            override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit
        })
    }
}
