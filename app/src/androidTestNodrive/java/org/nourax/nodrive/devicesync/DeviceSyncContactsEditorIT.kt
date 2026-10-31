/*
 * NoDrive by Nourax
 *
 * SPDX-FileCopyrightText: 2026 Nourax
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package org.nourax.nodrive.devicesync

import android.content.ComponentName
import android.content.ContentUris
import android.content.Intent
import android.net.Uri
import android.provider.ContactsContract
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.UiObject2
import androidx.test.uiautomator.Until
import com.nextcloud.test.GrantTestPermissionRule
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import java.io.File
import java.util.regex.Pattern

/**
 * The phone's own Contacts app (the real editor, driven with UI Automator) against the NoDrive account. The structure file of the account
 * type decides whether that app treats the account as writable: without an edit schema it is read only, and an edit is silently saved into
 * a new contact of the default account instead. The other contacts tests edit through the content provider and could never see that.
 *
 * Needs the throwaway server (the contact is made there) and an app on the phone that can edit contacts (on the CI emulator image that is Google Contacts, `com.google.android.contacts`; the
 * window dumps of the run show it; AOSP Contacts and Samsung Contacts are not run in CI).
 */
class DeviceSyncContactsEditorIT {
    @get:Rule
    val permissions = GrantTestPermissionRule.grantStorageAndNotification()

    private val support = DeviceSyncTestSupport()
    private val device = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())
    private val book = "remote.php/dav/addressbooks/users/ali/contacts/"
    private val cardName = "ds-editor-person.vcf"
    private val person = "Editor Person"
    private var editorPackage: String? = null

    @Before
    fun setUp() {
        assumeTrue("needs the throwaway server", support.agenda.hasServer)
        support.grantPermissions()
        support.agenda.reset()
        support.signInAsAli()
        DeviceSyncAccounts.forget(support.context, support.account.name)
    }

    @After
    fun tearDown() {
        editorPackage?.let { shell("am force-stop $it") }
        runCatching { device.pressHome() }
        runCatching { support.serverDelete(book + cardName) }
        support.cleanUp()
        support.agenda.reset()
    }

    private fun shell(command: String): String = support.agenda.backup.shell(command)

    private fun rawContactsNamed(name: String): List<String> {
        val out = ArrayList<String>()
        support.context.contentResolver.query(
            ContactsContract.RawContacts.CONTENT_URI,
            arrayOf(ContactsContract.RawContacts.ACCOUNT_TYPE, ContactsContract.RawContacts.ACCOUNT_NAME, ContactsContract.RawContacts.DIRTY),
            ContactsContract.RawContacts.DISPLAY_NAME_PRIMARY + " = ? AND " + ContactsContract.RawContacts.DELETED + " = 0", arrayOf(name), null
        )?.use { c -> while (c.moveToNext()) out += "${c.getString(0)} / ${c.getString(1)} / dirty=${c.getInt(2)}" }
        return out
    }

    /** The first app of the phone that handles [action] on [uri] (the Contacts app that comes with the phone is preferred). */
    private fun editorComponent(action: String, uri: String, type: String): String {
        val answer = shell("cmd package query-activities --brief -a $action -d $uri -t $type")
        val found = answer.lines().map { it.trim() }.filter { Regex("^[\\w.]+/[\\w.$]+$").matches(it) }
        assertTrue("no app on this phone handles $action ($type). The phone said: $answer", found.isNotEmpty())
        return found.firstOrNull { it.startsWith("com.android.contacts/") } ?: found.first()
    }

    private fun launch(action: String, uri: String, type: String): String {
        val component = editorComponent(action, uri, type)
        val pkg = component.substringBefore('/')
        editorPackage = pkg
        for (p in listOf("READ_CONTACTS", "WRITE_CONTACTS", "GET_ACCOUNTS", "READ_PHONE_STATE", "POST_NOTIFICATIONS")) {
            shell("pm grant $pkg android.permission.$p")
        }
        shell("am force-stop $pkg")
        println("devicesync editor: $action is handled by $component")
        support.context.startActivity(
            Intent(action).setComponent(ComponentName.unflattenFromString(component)).setDataAndType(Uri.parse(uri), type)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
        )
        assertTrue("the window of $pkg did not come up", device.wait(Until.hasObject(By.pkg(pkg)), UI_WAIT))
        return pkg
    }

    private fun capture(name: String) {
        val folder = checkNotNull(support.context.getExternalFilesDir(null))
        runCatching { device.takeScreenshot(File(folder, "phonecontacts-$name.png")) }
        runCatching { device.dumpWindowHierarchy(File(folder, "phonecontacts-$name.xml")) }
    }

    private fun onScreen(pkg: String): List<String> = runCatching {
        device.findObjects(By.pkg(pkg)).flatMap { node -> runCatching { listOfNotNull(node.text, node.contentDescription) }.getOrDefault(emptyList()) }
            .filter { it.isNotBlank() }.distinct()
    }.getOrDefault(emptyList())

    private fun clickAny(vararg what: androidx.test.uiautomator.BySelector): Boolean {
        for (selector in what) {
            val node: UiObject2? = device.findObject(selector)
            if (node != null && runCatching { node.click() }.isSuccess) return true
        }
        return false
    }

    private fun contactUri(rawId: Long): String {
        var contactId = -1L
        support.context.contentResolver.query(
            ContactsContract.RawContacts.CONTENT_URI, arrayOf(ContactsContract.RawContacts.CONTACT_ID),
            ContactsContract.RawContacts._ID + " = ?", arrayOf(rawId.toString()), null
        )?.use { if (it.moveToFirst()) contactId = it.getLong(0) }
        assertTrue("the raw contact is in a contact", contactId > 0)
        val lookup = ContactsContract.Contacts.getLookupUri(
            support.context.contentResolver, ContentUris.withAppendedId(ContactsContract.Contacts.CONTENT_URI, contactId)
        )
        return checkNotNull(lookup) { "no lookup uri" }.toString()
    }

    private fun serverCard(): String? =
        support.listing(book).map { support.serverText(it.trimStart('/')) }.firstOrNull { it.contains("FN:$person") }

    /**
     * Design 10, contacts two-way, in the phone's real Contacts app: a NoDrive contact is edited there (the number is changed and saved), the
     * raw contact of the NoDrive account becomes dirty, no copy appears in another account, and the next sync puts the new number on the
     * server card, which keeps the line the phone has no place for.
     */
    @Test
    fun aContactEditedInThePhonesContactsAppIsSavedInNoDriveAndReachesTheServer() {
        val card = listOf(
            "BEGIN:VCARD", "VERSION:3.0", "UID:ds-editor-person", "FN:$person", "N:Person;Editor;;;", "TEL;TYPE=CELL:+49 30 4444444",
            "EMAIL;TYPE=HOME:editor@example.test", "X-NOURAX-TEST:keep this", "END:VCARD"
        ).joinToString("\r\n") + "\r\n"
        val put = support.raw.request("PUT", book + cardName, card, mapOf("Content-Type" to "text/vcard; charset=utf-8"))
        assertTrue("the test card was not accepted: ${put.status}", put.status in 200..299)
        support.switchOn("/contacts/")
        assertTrue("the first sync of the address book", support.sync(setOf("contacts")).ok)
        val row = support.contactsStore().rows().first { it.contact.displayName == person }
        assertFalse("a synced contact is not dirty", row.dirty)
        assertEquals("only the NoDrive contact exists before the edit: ${rawContactsNamed(person)}", 1, rawContactsNamed(person).size)

        val pkg = launch(Intent.ACTION_EDIT, contactUri(row.id), ContactsContract.Contacts.CONTENT_ITEM_TYPE)
        val field = device.wait(Until.findObject(By.clazz("android.widget.EditText").text(Pattern.compile(".*4444444.*"))), UI_WAIT)
        capture("edit-opened")
        assertNotNull("the editor shows no field with the number. On screen: ${onScreen(pkg)}", field)
        field.text = "+49 30 7777777"
        capture("edit-typed")
        val saved = clickAny(By.res(pkg, "editor_menu_save_button"), By.text("Save"), By.desc("Save"), By.textContains("Save"))
        assertTrue("no Save button in the editor. On screen: ${onScreen(pkg)}", saved)

        // the edit is saved into the NoDrive contact and marks it dirty (that is what makes the sync send it)
        val after = support.agenda.backup.waitFor("the NoDrive contact holds the new number: ${rawContactsNamed(person)}", SAVE_WAIT) {
            support.contactsStore().rows().firstOrNull { r -> r.id == row.id && r.contact.phones.any { p -> p.value.filter(Char::isDigit).endsWith("7777777") } }
        }
        capture("edit-saved")
        assertTrue("the raw contact of the NoDrive account is dirty after the edit", after.dirty)
        val copies = rawContactsNamed(person)
        assertEquals("no copy of the contact appeared in another account: $copies", 1, copies.size)

        assertTrue("the sync after the edit", support.sync(setOf("contacts")).ok)
        val sent = checkNotNull(serverCard()) { "the card is gone from the server" }
        assertTrue("the new number is on the server card: $sent", sent.contains("7777777"))
        assertFalse("the old number is gone from the server card: $sent", sent.contains("4444444"))
        assertTrue("the line the phone has no place for is kept: $sent", sent.contains("X-NOURAX-TEST:keep this"))
        assertFalse("the contact is not dirty after the sync", support.contactsStore().rows().first { it.id == row.id }.dirty)
    }

    /** A new contact: the phone's editor offers NoDrive as a place to save it (it lists only accounts that are writable there). */
    @Test
    fun theEditorForANewContactOffersNoDrive() {
        support.switchOn("/contacts/")
        assertTrue("the first sync of the address book", support.sync(setOf("contacts")).ok)
        val pkg = launch(Intent.ACTION_INSERT, ContactsContract.Contacts.CONTENT_URI.toString(), ContactsContract.Contacts.CONTENT_TYPE)
        device.wait(Until.hasObject(By.clazz("android.widget.EditText")), UI_WAIT)
        capture("insert-opened")
        val label = Pattern.compile(".*(NoDrive|" + Pattern.quote(support.account.name) + ").*", Pattern.CASE_INSENSITIVE)
        fun offered() = onScreen(pkg).any { label.matcher(it).matches() }
        if (!offered()) {
            // the editor shows the account it saves to, and a list of the others when that is tapped
            clickAny(By.res(Pattern.compile(".*account.*")).clickable(true), By.res(Pattern.compile(".*account.*")), By.textContains("Device"))
            device.waitForIdle()
            device.wait(Until.hasObject(By.text(label)), SAVE_WAIT)
            capture("insert-accounts")
        }
        assertTrue("the editor for a new contact does not offer NoDrive. On screen: ${onScreen(pkg)}", offered())
    }

    private companion object {
        const val UI_WAIT = 30_000L
        const val SAVE_WAIT = 20_000L
    }
}
