/*
 * NoDrive by Nourax
 *
 * SPDX-FileCopyrightText: 2026 Nourax
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package org.nourax.nodrive.conflicts

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Data safety (round 3): the conflict code never deletes a phone file, a Drive file or an upload row, and never moves a
 * file. The one overwrite in the app ("Keep the phone copy", an explicit choice) is named in one file only.
 */
class ConflictSafetyTest {
    private val forbidden = listOf(
        "\\.delete\\(",
        "deleteRecursively",
        "Files\\.delete",
        "renameTo\\(",
        "deleteSyncedFolder",
        "deleteSyncFoldersForAccount",
        "LOCAL_BEHAVIOUR_MOVE",
        "LOCAL_BEHAVIOUR_DELETE",
        "LOCAL_BEHAVIOUR_COPY",
        "RemoveFileOperation",
        "RenameFileOperation",
        "MoveFileOperation",
        "CopyFileOperation",
        "removeUpload",
        "removeUploads",
        "removeAllUploads",
        "removeUserUploads",
        "deleteByRemotePathAndAccountName",
        "deleteByLocalRemotePath",
        "removeEntityFromUploadEntities",
        "contentResolver\\.delete",
        "resolver\\.delete",
        "removeFile\\("
    ).map { Regex(it) }

    private fun sources(): List<File> {
        val relative = "src/main/java/org/nourax/nodrive/conflicts"
        val dir = listOf(File(relative), File("app/$relative")).first { it.isDirectory }
        return dir.listFiles { file -> file.extension == "kt" }.orEmpty().toList()
    }

    private fun code(file: File) = file.readLines().withIndex().filterNot { (_, line) ->
        val trimmed = line.trim()
        trimmed.startsWith("*") || trimmed.startsWith("/*") || trimmed.startsWith("//")
    }

    @Test
    fun theConflictCodeIsFoundAndHasNoDeleteMoveOrRemoveCall() {
        val files = sources()
        assertTrue("the conflict sources were not found", files.size >= 10)
        val hits = files.flatMap { file ->
            code(file).flatMap { (index, line) ->
                forbidden.filter { it.containsMatchIn(line) }.map { "${file.name}:${index + 1} ${it.pattern}" }
            }
        }
        assertTrue("forbidden calls: $hits", hits.isEmpty())
    }

    @Test
    fun overwriteIsNamedOnlyInTheChoiceFile() {
        val hits = sources().filter { it.name != "ConflictChoice.kt" }.flatMap { file ->
            code(file).filter { (_, line) -> line.contains("OVERWRITE") }.map { "${file.name}:${it.index + 1}" }
        }
        assertTrue("OVERWRITE outside ConflictChoice.kt: $hits", hits.isEmpty())
    }

    @Test
    fun everyRequeueKeepsThePhoneFile() {
        val worker = sources().first { it.name == "ResolveAllWorker.kt" }.readText()
        assertTrue(worker.contains("LOCAL_BEHAVIOUR_FORGET"))
    }

    @Test
    fun theGuardWouldCatchAForbiddenCall() {
        assertTrue(forbidden.any { it.containsMatchIn("File(path).delete()") })
        assertTrue(forbidden.any { it.containsMatchIn("storage.removeUpload(id)") })
        assertTrue(forbidden.any { it.containsMatchIn("RemoveFileOperation(file)") })
    }
}
