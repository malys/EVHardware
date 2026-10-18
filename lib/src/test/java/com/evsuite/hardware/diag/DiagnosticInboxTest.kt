package com.evsuite.hardware.diag

import com.google.gson.JsonParser
import java.util.Base64
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DiagnosticInboxTest {

    private val artifact = DiagnosticInbox.Artifact(
        app = "EVTasker", version = "2.17.0", name = "evtasker-diagnostic.txt",
        createdAtMs = 1_792_323_000_000L, content = "report ✓".toByteArray(),
    )

    @Test fun `only a plain https address is an inbox`() {
        assertNull(DiagnosticInbox.refuse("https://nodered.example/evsuite"))
        assertNotNull(DiagnosticInbox.refuse("http://192.0.2.10:1880/evsuite"))
        assertNotNull(DiagnosticInbox.refuse("https://user:pw@host/evsuite"))
        assertNotNull(DiagnosticInbox.refuse("https://host/evsuite?token=1"))
        assertNotNull(DiagnosticInbox.refuse("evsuite"))
    }

    @Test fun `the body carries the artifact byte for byte`() {
        var sent: String? = null
        val outcome = DiagnosticInbox.send("https://host/evsuite", artifact) { _, body -> sent = body; 200 }

        assertEquals(DiagnosticInbox.Outcome.Sent(200), outcome)
        val json = JsonParser.parseString(sent).asJsonObject
        assertEquals(1, json["evsuite"].asInt)
        assertEquals("EVTasker", json["app"].asString)
        assertEquals("report ✓", String(Base64.getDecoder().decode(json["contentBase64"].asString)))
    }

    @Test fun `a refused address never reaches the network, a server error is a failure`() {
        var called = false
        val refused = DiagnosticInbox.send("http://host/evsuite", artifact) { _, _ -> called = true; 200 }
        assertTrue(refused is DiagnosticInbox.Outcome.Failed && !called)
        assertEquals(
            DiagnosticInbox.Outcome.Failed("HTTP 500"),
            DiagnosticInbox.send("https://host/evsuite", artifact) { _, _ -> 500 },
        )
    }
}
