/*
 * NoDrive by Nourax
 *
 * SPDX-FileCopyrightText: 2026 Nourax
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package org.nourax.nodrive.reports

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Every category of the scrubbing rule (round 3 contract, B) with fixtures. All secrets here are made up. */
class ScrubberTest {
    private val fakeAppPassword = "Zq8Rt-3KmPx-L9vBn-W2cYd-H7sGe"
    private val fakeLongToken = "a1B2".repeat(10)
    private val fakeBase64 = "Tm90QVJlYWxLZXlCdXRMb25nRW5vdWdoVG9Mb29rTGlrZU9uZVdpdGhNaXhlZENhc2VBbmREaWdpdHMxMjM0NTY3ODk="

    private fun gone(secret: String, input: String) {
        val out = Scrubber.scrub(input)
        assertFalse("\"$secret\" survived in: $out", out.contains(secret))
        assertEquals("not idempotent for: $input", out, Scrubber.scrub(out))
    }

    @Test
    fun authorizationHeaderValueIsRemoved() {
        gone("SuperSecretValue", "> Authorization: Bearer SuperSecretValue123")
        gone("dXNlcjpwYXNzd29yZA", "authorization: Basic dXNlcjpwYXNzd29yZA==")
        assertEquals("Authorization: <removed>", Scrubber.scrub("Authorization: Bearer abcdef123456"))
        assertEquals("Proxy-Authorization: <removed>", Scrubber.scrub("Proxy-Authorization: Basic abcdef123456"))
    }

    @Test
    fun basicAndBearerValuesAreRemovedAnywhere() {
        assertEquals("sent Bearer <removed> ok", Scrubber.scrub("sent Bearer eyJhbGciOiJIUzI1NiJ9.payload.sig ok"))
        assertEquals("sent Basic <removed> ok", Scrubber.scrub("sent Basic dXNlcjpwYXNzd29yZA== ok"))
    }

    @Test
    fun appPasswordsAndLongTokensAreRemoved() {
        gone(fakeAppPassword, "app password $fakeAppPassword for the account")
        gone(fakeLongToken, "token is $fakeLongToken now")
        gone(fakeBase64, "blob $fakeBase64 end")
    }

    @Test
    fun cookieHeadersAreRemovedWhole() {
        gone("PASSPHRASE1", "Cookie: oc_sessionPassphrase=PASSPHRASE1; nc_session_id=SESSION2; other=ok")
        gone("SESSION2", "Cookie: oc_sessionPassphrase=PASSPHRASE1; nc_session_id=SESSION2; other=ok")
        gone("lax9", "Set-Cookie: __Host-nc_sameSiteCookielax=lax9; path=/; httponly")
        assertEquals("Set-Cookie: <removed>", Scrubber.scrub("Set-Cookie: nc_token=tok; path=/"))
    }

    @Test
    fun sessionCookiePairsAreRemovedInsideText() {
        val text = "jar has oc_sessionPassphrase=PASS3 and nc_session_id=SESS4 and ocabcdef123456=INST5 and __Host-x=HOST6 done"
        val out = Scrubber.scrub(text)
        listOf("PASS3", "SESS4", "INST5", "HOST6").forEach { assertFalse("$it survived: $out", out.contains(it)) }
        assertTrue(out.startsWith("jar has oc_sessionPassphrase=<removed> and nc_session_id=<removed>"))
        assertEquals(out, Scrubber.scrub(out))
    }

    @Test
    fun urlQueryValuesOfSecretNamesAreRemoved() {
        val url = "GET https://example.test/index.php/x?token=TOK1&name=holiday.jpg&password=PW2&pass=P3&secret=S4&key=K5&sig=G6&code=C7&state=T8&dir=/Photos"
        val out = Scrubber.scrub(url)
        listOf("TOK1", "PW2", "P3", "S4", "K5", "G6", "C7", "T8").forEach { assertFalse("$it survived: $out", out.contains(it)) }
        assertTrue(out.contains("name=holiday.jpg"))
        assertTrue(out.contains("dir=/Photos"))
        assertEquals(out, Scrubber.scrub(out))
    }

    @Test
    fun passwordInsideAUrlIsRemoved() {
        gone("hunter2", "connecting to https://citest:hunter2@example.test/remote.php/dav")
        assertTrue(Scrubber.scrub("connecting to https://u:p@example.test/x").contains("example.test/x"))
    }

    @Test
    fun namedSecretsInKeyValueAndJsonAreRemoved() {
        gone("hunter2", "login password=hunter2 done")
        gone("hunter3", """{"password":"hunter3","user":"anna"}""")
        gone("abc999", "access_token: abc999")
        assertTrue(Scrubber.scrub("""{"password":"hunter3","user":"anna"}""").contains("\"user\":\"anna\""))
    }

    @Test
    fun privateKeysAndEndToEndMaterialAreRemoved() {
        val pem = "-----BEGIN PRIVATE KEY-----\nMIIEvQIBADANBgkqhkiG9w0BAQEFAASC\nKgwggSkAgEAAoIBAQC7\n-----END PRIVATE KEY-----"
        val out = Scrubber.scrub("before\n$pem\nafter")
        assertFalse(out.contains("MIIEvQIBADAN"))
        assertFalse(out.contains("BEGIN"))
        assertTrue(out.startsWith("before\n") && out.endsWith("\nafter"))
        gone("MIIBVgIBADAN", """{"privateKey":"MIIBVgIBADAN", "other": 1}""")
        gone("apple banana cherry", "mnemonic: apple banana cherry delta echo")
        gone("KEYDATA", "metadataKeys = KEYDATA")
        gone("ENC123", """encryptedKey: "ENC123" """)
        // a key block that is cut off still goes, with its base64 lines
        gone("CUTOFFLINE1234567890ab", "-----BEGIN RSA PRIVATE KEY-----\nCUTOFFLINE1234567890ab\nCUTOFFLINE1234567890cd\n")
    }

    @Test
    fun emailAddressesBecomePlaceholders() {
        assertEquals("mail <email> and <email>.", Scrubber.scrub("mail anna.k+x@example.org and bob_1@mail.example.co.uk."))
    }

    @Test
    fun fileAndFolderNamesAreKept() {
        val line = "Upload of /Phone backup/Camera/IMG_2024-05-01 holiday (2).jpg to /Documents/Taxes 2025/return.pdf failed"
        assertEquals(line, Scrubber.scrub(line))
        val path = "/storage/emulated/0/DCIM/Camera/Some Folder/another folder name/photo.jpg"
        assertEquals(path, Scrubber.scrub(path))
    }

    @Test
    fun scrubbingTwiceChangesNothingMore() {
        val all = listOf(
            "Authorization: Bearer x1y2z3w4v5",
            "Cookie: oc_sessionPassphrase=A; nc_session_id=B",
            "?token=T&sig=S&name=n.jpg",
            fakeAppPassword,
            fakeLongToken,
            fakeBase64,
            "https://u:p@h.example/x",
            "password=pw token: tk mnemonic: a b c",
            "x@y.example",
            "-----BEGIN CERTIFICATE-----\nAAAA\n-----END CERTIFICATE-----"
        ).joinToString("\n")
        val once = Scrubber.scrub(all)
        assertEquals(once, Scrubber.scrub(once))
        assertEquals(listOf("a", "b"), Scrubber.scrubLines(listOf("a", "b")))
    }
}
