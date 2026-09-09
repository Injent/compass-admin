package ru.injent.service.google

import com.google.api.client.googleapis.json.GoogleJsonResponseException
import com.google.api.client.http.HttpHeaders
import com.google.api.client.http.HttpResponseException
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.assertNull

class SheetsRequestGateTest {
    @Test
    fun `requests are paced and quota failures retry without losing the result`() = runBlocking {
        var time = 0L
        val waits = mutableListOf<Long>()
        val gate = SheetsRequestGate({ time }, { waits += it; time += it })
        repeat(61) { gate.execute { Unit } }
        assertEquals(72_000L, time)
        var calls = 0
        val result = gate.execute {
            calls++
            if (calls == 1) throw GoogleJsonResponseException(
                HttpResponseException.Builder(429, "Too Many Requests", HttpHeaders().setRetryAfter("60")), null,
            )
            "done"
        }
        assertEquals("done", result)
        assertEquals(2, calls)
        assertTrue(waits.contains(60_000L))
        assertNull(gate.message.value)
        var forbiddenCalls = 0
        try {
            gate.execute {
                forbiddenCalls++
                throw GoogleJsonResponseException(HttpResponseException.Builder(403, "Forbidden", HttpHeaders()), null)
            }
        } catch (_: GoogleJsonResponseException) { }
        assertEquals(1, forbiddenCalls)
        assertNull(gate.message.value)
    }
}
