/*
 * NoDrive by Nourax
 *
 * SPDX-FileCopyrightText: 2026 Nourax
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package org.nourax.nodrive.conflicts

import com.nextcloud.client.preferences.SubFolderRule
import com.owncloud.android.datamodel.ArbitraryDataProviderImpl
import com.owncloud.android.datamodel.MediaFolderType
import com.owncloud.android.datamodel.SyncedFolder
import com.owncloud.android.files.services.NameCollisionPolicy
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.nourax.nodrive.backup.BackupSnapshot
import org.nourax.nodrive.backup.BackupState

/**
 * The one-time migration on the real Auto upload table: owned Ask and Skip rows become Smart, Rename and Overwrite stay,
 * rows of the user are never touched, and a second run changes nothing. No server.
 */
class ConflictsMigrationIT {
    private val support = ConflictsTestSupport()
    private val backup = support.backup
    private lateinit var account: String

    private val rename = NameCollisionPolicy.RENAME.serialize()
    private val overwrite = NameCollisionPolicy.OVERWRITE.serialize()
    private val skip = NameCollisionPolicy.SKIP.serialize()
    private val ask = NameCollisionPolicy.ASK_USER.serialize()

    @Before
    fun setUp() {
        account = backup.addLocalAccount().name
    }

    @After
    fun tearDown() {
        backup.wipeAllAccountsAndRowsCiEmulatorOnly()
        BackupState.forget(backup.context, account)
        // Leave the flag set, as a normal run of the app would.
        support.settings().ensureMigrated()
    }

    private fun store(path: String, policy: Int): Long = backup.provider.storeSyncedFolder(
        SyncedFolder(
            "/storage/emulated/0/$path",
            "/Phone backup/test/$path",
            true,
            false,
            false,
            false,
            account,
            2,
            policy,
            true,
            System.currentTimeMillis(),
            MediaFolderType.CUSTOM,
            false,
            SubFolderRule.YEAR_MONTH,
            false,
            SyncedFolder.NOT_SCANNED_YET
        )
    )

    private fun policyOf(id: Long) = backup.rows().first { it.id == id }.nameCollisionPolicyInt

    private fun clearFlag() = ArbitraryDataProviderImpl(backup.context)
        .deleteKeyForAccount("global", ConflictSettings.KEY_MIGRATED)

    @Test
    fun ownedAskAndSkipBecomeSmartRenameAndOverwriteStayAndUserRowsAreNeverTouched() {
        val ownedAsk = store("MigrationAsk", ask)
        val ownedSkip = store("MigrationSkip", skip)
        val ownedRename = store("MigrationRename", rename)
        val ownedOverwrite = store("MigrationOverwrite", overwrite)
        val userSkip = store("MigrationUserSkip", skip)
        val userAsk = store("MigrationUserAsk", ask)
        val userRename = store("MigrationUserRename", rename)
        val userOverwrite = store("MigrationUserOverwrite", overwrite)
        backup.state(account).write(
            BackupSnapshot(mediaOn = true, owned = setOf(ownedAsk, ownedSkip, ownedRename, ownedOverwrite))
        )
        clearFlag()

        support.settings().ensureMigrated()
        val expected = mapOf(
            ownedAsk to ask,
            ownedSkip to ask,
            ownedRename to rename,
            ownedOverwrite to overwrite,
            userSkip to skip,
            userAsk to ask,
            userRename to rename,
            userOverwrite to overwrite
        )
        expected.forEach { (id, policy) -> assertEquals("row $id after the first run", policy, policyOf(id)) }
        assertEquals(ConflictChoice.SMART, support.settings().choice(account))

        // A second run, also after the flag was cleared by hand: nothing moves again.
        support.settings().ensureMigrated()
        clearFlag()
        support.settings().ensureMigrated()
        expected.forEach { (id, policy) -> assertEquals("row $id after more runs", policy, policyOf(id)) }
        assertNotEquals("the owned Skip row really was changed", skip, policyOf(ownedSkip))
    }

    @Test
    fun aChoiceTheUserMakesLaterIsNeverUndoneByAnotherRun() {
        val owned = store("MigrationLater", ask)
        backup.state(account).write(BackupSnapshot(mediaOn = true, owned = setOf(owned)))
        clearFlag()
        support.settings().ensureMigrated()
        support.settings().setChoice(account, ConflictChoice.KEEP_NODRIVE)
        assertEquals(skip, policyOf(owned))
        support.settings().ensureMigrated()
        assertEquals(skip, policyOf(owned))
        assertTrue(ConflictChoice.KEEP_NODRIVE == support.settings().choice(account))
    }
}
