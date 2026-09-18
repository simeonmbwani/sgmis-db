package com.example

import com.example.data.model.ClockOutRequest
import com.squareup.moshi.Moshi
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

class ClockOutRequestTest {

    private lateinit var moshi: Moshi

    @Before
    fun setup() {
        moshi = Moshi.Builder()
            .add(KotlinJsonAdapterFactory())
            .build()
    }

    @Test
    fun testNormalClockOutSerialization_hasNoSupervisorCredentials() {
        val request = ClockOutRequest(
            shiftId = "f47ac10b-58cc-4372-a567-0e02b2c3d479",
            latitude = -1.2921,
            longitude = 36.8219
        )
        val adapter = moshi.adapter(ClockOutRequest::class.java)
        val json = adapter.toJson(request)

        assertTrue(json.contains("\"shift_id\":\"f47ac10b-58cc-4372-a567-0e02b2c3d479\""))
        assertTrue(json.contains("\"latitude\":-1.2921"))
        assertTrue(json.contains("\"longitude\":36.8219"))
        assertNull(request.supervisorUsername)
        assertNull(request.supervisorPassword)
        assertNull(request.overrideReason)
    }

    @Test
    fun testEarlyClockOutSerialization_containsSupervisorCredentialsAndReason() {
        val request = ClockOutRequest(
            shiftId = "f47ac10b-58cc-4372-a567-0e02b2c3d479",
            latitude = -1.2921,
            longitude = 36.8219,
            supervisorUsername = "supervisor1",
            supervisorPassword = "SuperSecretPassword123!",
            overrideReason = "Medical emergency on duty post"
        )
        val adapter = moshi.adapter(ClockOutRequest::class.java)
        val json = adapter.toJson(request)

        assertTrue(json.contains("\"supervisor_username\":\"supervisor1\""))
        assertTrue(json.contains("\"supervisor_password\":\"SuperSecretPassword123!\""))
        assertTrue(json.contains("\"override_reason\":\"Medical emergency on duty post\""))
        assertEquals("supervisor1", request.supervisorUsername)
        assertEquals("Medical emergency on duty post", request.overrideReason)
    }

    @Test
    fun testClockOutRequestDeserialization() {
        val json = """
            {
                "shift_id": "12345678-1234-1234-1234-123456789abc",
                "latitude": 1.25,
                "longitude": 36.85,
                "supervisor_username": "sup_lead",
                "supervisor_password": "pass",
                "override_reason": "Severe storm evacuations"
            }
        """.trimIndent()

        val adapter = moshi.adapter(ClockOutRequest::class.java)
        val parsed = adapter.fromJson(json)

        assertNotNull(parsed)
        assertEquals("12345678-1234-1234-1234-123456789abc", parsed!!.shiftId)
        assertEquals(1.25, parsed.latitude!!, 0.001)
        assertEquals(36.85, parsed.longitude!!, 0.001)
        assertEquals("sup_lead", parsed.supervisorUsername)
        assertEquals("pass", parsed.supervisorPassword)
        assertEquals("Severe storm evacuations", parsed.overrideReason)
    }
}
