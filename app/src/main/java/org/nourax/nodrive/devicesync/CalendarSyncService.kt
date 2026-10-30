/*
 * NoDrive by Nourax
 *
 * SPDX-FileCopyrightText: 2026 Nourax
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package org.nourax.nodrive.devicesync

import android.app.Service
import android.content.Intent
import android.os.IBinder

/** Binds the phone's sync system to [CalendarSyncAdapter] (authority `com.android.calendar`). */
class CalendarSyncService : Service() {
    override fun onCreate() {
        super.onCreate()
        synchronized(lock) { if (adapter == null) adapter = CalendarSyncAdapter(applicationContext) }
    }

    override fun onBind(intent: Intent?): IBinder? = adapter?.syncAdapterBinder

    private companion object {
        val lock = Any()
        var adapter: CalendarSyncAdapter? = null
    }
}

/** Binds the phone's sync system to [ContactsSyncAdapter] (authority `com.android.contacts`). */
class ContactsSyncService : Service() {
    override fun onCreate() {
        super.onCreate()
        synchronized(lock) { if (adapter == null) adapter = ContactsSyncAdapter(applicationContext) }
    }

    override fun onBind(intent: Intent?): IBinder? = adapter?.syncAdapterBinder

    private companion object {
        val lock = Any()
        var adapter: ContactsSyncAdapter? = null
    }
}
