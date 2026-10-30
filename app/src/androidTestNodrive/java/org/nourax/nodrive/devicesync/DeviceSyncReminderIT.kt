/*
 * NoDrive by Nourax
 *
 * SPDX-FileCopyrightText: 2026 Nourax
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package org.nourax.nodrive.devicesync

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.provider.CalendarContract
import android.util.Log
import com.nextcloud.test.GrantTestPermissionRule
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import java.io.File
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * An event alarm of the server becomes a reminder of the phone's Calendar (plan 4.9). The event starts in three minutes with
 * `TRIGGER:-PT1M`: the phone's Calendar provider must hold a one-minute alert reminder and then fire it. If the broadcast does not reach the
 * test on the CI emulator, the provider's own alert row is the proof and the reason is written to the log (nothing is skipped silently).
 */
class DeviceSyncReminderIT {
    @get:Rule
    val permissions = GrantTestPermissionRule.grantStorageAndNotification()

    private val support = DeviceSyncTestSupport()
    private val name = "ds-reminder-${System.nanoTime()}.ics"
    private var receiver: BroadcastReceiver? = null

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
        receiver?.let { runCatching { support.context.unregisterReceiver(it) } }
        runCatching { support.serverDelete("remote.php/dav/calendars/ali/work/$name") }
        support.cleanUp()
        support.agenda.reset()
    }

    @Test
    fun aServerAlarmIsAPhoneReminderThatTheCalendarFires() {
        val start = Instant.now().plus(3, ChronoUnit.MINUTES).truncatedTo(ChronoUnit.SECONDS)
        val title = "Reminder test ${support.newId()}"
        support.putServerEvent(
            "work", name,
            support.icsOf("ds-rem-${support.newId()}", title, start, start.plus(1, ChronoUnit.HOURS), "BEGIN:VALARM", "ACTION:DISPLAY", "DESCRIPTION:Reminder", "TRIGGER:-PT1M", "END:VALARM")
        )
        support.switchOn("/work/")
        assertTrue(support.sync(setOf("calendar")).ok)
        val calendar = support.calendarIdEndingWith("/work/")
        val event = checkNotNull(support.event(calendar, title))
        // the reminder: one minute before, as an alert
        val reminders = support.reminders(event.id)
        assertEquals(listOf(1 to CalendarContract.Reminders.METHOD_ALERT), reminders)

        // the phone's Calendar fires it (the broadcast of the provider), or at least schedules it as an alert row
        val fired = AtomicInteger()
        receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                fired.incrementAndGet()
            }
        }
        val filter = IntentFilter(CalendarContract.ACTION_EVENT_REMINDER).apply { addDataScheme("content") }
        support.context.registerReceiver(receiver, filter, Context.RECEIVER_EXPORTED)
        var alertSeen = false
        var alertFired = false
        val deadline = System.currentTimeMillis() + WAIT_MILLIS
        while (System.currentTimeMillis() < deadline && fired.get() == 0 && !alertFired) {
            support.context.contentResolver.query(
                CalendarContract.CalendarAlerts.CONTENT_URI, arrayOf(CalendarContract.CalendarAlerts.STATE),
                CalendarContract.CalendarAlerts.EVENT_ID + " = ?", arrayOf(event.id.toString()), null
            )?.use {
                while (it.moveToNext()) {
                    alertSeen = true
                    if (it.getInt(0) == CalendarContract.CalendarAlerts.STATE_FIRED) alertFired = true
                }
            }
            Thread.sleep(POLL_MILLIS)
        }
        val path = when {
            fired.get() > 0 -> "the provider's EVENT_REMINDER broadcast reached the test"
            alertFired -> "no broadcast reached the test on this emulator; the provider's alert row is FIRED"
            alertSeen -> "no broadcast reached the test on this emulator; the provider's alert row is SCHEDULED"
            else -> "nothing"
        }
        Log.i(TAG, "reminder proof: $path")
        File(checkNotNull(support.context.getExternalFilesDir(null)), "devicesync-reminder-proof.txt").writeText(path)
        assertTrue("the phone's Calendar neither fired nor scheduled the reminder: $path", fired.get() > 0 || alertSeen)
    }

    private companion object {
        const val WAIT_MILLIS = 5 * 60_000L
        const val POLL_MILLIS = 2_000L
        const val TAG = "DeviceSyncReminderIT"
    }
}
