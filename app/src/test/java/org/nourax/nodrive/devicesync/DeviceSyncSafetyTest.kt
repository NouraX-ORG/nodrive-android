/*
 * NoDrive by Nourax
 *
 * SPDX-FileCopyrightText: 2026 Nourax
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package org.nourax.nodrive.devicesync

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Data safety of device sync (plan 4.6), as a guard over the sources of the package and over the write policy:
 *  - no file of the phone or of the Drive is deleted; the Drive is only written with one new safety file;
 *  - the phone's Calendar and Contacts are written only by the two provider stores, through [ProviderScope];
 *  - a DELETE leaves the app only for a calendar or contact object, with `If-Match`;
 *  - upstream's backup never expires (contacts_backup_expire is -1 in the nodrive build).
 */
class DeviceSyncSafetyTest {
    private fun root(relative: String): File = listOf(File(relative), File("app/$relative")).first { it.exists() }

    private fun sources(): List<File> =
        root("src/main/java/org/nourax/nodrive/devicesync").walkTopDown().filter { it.isFile && it.extension == "kt" }.toList()

    private fun code(file: File) = file.readLines().withIndex().filterNot { (_, line) ->
        val trimmed = line.trim()
        trimmed.startsWith("*") || trimmed.startsWith("/*") || trimmed.startsWith("//") || trimmed.startsWith("import ")
    }

    private fun hits(pattern: Regex, except: (File) -> Boolean = { false }): List<String> =
        sources().filterNot(except).flatMap { file ->
            code(file).filter { (_, line) -> pattern.containsMatchIn(line) }.map { "${file.name}:${it.index + 1}" }
        }

    @Test
    fun theSourcesAreFound() {
        assertTrue("sources not found: ${sources().size}", sources().size >= 20)
    }

    @Test
    fun noFileOfThePhoneOrTheDriveIsDeleted() {
        assertEquals(emptyList<String>(), hits(Regex("""deleteRecursively|RemoveFileOperation|RemoveFilesOperation|FileUtils\.delete|\bFile\(.*\)\.delete""")))
    }

    @Test
    fun theProvidersAreWrittenOnlyByTheTwoProviderStores() {
        val writes = Regex("""\bresolver\.(insert|update|delete|bulkInsert|applyBatch)\(|contentResolver\.(insert|update|delete|bulkInsert|applyBatch)\(""")
        assertEquals(emptyList<String>(), hits(writes, except = { it.name.endsWith("ProviderStore.kt") }))
        assertTrue(hits(writes).isNotEmpty())
    }

    @Test
    fun everyProviderAddressOfAWriterComesFromTheScope() {
        // The stores never name a provider address themselves: they use ProviderScope (our account name and type, sync adapter).
        assertEquals(
            emptyList<String>(),
            hits(Regex("""CONTENT_URI"""), except = { it.name == "ProviderScope.kt" || it.name == "BackupGate.kt" })
        )
        val scope = File(root("src/main/java/org/nourax/nodrive/devicesync"), "ProviderScope.kt").readText()
        assertTrue(scope.contains("CALLER_IS_SYNCADAPTER"))
        assertTrue(scope.contains("ACCOUNT_NAME") && scope.contains("ACCOUNT_TYPE"))
    }

    @Test
    fun deleteIsSentOnlyByTheDavClientAndAlwaysWithAnETag() {
        assertEquals(emptyList<String>(), hits(Regex(""""DELETE""""), except = { it.name == "CardDavClient.kt" || it.name == "DeviceSyncWritePolicy.kt" }))
        val client = File(root("src/main/java/org/nourax/nodrive/devicesync/dav"), "CardDavClient.kt").readText()
        assertTrue(client.contains("If-Match"))
        assertTrue(client.contains("If-None-Match"))
    }

    @Test
    fun theSafetyFileIsNeverReplaced() {
        val copy = File(root("src/main/java/org/nourax/nodrive/devicesync/contacts"), "RemovedContactsCopy.kt").readText()
        assertTrue(copy.contains("If-None-Match"))
        assertFalse(copy.contains("\"DELETE\""))
    }

    @Test
    fun noHostNameAndNoServerSoftwareNameIsWrittenInTheCode() {
        assertEquals(emptyList<String>(), hits(Regex("""nourax\.org|https?://""", RegexOption.IGNORE_CASE)))
        assertEquals(emptyList<String>(), hits(Regex("""next\s*cloud""", RegexOption.IGNORE_CASE)))
    }

    @Test
    fun theUpstreamBackupNeverExpiresInTheNodriveBuild() {
        val main = root("src/main/res/values/setup.xml").readText()
        assertTrue(main.contains("""<integer name="contacts_backup_expire">-1</integer>"""))
        val nodrive = File(root("src/nodrive/res/values"), "setup.xml").readText()
        assertFalse("the nodrive build must not change the expiry", nodrive.contains("contacts_backup_expire") && !nodrive.contains("""name="contacts_backup_expire">-1<"""))
    }

    // ---- the write policy

    @Test
    fun thePolicyAllowsOnlyCalendarAndContactObjectsAndTheSafetyFile() {
        val ics = "/remote.php/dav/calendars/ali/work/abc.ics"
        val vcf = "/remote.php/dav/addressbooks/users/ali/contacts/abc.vcf"
        assertTrue(DeviceSyncWritePolicy.allows("PUT", ics))
        assertTrue(DeviceSyncWritePolicy.allows("DELETE", ics))
        assertTrue(DeviceSyncWritePolicy.allows("PUT", vcf))
        assertTrue(DeviceSyncWritePolicy.allows("DELETE", vcf))
        assertTrue(DeviceSyncWritePolicy.allows("REPORT", "/remote.php/dav/calendars/ali/work/"))
        assertTrue(DeviceSyncWritePolicy.allows("PUT", "remote.php/dav/files/ali/.Contacts-Backup/removed-contacts-20261030T120000Z.vcf"))
        assertTrue(DeviceSyncWritePolicy.allows("MKCOL", "remote.php/dav/files/ali/.Contacts-Backup/"))
    }

    @Test
    fun thePolicyRefusesEverythingElse() {
        // never a delete of a file of the Drive, never any other file write, never a collection delete
        assertFalse(DeviceSyncWritePolicy.allows("DELETE", "remote.php/dav/files/ali/.Contacts-Backup/removed-contacts-1.vcf"))
        assertFalse(DeviceSyncWritePolicy.allows("DELETE", "remote.php/dav/files/ali/Documents/a.txt"))
        assertFalse(DeviceSyncWritePolicy.allows("PUT", "remote.php/dav/files/ali/Documents/a.txt"))
        assertFalse(DeviceSyncWritePolicy.allows("PUT", "remote.php/dav/files/ali/.Contacts-Backup/other.txt"))
        assertFalse(DeviceSyncWritePolicy.allows("DELETE", "/remote.php/dav/calendars/ali/work/"))
        assertFalse(DeviceSyncWritePolicy.allows("DELETE", "/remote.php/dav/addressbooks/users/ali/contacts/"))
        assertFalse(DeviceSyncWritePolicy.allows("MKCOL", "remote.php/dav/files/ali/Other/"))
        assertFalse(DeviceSyncWritePolicy.allows("MOVE", "/remote.php/dav/calendars/ali/work/abc.ics"))
        assertFalse(DeviceSyncWritePolicy.allows("PUT", "/remote.php/dav/calendars/ali/work/../../x/abc.ics"))
        assertFalse(DeviceSyncWritePolicy.allows("POST", "ocs/v2.php/apps/nc_gantt/api/v1/tasks/1"))
    }
}
