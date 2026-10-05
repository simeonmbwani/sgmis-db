package com.example

import com.example.data.model.*
import org.junit.Assert.*
import org.junit.Test

class PatrolSystemStage2Test {

    @Test
    fun testPatrolLog_defaultStatusIsAssigned() {
        val patrol = PatrolLog(
            id = "patrol-1",
            guard = "guard-1",
            guardName = "Officer Test",
            station = "station-1",
            stationName = "Harare Main Station"
        )
        assertEquals("ASSIGNED", patrol.status)
        assertTrue(patrol.isAssigned)
        assertFalse(patrol.isInProgress)
        assertFalse(patrol.isCompleted)
        assertFalse(patrol.isApprovedStatus)
        assertFalse(patrol.isFailed)
        assertFalse(patrol.isCancelled)
        assertFalse(patrol.isExpired)
    }

    @Test
    fun testPatrolLog_statusStateProperties() {
        val active = PatrolLog(
            id = "p-active",
            guard = "g1",
            guardName = "Guard",
            station = "s1",
            stationName = "Station",
            status = "IN_PROGRESS"
        )
        assertTrue(active.isInProgress)
        assertFalse(active.isAssigned)

        val completed = PatrolLog(
            id = "p-completed",
            guard = "g1",
            guardName = "Guard",
            station = "s1",
            stationName = "Station",
            status = "COMPLETED"
        )
        assertTrue(completed.isCompleted)
        assertFalse(completed.isApprovedStatus)

        val approved = PatrolLog(
            id = "p-approved",
            guard = "g1",
            guardName = "Guard",
            station = "s1",
            stationName = "Station",
            status = "APPROVED",
            isApproved = true
        )
        assertTrue(approved.isApprovedStatus)

        val failed = PatrolLog(
            id = "p-failed",
            guard = "g1",
            guardName = "Guard",
            station = "s1",
            stationName = "Station",
            status = "FAILED"
        )
        assertTrue(failed.isFailed)

        val cancelled = PatrolLog(
            id = "p-cancelled",
            guard = "g1",
            guardName = "Guard",
            station = "s1",
            stationName = "Station",
            status = "CANCELLED"
        )
        assertTrue(cancelled.isCancelled)

        val expired = PatrolLog(
            id = "p-expired",
            guard = "g1",
            guardName = "Guard",
            station = "s1",
            stationName = "Station",
            status = "EXPIRED"
        )
        assertTrue(expired.isExpired)
    }

    @Test
    fun testCheckpoint_nfcAndIntervalDefaults() {
        val cp = Checkpoint(
            id = "cp-1",
            station = "s1",
            stationName = "Station A",
            name = "Main Gate Post",
            code = "CP-GATE",
            qrCode = "QR-CP-GATE",
            latitude = -17.8252,
            longitude = 31.0335,
            order = 1
        )
        assertEquals(60, cp.minIntervalSeconds)
        assertNull(cp.nfcUid)
        assertTrue(cp.isActive)

        val cpWithNfc = cp.copy(nfcUid = "04:A1:B2:C3:D4", minIntervalSeconds = 45)
        assertEquals("04:A1:B2:C3:D4", cpWithNfc.nfcUid)
        assertEquals(45, cpWithNfc.minIntervalSeconds)
    }

    @Test
    fun testCheckpointScan_attributes() {
        val scan = CheckpointScan(
            id = "scan-1",
            clientEventId = "evt-1234",
            patrolLog = "patrol-1",
            checkpoint = "cp-1",
            checkpointName = "Main Gate",
            checkpointCode = "CP-GATE",
            scannedAt = "2026-10-05T20:00:00Z",
            clientTimestamp = "2026-10-05T20:00:00Z",
            gpsCoords = "-17.8252,31.0335",
            accuracy = 8.5,
            verificationMethod = "NFC",
            notes = "Physical NFC tag verified"
        )
        assertEquals("evt-1234", scan.clientEventId)
        assertEquals("NFC", scan.verificationMethod)
        assertEquals(8.5, scan.accuracy!!, 0.001)
    }

    @Test
    fun testAssignPatrolRequest_creation() {
        val req = AssignPatrolRequest(
            guard = "guard-uuid-1",
            station = "station-uuid-1",
            name = "Perimeter Security Sweep",
            startWindow = "2026-10-05T21:00:00Z",
            deadline = "2026-10-05T23:00:00Z",
            notes = "Pay special attention to rear gate"
        )
        assertEquals("guard-uuid-1", req.guard)
        assertEquals("station-uuid-1", req.station)
        assertEquals("Perimeter Security Sweep", req.name)
        assertEquals("2026-10-05T23:00:00Z", req.deadline)
    }

    @Test
    fun testOfflinePatrolSyncDTOs() {
        val event1 = OfflinePatrolEvent(
            clientEventId = "evt-1",
            checkpoint = "cp-1",
            clientTimestamp = "2026-10-05T21:05:00Z",
            verificationMethod = "NFC",
            gpsCoords = "-17.8252,31.0335",
            accuracy = 5.0,
            notes = "Offline scan 1"
        )
        val event2 = OfflinePatrolEvent(
            clientEventId = "evt-2",
            checkpoint = "cp-2",
            clientTimestamp = "2026-10-05T21:10:00Z",
            verificationMethod = "QR",
            gpsCoords = "-17.8255,31.0338",
            accuracy = 6.0,
            notes = "Offline scan 2 (QR fallback)"
        )

        val syncReq = SyncPatrolEventsRequest(events = listOf(event1, event2))
        assertEquals(2, syncReq.events.size)

        val syncResp = SyncPatrolEventsResponse(
            syncedCount = 2,
            anomaliesDetected = 0,
            patrolStatus = "IN_PROGRESS"
        )
        assertEquals(2, syncResp.syncedCount)
        assertEquals(0, syncResp.anomaliesDetected)
        assertEquals("IN_PROGRESS", syncResp.patrolStatus)
    }

    @Test
    fun testRouteProgressionSequenceEnforcement() {
        val checkpoints = listOf(
            Checkpoint(id = "cp-3", station = "s1", stationName = "Station", name = "Perimeter West", code = "CP-03", qrCode = "QR-03", latitude = -17.82, longitude = 31.03, order = 3),
            Checkpoint(id = "cp-1", station = "s1", stationName = "Station", name = "Main Gate", code = "CP-01", qrCode = "QR-01", latitude = -17.82, longitude = 31.03, order = 1),
            Checkpoint(id = "cp-2", station = "s1", stationName = "Station", name = "Admin Block", code = "CP-02", qrCode = "QR-02", latitude = -17.82, longitude = 31.03, order = 2)
        )

        val ordered = checkpoints.sortedBy { it.order }
        assertEquals(1, ordered[0].order)
        assertEquals(2, ordered[1].order)
        assertEquals(3, ordered[2].order)

        // Given no checkpoints scanned: next must be CP-1
        var scanned = setOf<String>()
        val next1 = ordered.firstOrNull { !scanned.contains(it.id) }
        assertEquals("cp-1", next1?.id)

        // After scanning CP-1: next must be CP-2
        scanned = scanned + "cp-1"
        val next2 = ordered.firstOrNull { !scanned.contains(it.id) }
        assertEquals("cp-2", next2?.id)

        // After scanning CP-2: next must be CP-3
        scanned = scanned + "cp-2"
        val next3 = ordered.firstOrNull { !scanned.contains(it.id) }
        assertEquals("cp-3", next3?.id)

        // All scanned: next is null
        scanned = scanned + "cp-3"
        val nextAll = ordered.firstOrNull { !scanned.contains(it.id) }
        assertNull(nextAll)
    }
}
