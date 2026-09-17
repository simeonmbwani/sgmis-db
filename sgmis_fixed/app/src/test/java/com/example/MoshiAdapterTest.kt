package com.example

import com.example.data.api.PaginatedListJsonAdapterFactory
import com.example.data.api.ShiftJsonAdapterFactory
import com.example.data.model.Shift
import com.example.data.model.Station
import com.squareup.moshi.Moshi
import com.squareup.moshi.Types
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

class MoshiAdapterTest {

    private lateinit var moshi: Moshi

    @Before
    fun setup() {
        moshi = Moshi.Builder()
            .add(ShiftJsonAdapterFactory())
            .add(PaginatedListJsonAdapterFactory())
            .add(KotlinJsonAdapterFactory())
            .build()
    }

    @Test
    fun testPaginatedStationListDeserialization() {
        val json = """
            {
                "count": 1,
                "next": null,
                "previous": null,
                "results": [
                    {
                        "id": "3ca4312f-8343-450a-9ad4-37ff8afa4755",
                        "name": "New Post",
                        "code": "NEW01",
                        "address": "Gate 1",
                        "latitude": -1.28,
                        "longitude": 36.82,
                        "geofence_radius_meters": 200.0,
                        "is_active": true
                    }
                ]
            }
        """.trimIndent()

        val type = Types.newParameterizedType(List::class.java, Station::class.java)
        val adapter = moshi.adapter<List<Station>>(type)
        val stations = adapter.fromJson(json)

        assertNotNull(stations)
        assertEquals(1, stations!!.size)
        assertEquals("New Post", stations[0].name)
        assertEquals("NEW01", stations[0].code)
    }

    @Test
    fun testRawArrayStationListDeserialization() {
        val json = """
            [
                {
                    "id": "3ca4312f-8343-450a-9ad4-37ff8afa4755",
                    "name": "New Post",
                    "code": "NEW01",
                    "address": "Gate 1",
                    "latitude": -1.28,
                    "longitude": 36.82,
                    "geofence_radius_meters": 200.0,
                    "is_active": true
                }
            ]
        """.trimIndent()

        val type = Types.newParameterizedType(List::class.java, Station::class.java)
        val adapter = moshi.adapter<List<Station>>(type)
        val stations = adapter.fromJson(json)

        assertNotNull(stations)
        assertEquals(1, stations!!.size)
        assertEquals("New Post", stations[0].name)
    }

    @Test
    fun testEmptyPaginatedListDeserialization() {
        val json = """
            {
                "count": 0,
                "next": null,
                "previous": null,
                "results": []
            }
        """.trimIndent()

        val type = Types.newParameterizedType(List::class.java, Station::class.java)
        val adapter = moshi.adapter<List<Station>>(type)
        val stations = adapter.fromJson(json)

        assertNotNull(stations)
        assertTrue(stations!!.isEmpty())
    }

    @Test
    fun testNoShiftScheduledDeserializationReturnsNullWithoutCrashing() {
        val json = """
            {
                "detail": "No shift scheduled for today.",
                "shift": null
            }
        """.trimIndent()

        val adapter = moshi.adapter(Shift::class.java)
        val shift = adapter.fromJson(json)

        assertNull("Empty shift response must parse as null without exception", shift)
    }

    @Test
    fun testActiveShiftScheduledDeserializationReturnsShift() {
        val json = """
            {
                "id": "shift-12345",
                "station": "station-999",
                "station_name": "Alpha Command Post",
                "guard": "guard-555",
                "guard_name": "John Doe",
                "employee_number": "SEC-001",
                "date": "2026-09-16",
                "start_time": "07:00:00",
                "end_time": "18:00:00",
                "shift_type": "DAY",
                "attendance_status": "NOT_CLOCKED_IN"
            }
        """.trimIndent()

        val adapter = moshi.adapter(Shift::class.java)
        val shift = adapter.fromJson(json)

        assertNotNull(shift)
        assertEquals("shift-12345", shift!!.id)
        assertEquals("Alpha Command Post", shift.stationName)
        assertEquals("DAY", shift.shiftType)
    }
}
