/*
 * NoDrive by Nourax
 *
 * SPDX-FileCopyrightText: 2026 Nourax
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package org.nourax.nodrive.backup

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** Data safety (design 2.8): the backup code never deletes, moves or overwrites a file, a row or a Drive item. */
class BackupSafetyTest {
    private val forbidden = listOf(
        "deleteSyncedFolder",
        "deleteSyncedFoldersNotInList",
        "deleteSyncFoldersForAccount",
        "\\.delete\\(",
        "deleteRecursively",
        "Files\\.delete",
        "renameTo\\(",
        "OVERWRITE",
        "LOCAL_BEHAVIOUR_MOVE",
        "LOCAL_BEHAVIOUR_DELETE",
        "LOCAL_BEHAVIOUR_COPY",
        "startLocalDeletion",
        "RemoveFileOperation",
        "RenameFileOperation",
        "MoveFileOperation",
        "CopyFileOperation",
        "contentResolver\\.delete",
        "resolver\\.delete"
    ).map { Regex(it) }

    private fun sources(): List<File> {
        val relative = "src/main/java/org/nourax/nodrive/backup"
        val dir = listOf(File(relative), File("app/$relative")).first { it.isDirectory }
        return dir.listFiles { file -> file.extension == "kt" }.orEmpty().toList()
    }

    @Test
    fun theBackupCodeIsFoundAndHasNoDeleteMoveOrOverwriteCall() {
        val files = sources()
        assertTrue("the backup sources were not found", files.size >= 10)
        val hits = files.flatMap { file ->
            file.readLines().withIndex().flatMap { (index, line) ->
                forbidden.filter { it.containsMatchIn(line) }.map { "${file.name}:${index + 1} ${it.pattern}" }
            }
        }
        assertTrue("forbidden calls: $hits", hits.isEmpty())
    }

    @Test
    fun theGuardWouldCatchAForbiddenCall() {
        assertTrue(forbidden.any { it.containsMatchIn("File(path).delete()") })
        assertTrue(forbidden.any { it.containsMatchIn("provider.deleteSyncedFolder(id)") })
    }
}
