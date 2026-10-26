/*
 * NoDrive by Nourax
 *
 * SPDX-FileCopyrightText: 2026 Nourax
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package org.nourax.nodrive.backup

import com.owncloud.android.datamodel.MediaFolderType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BackupPlannerTest {
    private val account = "a@h"
    private val otherAccount = "b@h"
    private val root = "/Phone backup/Pixel 8"
    private val camera = "/storage/emulated/0/DCIM/Camera"
    private val movies = "/storage/emulated/0/Movies"
    private val pictures = "/storage/emulated/0/Pictures"
    private val screenshots = "/storage/emulated/0/Pictures/Screenshots"
    private val documents = "/storage/emulated/0/Documents"
    private val download = "/storage/emulated/0/Download"

    private fun image(path: String, qualifies: Boolean = true) = MediaFolderInfo(path, MediaFolderType.IMAGE, qualifies)

    private fun video(path: String) = MediaFolderInfo(path, MediaFolderType.VIDEO)

    private fun row(id: Long, path: String, type: MediaFolderType, enabled: Boolean, owner: String = account) =
        FolderRow(id, path, type, owner, enabled)

    private fun autoAdd(
        folders: List<MediaFolderInfo>,
        docs: List<String>,
        rows: List<FolderRow>,
        snapshot: BackupSnapshot
    ) = BackupPlanner.autoAdd(folders, docs, rows, snapshot, account, root)

    /** Applies a plan the way BackupController does, so a second run can be planned on the result. */
    private class World(var rows: List<FolderRow>, var snapshot: BackupSnapshot, private var nextId: Long = 100) {
        fun apply(plan: BackupPlan) {
            val owned = plan.state.owned.toMutableSet()
            plan.ops.forEach { op ->
                when (op) {
                    is BackupOp.Create -> {
                        val id = nextId++
                        rows = rows + FolderRow(id, op.spec.path, op.spec.type, "a@h", true)
                        owned += id
                    }
                    is BackupOp.Enable -> rows = rows.map { if (it.id == op.rowId) it.copy(enabled = true) else it }
                    is BackupOp.Disable -> rows = rows.map { if (it.id == op.rowId) it.copy(enabled = false) else it }
                }
            }
            snapshot = plan.state.copy(owned = owned)
        }
    }

    private fun on(world: World, folders: List<MediaFolderInfo>): BackupPlan =
        BackupPlanner.enableMedia(folders, world.rows, world.snapshot, account, root).also { world.apply(it) }

    @Test
    fun switchingOnCreatesOneRowPerPathAndTypeWithTheAgreedSettings() {
        val plan = BackupPlanner.enableMedia(
            listOf(image(camera), video(camera), video(movies)),
            emptyList(),
            BackupSnapshot(),
            account,
            root
        )
        val creates = plan.ops.filterIsInstance<BackupOp.Create>().map { it.spec }
        assertEquals(3, plan.ops.size)
        assertEquals(
            setOf(
                FolderSpec(camera, MediaFolderType.IMAGE, "$root/DCIM/Camera"),
                FolderSpec(camera, MediaFolderType.VIDEO, "$root/DCIM/Camera"),
                FolderSpec(movies, MediaFolderType.VIDEO, "$root/Movies")
            ),
            creates.toSet()
        )
        assertTrue(creates.none { it.excludeHidden })
        assertTrue(plan.state.mediaOn)
        assertEquals(root, plan.state.root)
        assertEquals(setOf("1:$camera", "2:$camera", "2:$movies"), plan.state.seen)
    }

    @Test
    fun aFolderThatDoesNotQualifyIsNeitherCreatedNorSeen() {
        val plan = BackupPlanner.enableMedia(
            listOf(image("/storage/emulated/0/Music/Album", qualifies = false)),
            emptyList(),
            BackupSnapshot(),
            account,
            root
        )
        assertTrue(plan.ops.isEmpty())
        assertTrue(plan.state.seen.isEmpty())
    }

    @Test
    fun theUsersOwnEnabledRowIsLeftAloneAndNotOwned() {
        val rows = listOf(row(5, camera, MediaFolderType.IMAGE, enabled = true))
        val plan = BackupPlanner.enableMedia(listOf(image(camera)), rows, BackupSnapshot(), account, root)
        assertTrue(plan.ops.isEmpty())
        assertFalse(5L in plan.state.owned)
    }

    @Test
    fun aDisabledRowOfTheUserIsNotChangedNotOwnedAndReported() {
        val rows = listOf(row(5, camera, MediaFolderType.IMAGE, enabled = false))
        val plan = BackupPlanner.enableMedia(listOf(image(camera)), rows, BackupSnapshot(), account, root)
        assertTrue(plan.ops.isEmpty())
        assertFalse(5L in plan.state.owned)
        assertEquals(listOf(FolderSpec(camera, MediaFolderType.IMAGE, "$root/DCIM/Camera")), plan.leftAlone)
    }

    @Test
    fun aStorageVolumeAsABucketIsSkipped() {
        val plan = BackupPlanner.enableMedia(
            listOf(image("/storage/emulated/0"), image("/storage/1234-ABCD/"), image(camera)),
            emptyList(),
            BackupSnapshot(),
            account,
            root
        )
        assertEquals(1, plan.ops.size)
        assertEquals(setOf("1:$camera"), plan.state.seen)
    }

    @Test
    fun anOwnedRowTheUserTurnedOffComesBackWhenSwitchedOnAgain() {
        val rows = listOf(row(5, camera, MediaFolderType.IMAGE, enabled = false))
        val snapshot = BackupSnapshot(mediaOn = false, root = root, owned = setOf(5), seen = setOf("1:$camera"))
        val plan = BackupPlanner.enableMedia(listOf(image(camera)), rows, snapshot, account, root)
        assertEquals(listOf<BackupOp>(BackupOp.Enable(5)), plan.ops)
    }

    @Test
    fun aChildOfAnEnabledParentOfTheSameTypeIsSkipped() {
        val plan = BackupPlanner.enableMedia(
            listOf(image(screenshots), image(pictures), video(screenshots)),
            emptyList(),
            BackupSnapshot(),
            account,
            root
        )
        val created = plan.ops.filterIsInstance<BackupOp.Create>().map { it.spec.type to it.spec.path }
        assertEquals(
            setOf(MediaFolderType.IMAGE to pictures, MediaFolderType.VIDEO to screenshots),
            created.toSet()
        )
    }

    @Test
    fun anEnabledParentRowAlsoCoversANewChild() {
        val rows = listOf(row(9, pictures, MediaFolderType.IMAGE, enabled = true))
        val plan = BackupPlanner.enableMedia(listOf(image(screenshots)), rows, BackupSnapshot(), account, root)
        assertTrue(plan.ops.isEmpty())
    }

    @Test
    fun switchingOffDisablesOnlyOwnedEnabledMediaRows() {
        val rows = listOf(
            row(1, camera, MediaFolderType.IMAGE, enabled = true),
            row(2, movies, MediaFolderType.VIDEO, enabled = true),
            row(3, pictures, MediaFolderType.IMAGE, enabled = true),
            row(4, documents, MediaFolderType.CUSTOM, enabled = true),
            row(5, camera, MediaFolderType.VIDEO, enabled = true, owner = otherAccount)
        )
        val snapshot = BackupSnapshot(mediaOn = true, docsOn = true, owned = setOf(1, 2, 4, 5))
        val plan = BackupPlanner.disableMedia(rows, snapshot, account)
        assertEquals(setOf<BackupOp>(BackupOp.Disable(1), BackupOp.Disable(2)), plan.ops.toSet())
        assertFalse(plan.state.mediaOn)
        assertTrue(plan.state.docsOn)
        assertEquals(setOf(1L, 2L, 4L), plan.state.owned)
    }

    @Test
    fun noOperationCanDelete() {
        // An exhaustive when: a new kind of operation (for example a delete) does not compile until it is looked at.
        fun kind(op: BackupOp): String = when (op) {
            is BackupOp.Create -> "Create"
            is BackupOp.Enable -> "Enable"
            is BackupOp.Disable -> "Disable"
        }
        val ops = listOf(
            BackupOp.Create(FolderSpec(camera, MediaFolderType.IMAGE, "$root/DCIM/Camera")),
            BackupOp.Enable(1),
            BackupOp.Disable(2)
        )
        assertEquals(listOf("Create", "Enable", "Disable"), ops.map { kind(it) })
    }

    @Test
    fun switchingOnTwiceAndOffTwiceChangesNothingTheSecondTime() {
        val world = World(emptyList(), BackupSnapshot())
        val folders = listOf(image(camera), video(movies), image(pictures))
        assertEquals(3, on(world, folders).ops.size)
        assertTrue(on(world, folders).ops.isEmpty())

        val off = BackupPlanner.disableMedia(world.rows, world.snapshot, account)
        assertEquals(3, off.ops.size)
        world.apply(off)
        assertTrue(BackupPlanner.disableMedia(world.rows, world.snapshot, account).ops.isEmpty())
        assertEquals(3, world.rows.size)
    }

    @Test
    fun autoAddCreatesOnlyFoldersNeverSeenBefore() {
        val snapshot = BackupSnapshot(mediaOn = true, root = root, seen = setOf("1:$camera"))
        val plan = autoAdd(listOf(image(camera), image(movies)), emptyList(), emptyList(), snapshot)
        assertEquals(
            listOf<BackupOp>(BackupOp.Create(FolderSpec(movies, MediaFolderType.IMAGE, "$root/Movies"))),
            plan.ops
        )
        assertTrue("1:$movies" in plan.state.seen)
    }

    @Test
    fun autoAddDoesNotBringBackARemovedOrTurnedOffFolder() {
        val snapshot =
            BackupSnapshot(mediaOn = true, root = root, owned = setOf(5), seen = setOf("1:$camera", "1:$movies"))
        val rows = listOf(row(5, camera, MediaFolderType.IMAGE, enabled = false))
        val plan = autoAdd(listOf(image(camera), image(movies)), emptyList(), rows, snapshot)
        assertTrue(plan.ops.isEmpty())
    }

    @Test
    fun autoAddNeverReEnablesAnOwnedRowEvenIfTheFolderIsNew() {
        val snapshot = BackupSnapshot(mediaOn = true, root = root, owned = setOf(5))
        val rows = listOf(row(5, camera, MediaFolderType.IMAGE, enabled = false))
        val plan = autoAdd(listOf(image(camera)), emptyList(), rows, snapshot)
        assertTrue(plan.ops.isEmpty())
    }

    @Test
    fun autoAddDoesNothingWhileBothSwitchesAreOff() {
        val snapshot = BackupSnapshot(mediaOn = false, docsOn = false)
        val plan = autoAdd(listOf(image(camera)), listOf(documents), emptyList(), snapshot)
        assertTrue(plan.ops.isEmpty())
        assertEquals(snapshot, plan.state)
    }

    @Test
    fun autoAddSkipsAChildOfAnEnabledParent() {
        val snapshot = BackupSnapshot(mediaOn = true, root = root, seen = setOf("1:$pictures"), owned = setOf(9))
        val rows = listOf(row(9, pictures, MediaFolderType.IMAGE, enabled = true))
        val plan = autoAdd(listOf(image(pictures), image(screenshots)), emptyList(), rows, snapshot)
        assertTrue(plan.ops.isEmpty())
    }

    @Test
    fun documentsRowsAreCustomExcludeHiddenAndOnlyForFoldersThatExist() {
        val plan = BackupPlanner.enableDocs(listOf(documents), emptyList(), BackupSnapshot(), account, root)
        assertEquals(
            listOf<BackupOp>(
                BackupOp.Create(FolderSpec(documents, MediaFolderType.CUSTOM, "$root/Documents", excludeHidden = true))
            ),
            plan.ops
        )
        assertTrue(plan.state.docsOn)
        assertTrue(BackupPlanner.enableDocs(emptyList(), emptyList(), BackupSnapshot(), account, root).ops.isEmpty())
    }

    @Test
    fun documentsAndDownloadSiblingsAreBothCreated() {
        val plan = BackupPlanner.enableDocs(listOf(documents, download), emptyList(), BackupSnapshot(), account, root)
        assertEquals(2, plan.ops.size)
    }

    @Test
    fun switchingDocumentsOffTouchesOnlyDocumentsRows() {
        val rows = listOf(
            row(1, camera, MediaFolderType.IMAGE, enabled = true),
            row(2, documents, MediaFolderType.CUSTOM, enabled = true)
        )
        val snapshot = BackupSnapshot(mediaOn = true, docsOn = true, owned = setOf(1, 2))
        val plan = BackupPlanner.disableDocs(rows, snapshot, account)
        assertEquals(listOf<BackupOp>(BackupOp.Disable(2)), plan.ops)
        assertTrue(plan.state.mediaOn)
        assertFalse(plan.state.docsOn)
    }

    @Test
    fun autoAddAddsDocumentsLaterWhenTheFolderAppears() {
        val snapshot = BackupSnapshot(docsOn = true, root = root)
        val plan = autoAdd(emptyList(), listOf(documents), emptyList(), snapshot)
        assertEquals(1, plan.ops.size)
        assertTrue(plan.state.seen.contains("0:$documents"))
    }

    @Test
    fun idsOfRowsThatNoLongerExistAreDroppedFromOwned() {
        val snapshot = BackupSnapshot(mediaOn = true, owned = setOf(1, 77))
        val rows = listOf(row(1, camera, MediaFolderType.IMAGE, enabled = true))
        val plan = BackupPlanner.enableMedia(listOf(image(camera)), rows, snapshot, account, root)
        assertEquals(setOf(1L), plan.state.owned)
    }
}
