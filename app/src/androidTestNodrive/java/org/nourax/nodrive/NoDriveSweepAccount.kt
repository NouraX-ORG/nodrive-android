/*
 * NoDrive by Nourax
 *
 * SPDX-FileCopyrightText: 2026 Nourax
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package org.nourax.nodrive

import android.accounts.Account
import android.accounts.AccountManager
import androidx.test.platform.app.InstrumentationRegistry
import com.owncloud.android.MainApp
import com.owncloud.android.lib.common.accounts.AccountUtils
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

/**
 * Adds a local stand-in account so tools/ci/sweep.py can screenshot the signed-in screens in CI,
 * where real sign-in is impossible. The sweep switches the emulator to airplane mode first, so the
 * app never sends this account to the server. Skipped unless the sweep passes "-e nodriveSweep addAccount".
 */
class NoDriveSweepAccount {
    @Test
    fun addAccount() {
        assumeTrue(
            "only for tools/ci/sweep.py",
            InstrumentationRegistry.getArguments().getString(SWEEP_ARGUMENT) == "addAccount"
        )
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val accountManager = AccountManager.get(context)
        val type = MainApp.getAccountType(context)
        accountManager.getAccountsByType(type).forEach { accountManager.removeAccountExplicitly(it) }

        val account = Account("$USER@$HOST", type)
        assertTrue(accountManager.addAccountExplicitly(account, "offline-sweep", null))
        accountManager.setUserData(account, AccountUtils.Constants.KEY_OC_BASE_URL, "https://$HOST")
        accountManager.setUserData(account, AccountUtils.Constants.KEY_USER_ID, USER)
        accountManager.setUserData(account, AccountUtils.Constants.KEY_DISPLAY_NAME, "Demo User")
    }

    companion object {
        private const val SWEEP_ARGUMENT = "nodriveSweep"
        private const val USER = "demo"
        private const val HOST = "drive.nourax.org"
    }
}
