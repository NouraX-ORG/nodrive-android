/*
 * NoDrive by Nourax
 *
 * SPDX-FileCopyrightText: 2026 Nourax
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package org.nourax.nodrive.backup

/** The folders of the phone the backup works on. */
interface DeviceFolders {
    fun mediaFolders(): List<MediaFolderInfo>

    /** The phone's Documents and Download folders that exist (empty without file access). */
    fun documentFolders(): List<String>
}
