/*
 * NoDrive by Nourax
 *
 * SPDX-FileCopyrightText: 2026 Nourax
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package org.nourax.nodrive.workspace

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WorkspaceIconsTest {
    @Test
    fun anIconRequestNeverCarriesACredential() {
        val names = WorkspaceIcons.headers().keys.map { it.lowercase() }
        assertFalse(names.toString(), names.contains("authorization"))
        assertFalse(names.toString(), names.contains("cookie"))
    }

    @Test
    fun svgIconsAreRecognisedByTheirPath() {
        assertTrue(WorkspaceIcons.isSvg("https://drive.nourax.org/apps/dashboard/img/dashboard.svg"))
        assertTrue(WorkspaceIcons.isSvg("https://drive.nourax.org/apps/x/img/X.SVG?v=2"))
    }

    @Test
    fun otherIconsAreNotSvg() {
        assertFalse(WorkspaceIcons.isSvg("https://drive.nourax.org/apps/x/img/x.png"))
        assertFalse(WorkspaceIcons.isSvg("https://drive.nourax.org/apps/x/img/x.png?name=a.svg"))
        assertFalse(WorkspaceIcons.isSvg("not a url"))
    }
}
