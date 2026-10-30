/*
 * NoDrive by Nourax
 *
 * SPDX-FileCopyrightText: 2026 Nourax
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package org.nourax.nodrive.devicesync.ui

import android.text.format.DateFormat
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.owncloud.android.R
import org.nourax.nodrive.devicesync.model.RemoteCollection
import java.util.Date

/** What the screen can ask the activity and the model to do. */
class DeviceSyncActions(
    val continueIntro: () -> Unit,
    val allowAccess: () -> Unit,
    val openSettings: () -> Unit,
    val toggle: (String) -> Unit,
    val chooseDone: () -> Unit,
    val retryChoices: () -> Unit,
    val retryBackup: () -> Unit,
    val finishFirstSync: () -> Unit,
    val syncNow: () -> Unit,
    val chooseAgain: () -> Unit,
    val askStop: () -> Unit,
    val confirmStop: () -> Unit,
    val cancelStop: () -> Unit,
    val applyAgain: (Long) -> Unit,
    val keepServer: (Long) -> Unit
)

@Composable
fun DeviceSyncScreen(model: DeviceSyncModel, actions: DeviceSyncActions) {
    val ui by model.state.collectAsState()
    Column(
        Modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(16.dp)
            .testTag("ds_screen_" + ui.step.name.lowercase())
    ) {
        when (ui.step) {
            Step.INTRO -> Intro(actions)
            Step.PERMISSIONS -> Permissions(ui, actions)
            Step.CHOOSE -> Choose(ui, actions)
            Step.BACKUP -> Backup(ui, actions)
            Step.FIRST_SYNC -> FirstSync(ui, actions)
            Step.STATUS -> Status(ui, actions)
        }
    }
    if (ui.stopQuestion) {
        AlertDialog(
            onDismissRequest = actions.cancelStop,
            text = { Text(stringResource(R.string.nodrive_device_sync_stop_question)) },
            confirmButton = {
                TextButton(onClick = actions.confirmStop, modifier = Modifier.testTag("ds_stop_confirm")) {
                    Text(stringResource(R.string.nodrive_device_sync_stop_and_remove))
                }
            },
            dismissButton = {
                TextButton(onClick = actions.cancelStop, modifier = Modifier.testTag("ds_stop_cancel")) {
                    Text(stringResource(R.string.nodrive_device_sync_cancel))
                }
            }
        )
    }
}

@Composable
private fun calendarCount(n: Int): String = pluralStringResource(R.plurals.nodrive_device_sync_n_calendars, n, n)

@Composable
private fun bookCount(n: Int): String = pluralStringResource(R.plurals.nodrive_device_sync_n_books, n, n)

@Composable
private fun Title(text: String) {
    Text(text, style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(bottom = 12.dp))
}

@Composable
private fun Body(text: String, tag: String? = null) {
    Text(
        text, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurface,
        modifier = Modifier
            .padding(bottom = 12.dp)
            .let { if (tag != null) it.testTag(tag) else it }
    )
}

@Composable
private fun Intro(actions: DeviceSyncActions) {
    Title(stringResource(R.string.nodrive_device_sync_title))
    Body(stringResource(R.string.nodrive_device_sync_intro), "ds_intro_text")
    Body(stringResource(R.string.nodrive_device_sync_intro_tasks))
    Button(onClick = actions.continueIntro, modifier = Modifier.testTag("ds_intro_continue")) {
        Text(stringResource(R.string.nodrive_device_sync_continue))
    }
}

@Composable
private fun Permissions(ui: DeviceSyncUi, actions: DeviceSyncActions) {
    Title(stringResource(R.string.nodrive_device_sync_title))
    Body(stringResource(R.string.nodrive_device_sync_permissions_text), "ds_permissions_text")
    if (ui.permissionsDenied) {
        Body(stringResource(R.string.nodrive_device_sync_permissions_denied), "ds_permissions_denied")
        Button(onClick = actions.openSettings, modifier = Modifier.testTag("ds_open_settings")) {
            Text(stringResource(R.string.nodrive_device_sync_open_settings))
        }
    } else {
        Button(onClick = actions.allowAccess, modifier = Modifier.testTag("ds_allow_access")) {
            Text(stringResource(R.string.nodrive_device_sync_allow))
        }
    }
}

@Composable
private fun offText(reason: String): String? = when (reason) {
    "birthdays" -> stringResource(R.string.nodrive_device_sync_off_birthdays)
    "subscription" -> stringResource(R.string.nodrive_device_sync_off_subscription)
    "readonly" -> stringResource(R.string.nodrive_device_sync_off_readonly)
    else -> null
}

@Composable
private fun Choose(ui: DeviceSyncUi, actions: DeviceSyncActions) {
    Title(stringResource(R.string.nodrive_device_sync_choose_title))
    when {
        ui.loading -> {
            Row(verticalAlignment = Alignment.CenterVertically) {
                CircularProgressIndicator(Modifier.size(24.dp))
                Text(
                    stringResource(R.string.nodrive_device_sync_choose_loading),
                    modifier = Modifier.padding(start = 12.dp).testTag("ds_choose_loading")
                )
            }
        }
        ui.offline -> {
            Body(stringResource(R.string.nodrive_device_sync_choose_offline), "ds_choose_offline")
            Button(onClick = actions.retryChoices, modifier = Modifier.testTag("ds_choose_retry")) {
                Text(stringResource(R.string.nodrive_device_sync_try_again))
            }
        }
        ui.items.isEmpty() -> Body(stringResource(R.string.nodrive_device_sync_choose_none), "ds_choose_none")
        else -> {
            Group(ui.items.filter { it.kind == RemoteCollection.CALENDAR }, R.string.nodrive_device_sync_choose_calendars, actions)
            Group(ui.items.filter { it.kind == RemoteCollection.CONTACTS }, R.string.nodrive_device_sync_choose_contacts, actions)
            Spacer(Modifier.height(8.dp))
            Button(
                onClick = actions.chooseDone, enabled = ui.items.any { it.checked },
                modifier = Modifier.testTag("ds_choose_continue")
            ) { Text(stringResource(R.string.nodrive_device_sync_continue)) }
        }
    }
}

@Composable
private fun Group(items: List<CollectionItem>, title: Int, actions: DeviceSyncActions) {
    if (items.isEmpty()) return
    Text(stringResource(title), style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 8.dp, bottom = 4.dp))
    for (item in items) {
        Row(
            Modifier
                .fillMaxWidth()
                .clickable { actions.toggle(item.href) }
                .testTag("ds_item_" + item.href.trimEnd('/').substringAfterLast('/')),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Checkbox(checked = item.checked, onCheckedChange = { actions.toggle(item.href) })
            if (item.kind == RemoteCollection.CALENDAR) {
                Box(Modifier.size(12.dp).clip(CircleShape).background(Color(item.color)))
                Spacer(Modifier.size(8.dp))
            }
            Column(Modifier.padding(vertical = 4.dp)) {
                Text(item.name, style = MaterialTheme.typography.bodyLarge)
                if (item.readOnly) {
                    Text(
                        stringResource(R.string.nodrive_device_sync_read_only), style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                offText(item.offReason)?.let {
                    Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
}

@Composable
private fun Backup(ui: DeviceSyncUi, actions: DeviceSyncActions) {
    Title(stringResource(R.string.nodrive_device_sync_title))
    Body(stringResource(R.string.nodrive_device_sync_backup_text), "ds_backup_text")
    when {
        ui.backupFailed -> {
            Body(stringResource(R.string.nodrive_device_sync_backup_failed), "ds_backup_failed")
            Button(onClick = actions.retryBackup, modifier = Modifier.testTag("ds_backup_retry")) {
                Text(stringResource(R.string.nodrive_device_sync_try_again))
            }
        }
        ui.backupDone -> Body(stringResource(R.string.nodrive_device_sync_backup_done), "ds_backup_done")
        else -> Row(verticalAlignment = Alignment.CenterVertically) {
            CircularProgressIndicator(Modifier.size(24.dp))
            Text(
                stringResource(R.string.nodrive_device_sync_backup_running),
                modifier = Modifier.padding(start = 12.dp).testTag("ds_backup_running")
            )
        }
    }
}

@Composable
private fun FirstSync(ui: DeviceSyncUi, actions: DeviceSyncActions) {
    Title(stringResource(R.string.nodrive_device_sync_title))
    Body(stringResource(R.string.nodrive_device_sync_first_sync_text), "ds_first_sync_text")
    when {
        ui.firstSyncRunning -> CircularProgressIndicator(Modifier.size(24.dp).testTag("ds_first_sync_running"))
        ui.firstSyncFailed -> {
            Body(stringResource(R.string.nodrive_device_sync_first_sync_failed), "ds_first_sync_failed")
            Button(onClick = actions.finishFirstSync, modifier = Modifier.testTag("ds_first_sync_continue")) {
                Text(stringResource(R.string.nodrive_device_sync_continue))
            }
        }
        else -> {
            Body(
                stringResource(R.string.nodrive_device_sync_first_sync_done, calendarCount(ui.calendarsOnPhone), bookCount(ui.addressBooksOnPhone)),
                "ds_first_sync_done"
            )
            Button(onClick = actions.finishFirstSync, modifier = Modifier.testTag("ds_first_sync_continue")) {
                Text(stringResource(R.string.nodrive_device_sync_continue))
            }
        }
    }
}

@Composable
private fun Status(ui: DeviceSyncUi, actions: DeviceSyncActions) {
    val context = LocalContext.current
    Title(stringResource(R.string.nodrive_device_sync_title))
    val lastText = if (ui.lastSyncUtc > 0) {
        stringResource(R.string.nodrive_device_sync_synced_at, DateFormat.getTimeFormat(context).format(Date(ui.lastSyncUtc)))
    } else {
        stringResource(R.string.nodrive_device_sync_never)
    }
    Body(if (ui.syncing) stringResource(R.string.nodrive_device_sync_syncing) else lastText, "ds_last_sync")
    Body(stringResource(R.string.nodrive_device_sync_on_summary, calendarCount(ui.calendarsOnPhone), bookCount(ui.addressBooksOnPhone)), "ds_counts")
    if (ui.autoSyncOff) Body(stringResource(R.string.nodrive_device_sync_auto_sync_off), "ds_auto_sync_off")
    if (ui.waiting > 0) Body(pluralStringResource(R.plurals.nodrive_device_sync_waiting, ui.waiting, ui.waiting), "ds_waiting")
    if (ui.noCalendarStorage) Body(stringResource(R.string.nodrive_device_sync_no_calendar_storage), "ds_no_storage")
    if (ui.stopRefused) Body(stringResource(R.string.nodrive_device_sync_stop_refused), "ds_stop_refused")
    if (ui.applyFailed) Body(stringResource(R.string.nodrive_device_sync_apply_failed), "ds_apply_failed")
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Button(onClick = actions.syncNow, enabled = !ui.syncing, modifier = Modifier.testTag("ds_sync_now")) {
            Text(stringResource(R.string.nodrive_device_sync_sync_now))
        }
    }
    if (ui.notSaved.isNotEmpty()) {
        HorizontalDivider(Modifier.padding(vertical = 12.dp))
        Text(stringResource(R.string.nodrive_device_sync_not_saved), style = MaterialTheme.typography.titleMedium)
        Body(stringResource(R.string.nodrive_device_sync_not_saved_explain))
        for (n in ui.notSaved) {
            Column(Modifier.padding(bottom = 8.dp).testTag("ds_not_saved_" + n.id)) {
                val kind = stringResource(
                    if (n.kind == RemoteCollection.CONTACTS) R.string.nodrive_device_sync_kind_contacts else R.string.nodrive_device_sync_kind_calendar
                )
                Text(n.title.ifEmpty { kind }, fontWeight = FontWeight.Medium)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = { actions.applyAgain(n.id) }, modifier = Modifier.testTag("ds_apply_again_" + n.id)) {
                        Text(stringResource(R.string.nodrive_device_sync_apply_again))
                    }
                    TextButton(onClick = { actions.keepServer(n.id) }, modifier = Modifier.testTag("ds_keep_server_" + n.id)) {
                        Text(stringResource(R.string.nodrive_device_sync_keep_server))
                    }
                }
            }
        }
    }
    HorizontalDivider(Modifier.padding(vertical = 12.dp))
    OutlinedButton(onClick = actions.chooseAgain, modifier = Modifier.testTag("ds_choose_again")) {
        Text(stringResource(R.string.nodrive_device_sync_choose_again))
    }
    TextButton(onClick = actions.askStop, modifier = Modifier.testTag("ds_stop")) {
        Text(stringResource(R.string.nodrive_device_sync_stop))
    }
}
