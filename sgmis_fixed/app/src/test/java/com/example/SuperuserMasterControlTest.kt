package com.example

import com.example.data.model.*
import org.junit.Assert.*
import org.junit.Test

class SuperuserMasterControlTest {

    @Test
    fun testCreateRecordAdjustmentRequest_withDirectApprovalStatus() {
        val req = CreateRecordAdjustmentRequest(
            guard = "guard-uuid-1",
            fieldName = "employee_number",
            requestedValue = "GRD-9000",
            effectiveDate = "2026-10-06",
            reason = "National HR reconciliation",
            status = "APPROVED"
        )

        assertEquals("guard-uuid-1", req.guard)
        assertEquals("employee_number", req.fieldName)
        assertEquals("GRD-9000", req.requestedValue)
        assertEquals("2026-10-06", req.effectiveDate)
        assertEquals("National HR reconciliation", req.reason)
        assertEquals("APPROVED", req.status)
    }

    @Test
    fun testReassignDutyRequest_supportsRosterPositionAndAssignmentType() {
        val req = ReassignDutyRequest(
            guardId = "guard-uuid-1",
            effectiveDate = "2026-10-07",
            reason = "Operational shift rebalancing",
            shiftType = "NIGHT",
            stationId = "station-uuid-b",
            pairGuardId = "guard-uuid-2",
            rosterPosition = 2,
            assignmentType = "NORMAL"
        )

        assertEquals("guard-uuid-1", req.guardId)
        assertEquals("2026-10-07", req.effectiveDate)
        assertEquals("NIGHT", req.shiftType)
        assertEquals("station-uuid-b", req.stationId)
        assertEquals("guard-uuid-2", req.pairGuardId)
        assertEquals(2, req.rosterPosition)
        assertEquals("NORMAL", req.assignmentType)
    }

    @Test
    fun testSetOpeningBalanceRequest_supportsCompensationBalance() {
        val req = SetOpeningBalanceRequest(
            guardId = "guard-uuid-1",
            effectiveDate = "2026-10-06",
            reason = "Ledger opening balance establishment",
            source = "Muster Roll 2026",
            vacationBalance = 15.5,
            casualBalance = 4.0,
            compensationBalance = 6.0
        )

        assertEquals("guard-uuid-1", req.guardId)
        assertEquals("2026-10-06", req.effectiveDate)
        assertEquals("Muster Roll 2026", req.source)
        assertEquals(15.5, req.vacationBalance ?: 0.0, 0.001)
        assertEquals(4.0, req.casualBalance ?: 0.0, 0.001)
        assertEquals(6.0, req.compensationBalance ?: 0.0, 0.001)
    }

    @Test
    fun testAdministrativeHistoryEntry_structureAndFields() {
        val details = mapOf(
            "employee" to "Officer George (GRD001)",
            "employee_number" to "GRD001",
            "station" to "Main Station",
            "admin_employee_number" to "ADM001"
        )
        val entry = AdministrativeHistoryEntry(
            id = "audit-uuid-1",
            kind = "RECORD_ADJUSTMENT",
            timestamp = "2026-10-06T10:00:00Z",
            actor = "Alice Admin (ADM001)",
            targetModel = "User",
            targetId = "guard-uuid-1",
            action = "APPROVED (employee_number)",
            reason = "Verified against national payroll",
            oldValue = "GRD000",
            newValue = "GRD001",
            details = details
        )

        assertEquals("audit-uuid-1", entry.id)
        assertEquals("RECORD_ADJUSTMENT", entry.kind)
        assertEquals("Alice Admin (ADM001)", entry.actor)
        assertEquals("APPROVED (employee_number)", entry.action)
        assertEquals("GRD000", entry.oldValue)
        assertEquals("GRD001", entry.newValue)
        assertEquals("Officer George (GRD001)", entry.details?.get("employee"))
        assertEquals("Main Station", entry.details?.get("station"))
        assertEquals("ADM001", entry.details?.get("admin_employee_number"))
    }

    @Test
    fun testLeaveBalance_authoritativeStateIntegrity() {
        val balance = LeaveBalance(
            id = "bal-1",
            guard = "guard-1",
            guardName = "George Guard",
            year = 2026,
            vacationDays = 25.0,
            usedVacation = 5.0,
            remainingVacation = 20.0,
            casualDays = 8.0,
            usedCasual = 2.0,
            remainingCasual = 6.0,
            compensationEarned = 4.0,
            compensationUsed = 2.0,
            remainingCompensation = 2.0
        )

        assertEquals(25.0, balance.vacationDays, 0.001)
        assertEquals(20.0, balance.remainingVacation, 0.001)
        assertEquals(8.0, balance.casualDays, 0.001)
        assertEquals(6.0, balance.remainingCasual, 0.001)
        assertEquals(2.0, balance.remainingCompensation, 0.001)
    }
}
