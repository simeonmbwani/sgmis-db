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

    @Test
    fun testLeaveBalanceDeserializationWithBackendStringDecimals() {
        val json = """
            {
              "id": "ee2cfb86-bfbc-4aa2-9882-56f70106dcf5",
              "guard": "4baf98b4-c5ac-490c-b844-25538f058c15",
              "guard_name": "",
              "guard_employee_number": null,
              "year": 2026,
              "annual_days": 21,
              "sick_days": 14,
              "used_annual": 0,
              "used_sick": 0,
              "remaining_annual": 21,
              "remaining_sick": 14,
              "casual_days": "0.0",
              "vacation_days": "0.0",
              "used_casual": "0.0",
              "used_vacation": "0.0",
              "casual_accrual_rate": "1.0",
              "vacation_accrual_rate": "2.5",
              "vacation_cap": "90.0",
              "remaining_casual": 0.0,
              "remaining_vacation": 0.0,
              "compensation_earned": 0.0,
              "compensation_used": 0.0,
              "remaining_compensation": 0.0,
              "opening_vacation_balance": "0.0",
              "opening_casual_balance": "0.0",
              "opening_balance_date": null,
              "opening_balance_source": "",
              "opening_balance_verified_by": null,
              "last_accrual_date": "2026-01-01",
              "casual_cycle_start": "2026-01-01"
            }
        """.trimIndent()

        val adapter = moshi.adapter(com.example.data.model.LeaveBalance::class.java)
        val lb = adapter.fromJson(json)
        assertNotNull(lb)
    }

    @Test
    fun testPatrolLogDeserialization() {
        val json = """
            {
              "id": "bff556fd-6670-40a2-8705-91530814acbf",
              "name": "Routine Station Patrol",
              "guard": "44c9dbdc-4921-4fdd-8d98-23b3996d25e9",
              "guard_name": "test_guard",
              "station": "5103afda-be50-4be8-8cee-de12217f2a57",
              "station_name": "Test Station",
              "assigned_by": "a35df763-07aa-4f12-a460-e02afc4665b3",
              "assigned_by_name": "test_sup",
              "start_window": "2026-10-06T02:00:00+02:00",
              "deadline": "2026-10-06T04:00:00+02:00",
              "start_time": "2026-10-06T01:19:25.485604+02:00",
              "end_time": null,
              "status": "ASSIGNED",
              "is_approved": false,
              "approved_by": null,
              "approved_by_name": null,
              "approved_at": null,
              "anomalies": [],
              "anomalies_count": 0,
              "notes": "",
              "scans_count": 0,
              "scans": []
            }
        """.trimIndent()

        val adapter = moshi.adapter(com.example.data.model.PatrolLog::class.java)
        val pl = adapter.fromJson(json)
        assertNotNull(pl)
    }

    @Test
    fun testAdministrativeHistoryEntryDeserialization() {
        val json = """
            {
              "id": "634e53ba-7fa7-443d-b4b0-9a8546b6b727",
              "kind": "RECORD_ADJUSTMENT",
              "timestamp": "2026-10-06T03:58:24.091015+00:00",
              "actor": "admin_test",
              "target_model": "User",
              "target_id": "4baf98b4-c5ac-490c-b844-25538f058c15",
              "action": "PENDING (employee_number)",
              "reason": "Test",
              "old_value": "",
              "new_value": "G-100",
              "details": {
                "field": "employee_number",
                "employee": "guard2",
                "employee_number": "",
                "station": "Test Station",
                "requested_by": "",
                "status": "PENDING",
                "request_id": "634e53ba-7fa7-443d-b4b0-9a8546b6b727",
                "admin_employee_number": ""
              }
            }
        """.trimIndent()

        val adapter = moshi.adapter(com.example.data.model.AdministrativeHistoryEntry::class.java)
        val entry = adapter.fromJson(json)
        assertNotNull(entry)
    }

    @Test
    fun testPaginatedPatrolLogListDeserialization() {
        val json = """
            {
              "count": 1,
              "next": null,
              "previous": null,
              "results": [
                {
                  "id": "bff556fd-6670-40a2-8705-91530814acbf",
                  "name": "Routine Station Patrol",
                  "guard": "44c9dbdc-4921-4fdd-8d98-23b3996d25e9",
                  "guard_name": "test_guard",
                  "station": "5103afda-be50-4be8-8cee-de12217f2a57",
                  "station_name": "Test Station",
                  "assigned_by": "a35df763-07aa-4f12-a460-e02afc4665b3",
                  "assigned_by_name": "test_sup",
                  "start_window": "2026-10-06T02:00:00+02:00",
                  "deadline": "2026-10-06T04:00:00+02:00",
                  "start_time": "2026-10-06T01:19:25.485604+02:00",
                  "end_time": null,
                  "status": "ASSIGNED",
                  "is_approved": false,
                  "approved_by": null,
                  "approved_by_name": null,
                  "approved_at": null,
                  "anomalies": [],
                  "anomalies_count": 0,
                  "notes": "",
                  "scans_count": 0,
                  "scans": []
                }
              ]
            }
        """.trimIndent()

        val type = Types.newParameterizedType(List::class.java, com.example.data.model.PatrolLog::class.java)
        val adapter = moshi.adapter<List<com.example.data.model.PatrolLog>>(type)
        val list = adapter.fromJson(json)
        assertNotNull(list)
        assertEquals(1, list!!.size)
        assertEquals("bff556fd-6670-40a2-8705-91530814acbf", list[0].id)
    }

    @Test
    fun testPaginatedCheckpointListDeserialization() {
        val json = """
            {
              "count": 1,
              "next": null,
              "previous": null,
              "results": [
                {
                  "id": "b2eceb49-728a-4e17-b157-4c2b488f7e67",
                  "station": "5103afda-be50-4be8-8cee-de12217f2a57",
                  "station_name": "Test Station",
                  "name": "Main Gate",
                  "code": "CP-01",
                  "qr_code": "",
                  "nfc_uid": "",
                  "latitude": -1.28,
                  "longitude": 36.82,
                  "order": 1,
                  "min_interval_seconds": 0,
                  "is_active": true
                }
              ]
            }
        """.trimIndent()

        val type = Types.newParameterizedType(List::class.java, com.example.data.model.Checkpoint::class.java)
        val adapter = moshi.adapter<List<com.example.data.model.Checkpoint>>(type)
        val list = adapter.fromJson(json)
        assertNotNull(list)
        assertEquals(1, list!!.size)
        assertEquals("Main Gate", list[0].name)
    }

    @Test
    fun testAdministrativeHistoryListDeserialization() {
        val json = """
            [
              {
                "id": "634e53ba-7fa7-443d-b4b0-9a8546b6b727",
                "kind": "RECORD_ADJUSTMENT",
                "timestamp": "2026-10-06T03:58:24.091015+00:00",
                "actor": "admin_test",
                "target_model": "User",
                "target_id": "4baf98b4-c5ac-490c-b844-25538f058c15",
                "action": "PENDING (employee_number)",
                "reason": "Test",
                "old_value": "",
                "new_value": "G-100",
                "details": {
                  "field": "employee_number"
                }
              }
            ]
        """.trimIndent()

        val type = Types.newParameterizedType(List::class.java, com.example.data.model.AdministrativeHistoryEntry::class.java)
        val adapter = moshi.adapter<List<com.example.data.model.AdministrativeHistoryEntry>>(type)
        val list = adapter.fromJson(json)
        assertNotNull(list)
        assertEquals(1, list!!.size)
        assertEquals("RECORD_ADJUSTMENT", list[0].kind)
    }

    @Test
    fun testPaginatedRecordAdjustmentRequestListDeserialization() {
        val json = """
            {
              "count": 1,
              "next": null,
              "previous": null,
              "results": [
                {
                  "id": "634e53ba-7fa7-443d-b4b0-9a8546b6b727",
                  "guard": "4baf98b4-c5ac-490c-b844-25538f058c15",
                  "guard_name": "",
                  "field_name": "employee_number",
                  "old_value": "",
                  "requested_value": "G-100",
                  "approved_value": "",
                  "effective_date": "2026-10-06",
                  "reason": "Test",
                  "notes": "",
                  "status": "PENDING",
                  "status_display": "Pending Review",
                  "requested_by": "82a4e36c-6b41-4ee4-96ee-eb1b1ec3eafb",
                  "requested_by_name": "",
                  "reviewed_by": null,
                  "reviewed_at": null,
                  "rejection_reason": "",
                  "created_at": "2026-10-06T05:58:24.091015+02:00"
                }
              ]
            }
        """.trimIndent()

        val type = Types.newParameterizedType(List::class.java, com.example.data.model.RecordAdjustmentRequest::class.java)
        val adapter = moshi.adapter<List<com.example.data.model.RecordAdjustmentRequest>>(type)
        val list = adapter.fromJson(json)
        assertNotNull(list)
        assertEquals(1, list!!.size)
        assertEquals("employee_number", list[0].fieldName)
    }
}
