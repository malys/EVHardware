package com.evsuite.hardware.diag

import com.google.gson.JsonParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.security.SecureRandom
import java.util.Base64
import java.util.zip.Inflater
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * The paste format is the contract with a server this project does not own, and the failure
 * mode is silent: a wrong `adata`, a zlib header where raw DEFLATE was expected, or a
 * reordered field all upload cleanly and produce a paste nobody can open. So the wire body
 * is decrypted here with primitives spelled out independently of [PrivateBin] — if the two
 * ever drift, this fails instead of the person holding the link.
 */
class PrivateBinTest {

    private val config = PrivateBin.Config(
        baseUrl = "https://paste.example.org/",
        password = "correct horse",
        expire = "1hour",
        formatter = "markdown",
    )

    /** Deterministic bytes, so the test knows the key it must reproduce the fragment from. */
    private fun fixedRandom(): SecureRandom = object : SecureRandom() {
        private val source = java.util.Random(1234)
        override fun nextBytes(bytes: ByteArray) = source.nextBytes(bytes)
    }

    private fun keysOf(): Triple<ByteArray, ByteArray, ByteArray> {
        val random = fixedRandom()
        val key = ByteArray(32).also(random::nextBytes)
        val iv = ByteArray(16).also(random::nextBytes)
        val salt = ByteArray(8).also(random::nextBytes)
        return Triple(key, iv, salt)
    }

    @Test
    fun `the body sent is PrivateBin v2 markdown expiring in one hour`() {
        var sent = ""
        PrivateBin.paste("hello", config, fixedRandom()) { _, body ->
            sent = body
            """{"status":0,"id":"abc","url":"/?abc"}"""
        }

        val json = JsonParser.parseString(sent).asJsonObject
        assertEquals(2, json.get("v").asInt)
        assertEquals("1hour", json.getAsJsonObject("meta").get("expire").asString)

        val adata = json.getAsJsonArray("adata")
        val spec = adata[0].asJsonArray
        assertEquals(100_000, spec[2].asInt)
        assertEquals(256, spec[3].asInt)
        assertEquals(128, spec[4].asInt)
        assertEquals("aes", spec[5].asString)
        assertEquals("gcm", spec[6].asString)
        assertEquals("zlib", spec[7].asString)
        assertEquals("markdown", adata[1].asString)
    }

    @Test
    fun `the rendered link carries the key as a base58 fragment`() {
        val outcome = PrivateBin.paste("hello", config, fixedRandom()) { _, _ ->
            """{"status":0,"id":"abc","url":"/?abc"}"""
        }

        val (key, _, _) = keysOf()
        assertEquals(
            PrivateBin.Outcome.Ok("https://paste.example.org/?abc#" + base58(key)),
            outcome
        )
    }

    @Test
    fun `the text decrypts with the fragment key and the password`() {
        var sent = ""
        PrivateBin.paste("# title\n\nbody", config, fixedRandom()) { _, body ->
            sent = body
            """{"status":0,"id":"abc","url":"/?abc"}"""
        }

        val json = JsonParser.parseString(sent).asJsonObject
        val (key, iv, salt) = keysOf()

        // The AAD is the exact JSON text of adata as it was sent — not a re-serialisation.
        // That is where compatibility with the reader's browser is won or lost.
        val aad = sent.substringAfter("\"adata\":").substringBefore(",\"ct\":")
        val derived = pbkdf2(key + config.password.toByteArray(), salt)
        val plain = Cipher.getInstance("AES/GCM/NoPadding").run {
            init(Cipher.DECRYPT_MODE, SecretKeySpec(derived, "AES"), GCMParameterSpec(128, iv))
            updateAAD(aad.toByteArray())
            doFinal(Base64.getDecoder().decode(json.get("ct").asString))
        }

        val payload = JsonParser.parseString(String(rawInflate(plain))).asJsonObject
        assertEquals("# title\n\nbody", payload.get("paste").asString)
    }

    @Test
    fun `a server refusal reports its reason`() {
        val outcome = PrivateBin.paste("hello", config, fixedRandom()) { _, _ ->
            """{"status":1,"message":"paste too large"}"""
        }
        assertEquals(PrivateBin.Outcome.Failed("paste too large"), outcome)
    }

    @Test
    fun `a network failure does not surface as an exception`() {
        val outcome = PrivateBin.paste("hello", config, fixedRandom()) { _, _ ->
            throw java.net.UnknownHostException("paste.example.org")
        }
        assertTrue(outcome is PrivateBin.Outcome.Failed)
    }

    /**
     * Live check against the real instance. Skipped unless `EV_LIVE_PASTE=1`, because CI
     * has no business uploading anything — run it by hand after touching the format.
     */
    @Test
    fun `a real upload to paste chapril org is accepted`() {
        assumeTrue(System.getenv("EV_LIVE_PASTE") == "1")
        val outcome = PrivateBin.paste(
            "# EVHardware\n\nlive format check\n",
            PrivateBin.Config("https://paste.chapril.org/", "evtaskerR0ck\$", "1hour", "markdown")
        )
        assertTrue("upload refused: $outcome", outcome is PrivateBin.Outcome.Ok)
        println("live paste: " + (outcome as PrivateBin.Outcome.Ok).url)
    }

    // -------------------------------------------------------------------------

    private fun pbkdf2(secret: ByteArray, salt: ByteArray): ByteArray {
        val mac = Mac.getInstance("HmacSHA256").apply { init(SecretKeySpec(secret, "HmacSHA256")) }
        var u = mac.doFinal(salt + byteArrayOf(0, 0, 0, 1))
        val acc = u.copyOf()
        repeat(99_999) {
            u = mac.doFinal(u)
            for (i in acc.indices) acc[i] = (acc[i].toInt() xor u[i].toInt()).toByte()
        }
        return acc
    }

    private fun rawInflate(data: ByteArray): ByteArray {
        val inflater = Inflater(true)
        return try {
            inflater.setInput(data)
            val out = ByteArrayOutputStream()
            val buffer = ByteArray(8 * 1024)
            while (!inflater.finished()) out.write(buffer, 0, inflater.inflate(buffer))
            out.toByteArray()
        } finally {
            inflater.end()
        }
    }

    private fun base58(data: ByteArray): String {
        val alphabet = "123456789ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnopqrstuvwxyz"
        var value = java.math.BigInteger(1, data)
        val out = StringBuilder()
        while (value.signum() > 0) {
            val (q, r) = value.divideAndRemainder(java.math.BigInteger.valueOf(58))
            out.append(alphabet[r.toInt()])
            value = q
        }
        for (byte in data) {
            if (byte.toInt() != 0) break
            out.append(alphabet[0])
        }
        return out.reverse().toString()
    }
}
