/*
 * NoDrive by Nourax
 *
 * SPDX-FileCopyrightText: 2026 Nourax
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package org.nourax.nodrive.backup

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class GoogleBackupPlacesTest {
    private fun ids(installed: Set<String>) = GoogleBackupPlaces.visible { it in installed }.map { it.id }

    private fun place(id: PlaceId) = GoogleBackupPlaces.all.first { it.id == id }

    @Test
    fun withNoOtherAppOnlyTheDeviceBackupIsListed() {
        assertEquals(listOf(PlaceId.DEVICE), ids(emptySet()))
    }

    @Test
    fun anAppsPlaceIsListedOnlyWhenThatAppIsInstalled() {
        assertEquals(listOf(PlaceId.PHOTOS, PlaceId.DEVICE), ids(setOf(GoogleBackupPlaces.PHOTOS_PACKAGE)))
        assertEquals(listOf(PlaceId.DEVICE, PlaceId.WHATSAPP), ids(setOf(GoogleBackupPlaces.WHATSAPP_PACKAGE)))
        assertEquals(
            listOf(PlaceId.DEVICE, PlaceId.WHATSAPP_BUSINESS),
            ids(setOf(GoogleBackupPlaces.WHATSAPP_BUSINESS_PACKAGE))
        )
        assertEquals(
            listOf(PlaceId.PHOTOS, PlaceId.DEVICE, PlaceId.WHATSAPP, PlaceId.WHATSAPP_BUSINESS),
            ids(
                setOf(
                    GoogleBackupPlaces.PHOTOS_PACKAGE,
                    GoogleBackupPlaces.WHATSAPP_PACKAGE,
                    GoogleBackupPlaces.WHATSAPP_BUSINESS_PACKAGE
                )
            )
        )
    }

    @Test
    fun theDeviceBackupChainStartsWithTheBackupScreenAndFallsBackToSettings() {
        assertEquals(
            listOf<IntentSpec>(
                IntentSpec.ViewAction("com.android.settings.BACKUP_SETTINGS"),
                IntentSpec.ViewAction("android.settings.SETTINGS")
            ),
            place(PlaceId.DEVICE).chain
        )
    }

    @Test
    fun anAppsChainLaunchesTheAppThenOpensItsDetailsPage() {
        assertEquals(
            listOf<IntentSpec>(
                IntentSpec.LaunchPackage("com.whatsapp"),
                IntentSpec.ViewAction("android.settings.APPLICATION_DETAILS_SETTINGS", "package:com.whatsapp")
            ),
            place(PlaceId.WHATSAPP).chain
        )
    }

    @Test
    fun aMissingTargetMovesToTheNextIntent() {
        val tried = mutableListOf<IntentSpec>()
        val result = PlaceLauncher { spec ->
            tried += spec
            if (spec is IntentSpec.LaunchPackage) false else true
        }.open(place(PlaceId.PHOTOS))
        assertEquals(LaunchResult.STARTED, result)
        assertEquals(2, tried.size)
    }

    @Test
    fun aThrowingTargetMovesToTheNextIntent() {
        var calls = 0
        val result = PlaceLauncher {
            calls++
            if (calls == 1) throw SecurityException("not allowed") else true
        }.open(place(PlaceId.DEVICE))
        assertEquals(LaunchResult.STARTED, result)
        assertEquals(2, calls)
    }

    @Test
    fun theFirstIntentThatStartsWins() {
        var calls = 0
        PlaceLauncher { calls++; true }.open(place(PlaceId.DEVICE))
        assertEquals(1, calls)
    }

    @Test
    fun whenEveryTargetFailsTheResultIsFailedWithoutAnException() {
        GoogleBackupPlaces.all.forEach { place ->
            assertEquals(LaunchResult.FAILED, PlaceLauncher { throw IllegalStateException("none") }.open(place))
            assertEquals(LaunchResult.FAILED, PlaceLauncher { false }.open(place))
        }
        assertTrue(GoogleBackupPlaces.all.all { it.chain.isNotEmpty() })
    }
}
