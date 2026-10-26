/*
 * NoDrive by Nourax
 *
 * SPDX-FileCopyrightText: 2026 Nourax
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package org.nourax.nodrive.backup

import android.content.Intent

enum class BackupAction {
    MEDIA_ON,
    MEDIA_OFF,
    DOCS_ON,
    DOCS_OFF;

    val turnsOn: Boolean get() = this == MEDIA_ON || this == DOCS_ON
    val isMedia: Boolean get() = this == MEDIA_ON || this == MEDIA_OFF

    fun opposite(): BackupAction = when (this) {
        MEDIA_ON -> MEDIA_OFF
        MEDIA_OFF -> MEDIA_ON
        DOCS_ON -> DOCS_OFF
        DOCS_OFF -> DOCS_ON
    }

    companion object {
        const val EXTRA_ACTION = "nodrive_backup_action"

        fun from(intent: Intent): BackupAction? =
            intent.getStringExtra(EXTRA_ACTION)?.let { name -> entries.firstOrNull { it.name == name } }
    }
}
