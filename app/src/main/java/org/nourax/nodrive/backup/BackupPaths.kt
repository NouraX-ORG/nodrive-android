/*
 * NoDrive by Nourax
 *
 * SPDX-FileCopyrightText: 2026 Nourax
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package org.nourax.nodrive.backup

/** The remote root of the backup and the mapping of a phone folder below it (design 2.4). Pure. */
object BackupPaths {
    const val BACKUP_FOLDER = "Phone backup"
    const val FALLBACK_DEVICE_NAME = "Android phone"
    const val SD_CARD_PREFIX = "SD card "
    const val OTHER_FOLDER = "Other"
    private const val SEPARATOR = "/"
    // Only what a file name or path cannot hold is replaced: letters of every script, digits and emoji stay readable.
    private val DEVICE_NAME_FORBIDDEN = Regex("[/\\\\:*?\"<>|\\u0000-\\u001F\\u007F]")
    private val SEGMENT_FORBIDDEN = Regex("[\\\\:*?\"<>|\\u0000-\\u001F]")
    private val EMULATED = Regex("^/storage/emulated/\\d+(?:/(.*))?$")
    private val VOLUME_ROOT = Regex("^/storage/(?:emulated/\\d+|[A-Za-z0-9]{4}-[A-Za-z0-9]{4})$")
    private val SD_CARD = Regex("^/storage/([A-Za-z0-9]{4}-[A-Za-z0-9]{4})(?:/(.*))?$")

    /** The device name as a folder name; [model] is tried when the name has no letter, digit or symbol left. */
    fun deviceFolderName(rawName: String?, model: String? = null): String =
        cleanDeviceName(rawName) ?: cleanDeviceName(model) ?: FALLBACK_DEVICE_NAME

    private fun cleanDeviceName(raw: String?): String? {
        val cleaned = raw.orEmpty().replace(DEVICE_NAME_FORBIDDEN, "_").trim().trimEnd('.', ' ')
        val readable = cleaned.codePoints().anyMatch {
            Character.isLetterOrDigit(it) || Character.getType(it) == Character.OTHER_SYMBOL.toInt()
        }
        return cleaned.takeIf { readable }
    }

    /** The root fixed at the first switch-on wins, so renaming the phone never splits the backup. */
    fun root(storedRoot: String?, deviceName: String?, model: String? = null): String =
        storedRoot?.takeIf { it.isNotBlank() }
            ?: "$SEPARATOR$BACKUP_FOLDER$SEPARATOR${deviceFolderName(deviceName, model)}"

    /** The top of a storage volume, for example /storage/emulated/0 or an SD card: not a folder to back up as a bucket. */
    fun isVolumeRoot(localPath: String): Boolean = VOLUME_ROOT.matches(localPath.trimEnd('/'))

    fun remotePath(root: String, localPath: String): String {
        val relative = relativeToVolume(localPath.trimEnd('/'))
        val segments = relative.split(SEPARATOR).filter { it.isNotEmpty() && it != "." && it != ".." }
        return (listOf(root.trimEnd('/')) + segments.map { cleanSegment(it) }).joinToString(SEPARATOR)
    }

    private fun relativeToVolume(path: String): String {
        EMULATED.matchEntire(path)?.let { return it.groupValues[1] }
        SD_CARD.matchEntire(path)?.let { return "$SD_CARD_PREFIX${it.groupValues[1]}$SEPARATOR${it.groupValues[2]}" }
        return "$OTHER_FOLDER$SEPARATOR${path.trimStart('/')}"
    }

    private fun cleanSegment(segment: String): String = segment.replace(SEGMENT_FORBIDDEN, "_").trimEnd('.', ' ')
        .ifEmpty { "_" }
}
