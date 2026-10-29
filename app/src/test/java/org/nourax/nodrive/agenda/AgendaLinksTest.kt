/*
 * NoDrive by Nourax
 *
 * SPDX-FileCopyrightText: 2026 Nourax
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package org.nourax.nodrive.agenda

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.nourax.nodrive.agenda.logic.AgendaBuilder
import org.nourax.nodrive.agenda.model.Home
import org.nourax.nodrive.agenda.ui.AgendaLinks

/** "Open in ..." goes to the account's own server only (design 8; plan 1.4). */
class AgendaLinksTest {
    private val base = "https://drive.example.test"
    private val items = AgendaBuilder.build(loadAliAnswered()).items

    private fun url(key: String) = AgendaLinks.itemUrl(items.byKey(key)!!, base, UTC)

    @Test
    fun aTaskOpensItsProjectAndTask() {
        assertEquals("$base/index.php/apps/nc_gantt/?project=1&task=101", url("gantt:task:101"))
    }

    @Test
    fun aCardOpensItsCard() {
        assertEquals("$base/index.php/apps/deck/card/502", url("deck:card:502"))
    }

    @Test
    fun aTodoOpensItsListAndItem() {
        assertEquals("$base/index.php/apps/tasks/calendars/personal/tasks/td1.ics", url("cal:personal/td1.ics"))
    }

    @Test
    fun anEventOpensItsDayInTheCalendarApp() {
        assertEquals("$base/index.php/apps/calendar/timeGridDay/2026-10-28", url("cal:work/call.ics"))
    }

    @Test
    fun aMilestoneOpensItsProject() {
        assertEquals("$base/index.php/apps/nc_gantt/?project=1", url("gantt:ms:301"))
    }

    @Test
    fun theAppsThemselvesAreOpenedFromTheBanners() {
        assertEquals("$base/index.php/apps/nc_gantt/", AgendaLinks.appUrl(Home.GANTT, "$base/"))
        assertEquals("$base/index.php/apps/deck/", AgendaLinks.appUrl(Home.BOARD, base))
        assertEquals("$base/index.php/apps/calendar/", AgendaLinks.appUrl(Home.CALENDAR, base))
        assertEquals("$base/index.php/apps/tasks/", AgendaLinks.appUrl(Home.TASKS, base))
    }

    @Test
    fun everyLinkStaysOnTheAccountsServer() {
        for (item in items) {
            assertTrue(item.key, AgendaLinks.itemUrl(item, base, UTC).startsWith("$base/index.php/apps/"))
        }
    }
}
