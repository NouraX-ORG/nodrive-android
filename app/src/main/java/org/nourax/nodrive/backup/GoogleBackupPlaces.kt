/*
 * NoDrive by Nourax
 *
 * SPDX-FileCopyrightText: 2026 Nourax
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package org.nourax.nodrive.backup

import android.provider.Settings
import com.owncloud.android.R

enum class PlaceId { PHOTOS, DEVICE, WHATSAPP, WHATSAPP_BUSINESS }

/** A place where Google or WhatsApp backs up the phone's saved media: the user switches it off there. */
data class Place(
    val id: PlaceId,
    /** The place is listed only when this app is installed; null means always listed. */
    val requiredPackage: String?,
    /** Tried in this order; the first that starts wins. */
    val chain: List<IntentSpec>,
    val titleRes: Int,
    val stepsRes: Int
)

object GoogleBackupPlaces {
    const val PHOTOS_PACKAGE = "com.google.android.apps.photos"
    const val WHATSAPP_PACKAGE = "com.whatsapp"
    const val WHATSAPP_BUSINESS_PACKAGE = "com.whatsapp.w4b"

    /** AOSP Settings' backup screen; it forwards to Google's backup page on Google phones. Best effort per maker. */
    const val BACKUP_SETTINGS_ACTION = "com.android.settings.BACKUP_SETTINGS"

    private fun appChain(packageName: String) = listOf(
        IntentSpec.LaunchPackage(packageName),
        IntentSpec.ViewAction(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, "package:$packageName")
    )

    val all: List<Place> = listOf(
        Place(
            PlaceId.PHOTOS,
            PHOTOS_PACKAGE,
            appChain(PHOTOS_PACKAGE),
            R.string.nodrive_backup_place_photos_title,
            R.string.nodrive_backup_place_photos_steps
        ),
        Place(
            PlaceId.DEVICE,
            null,
            listOf(IntentSpec.ViewAction(BACKUP_SETTINGS_ACTION), IntentSpec.ViewAction(Settings.ACTION_SETTINGS)),
            R.string.nodrive_backup_place_device_title,
            R.string.nodrive_backup_place_device_steps
        ),
        Place(
            PlaceId.WHATSAPP,
            WHATSAPP_PACKAGE,
            appChain(WHATSAPP_PACKAGE),
            R.string.nodrive_backup_place_whatsapp_title,
            R.string.nodrive_backup_place_whatsapp_steps
        ),
        Place(
            PlaceId.WHATSAPP_BUSINESS,
            WHATSAPP_BUSINESS_PACKAGE,
            appChain(WHATSAPP_BUSINESS_PACKAGE),
            R.string.nodrive_backup_place_whatsapp_business_title,
            R.string.nodrive_backup_place_whatsapp_steps
        )
    )

    fun visible(isInstalled: (String) -> Boolean): List<Place> =
        all.filter { it.requiredPackage == null || isInstalled(it.requiredPackage) }
}
