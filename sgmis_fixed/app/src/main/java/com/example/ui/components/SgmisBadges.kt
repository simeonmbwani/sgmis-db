package com.example.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Circle
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.data.model.GuardDutyState
import com.example.ui.theme.*

/**
 * Standardized status pill badge for operational records, rosters, and tasks.
 */
@Composable
fun SgmisStatusBadge(
    status: String,
    modifier: Modifier = Modifier,
    showDot: Boolean = true
) {
    val clean = status.trim().uppercase()
    val (bgColor, textColor) = when (clean) {
        "ACTIVE", "APPROVED", "VERIFIED", "COMPLETED", "RESOLVED", "NORMAL" ->
            Pair(StatusSuccess.copy(alpha = 0.14f), StatusSuccess)

        "PENDING", "IN_PROGRESS", "SCHEDULED", "ASSIGNED", "DRAFT" ->
            Pair(StatusPending.copy(alpha = 0.14f), StatusPending)

        "UNCOVERED", "CRITICAL", "FAILED", "REJECTED", "EXPIRED", "ATTENTION_REQUIRED" ->
            Pair(StatusError.copy(alpha = 0.14f), StatusError)

        "RELIEF", "COVERED" ->
            Pair(StatusRelief.copy(alpha = 0.14f), StatusRelief)

        "CANCELLED", "ARCHIVED", "OFF_DUTY" ->
            Pair(StatusNeutral.copy(alpha = 0.14f), StatusNeutral)

        else ->
            Pair(NavyContainer.copy(alpha = 0.14f), NavyDark)
    }

    Box(
        modifier = modifier
            .clip(RoundedCornerShape(6.dp))
            .background(bgColor)
            .padding(horizontal = 8.dp, vertical = 3.dp),
        contentAlignment = Alignment.Center
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (showDot) {
                Box(
                    modifier = Modifier
                        .size(6.dp)
                        .clip(CircleShape)
                        .background(textColor)
                )
                Spacer(modifier = Modifier.width(5.dp))
            }
            Text(
                text = clean.replace("_", " "),
                color = textColor,
                fontSize = 11.sp,
                fontWeight = FontWeight.Bold,
                letterSpacing = 0.3.sp
            )
        }
    }
}

/**
 * High-visibility badge indicating a guard's authoritative duty state.
 */
@Composable
fun SgmisDutyBadge(
    dutyState: GuardDutyState,
    modifier: Modifier = Modifier
) {
    val (label, bg, fg) = when (dutyState) {
        GuardDutyState.ON_DUTY -> Triple("ON DUTY", StatusSuccess.copy(alpha = 0.14f), StatusSuccess)
        GuardDutyState.ELIGIBLE_FOR_DUTY -> Triple("READY FOR DUTY", StatusInfo.copy(alpha = 0.14f), StatusInfo)
        GuardDutyState.REASSIGNED -> Triple("REASSIGNED RELIEF", StatusRelief.copy(alpha = 0.14f), StatusRelief)
        GuardDutyState.OFF_DUTY -> Triple("OFF DUTY", StatusNeutral.copy(alpha = 0.14f), StatusNeutral)
        GuardDutyState.ON_LEAVE -> Triple("ON LEAVE", ShiftLeaveColor.copy(alpha = 0.14f), ShiftLeaveColor)
        GuardDutyState.TIME_OFF -> Triple("TIME OFF", StatusNeutral.copy(alpha = 0.14f), StatusNeutral)
        GuardDutyState.EXAM -> Triple("EXAM MISSION", ShiftExamColor.copy(alpha = 0.14f), ShiftExamColor)
        GuardDutyState.ESCORT -> Triple("ESCORT MISSION", ShiftEscortColor.copy(alpha = 0.14f), ShiftEscortColor)
        GuardDutyState.EARLY_EXIT_PENDING -> Triple("EARLY EXIT PENDING", StatusWarning.copy(alpha = 0.14f), StatusWarning)
    }

    Box(
        modifier = modifier
            .clip(RoundedCornerShape(6.dp))
            .background(bg)
            .padding(horizontal = 9.dp, vertical = 4.dp),
        contentAlignment = Alignment.Center
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                modifier = Modifier
                    .size(6.dp)
                    .clip(CircleShape)
                    .background(fg)
            )
            Spacer(modifier = Modifier.width(6.dp))
            Text(
                text = label,
                color = fg,
                fontSize = 11.sp,
                fontWeight = FontWeight.Bold,
                letterSpacing = 0.4.sp
            )
        }
    }
}

/**
 * Shift type badge identifying Day, Night, Rest, or Special assignment.
 */
@Composable
fun SgmisShiftBadge(
    shiftType: String,
    assignmentType: String? = null,
    modifier: Modifier = Modifier
) {
    val cleanType = shiftType.trim().uppercase()
    val cleanAssign = assignmentType?.trim()?.uppercase()

    val (label, bg, fg) = when {
        cleanAssign == "RELIEF" -> Triple("RELIEF", StatusRelief.copy(alpha = 0.15f), StatusRelief)
        cleanAssign == "EXAM" -> Triple("EXAM", ShiftExamColor.copy(alpha = 0.15f), ShiftExamColor)
        cleanAssign == "ESCORT" -> Triple("ESCORT", ShiftEscortColor.copy(alpha = 0.15f), ShiftEscortColor)
        cleanAssign == "TIME_OFF" || cleanType == "OFF" -> Triple("OFF", ShiftOffColor.copy(alpha = 0.15f), ShiftOffColor)
        cleanType == "DAY" -> Triple("DAY SHIFT", ShiftDayColor.copy(alpha = 0.15f), ShiftDayColor)
        cleanType == "NIGHT" -> Triple("NIGHT SHIFT", ShiftNightColor.copy(alpha = 0.15f), ShiftNightColor)
        else -> Triple(cleanType, NavyContainer.copy(alpha = 0.15f), NavyDark)
    }

    Box(
        modifier = modifier
            .clip(RoundedCornerShape(6.dp))
            .background(bg)
            .padding(horizontal = 8.dp, vertical = 3.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = label,
            color = fg,
            fontSize = 11.sp,
            fontWeight = FontWeight.Bold,
            letterSpacing = 0.3.sp
        )
    }
}

/**
 * Backward compatibility adapters for Smart components.
 */
@Composable
fun SmartStatusBadge(
    status: String,
    modifier: Modifier = Modifier
) {
    SgmisStatusBadge(status = status, modifier = modifier)
}

@Composable
fun SmartDutyStateBadge(
    dutyState: GuardDutyState,
    modifier: Modifier = Modifier
) {
    SgmisDutyBadge(dutyState = dutyState, modifier = modifier)
}

enum class SgmisBadgeVariant {
    SUCCESS,
    WARNING,
    ERROR,
    DANGER,
    INFO,
    NEUTRAL,
    PRIMARY;

    companion object {
        val Success = SUCCESS
        val Warning = WARNING
        val Error = ERROR
        val Danger = DANGER
        val Info = INFO
        val Neutral = NEUTRAL
        val Primary = PRIMARY
    }
}

typealias BadgeVariant = SgmisBadgeVariant

@Composable
fun SgmisBadge(
    text: String,
    modifier: Modifier = Modifier,
    variant: SgmisBadgeVariant = SgmisBadgeVariant.PRIMARY,
    showDot: Boolean = false
) {
    val (bgColor, textColor) = when (variant) {
        SgmisBadgeVariant.SUCCESS -> Pair(StatusSuccess.copy(alpha = 0.14f), StatusSuccess)
        SgmisBadgeVariant.WARNING -> Pair(StatusWarning.copy(alpha = 0.14f), StatusWarning)
        SgmisBadgeVariant.ERROR, SgmisBadgeVariant.DANGER -> Pair(StatusError.copy(alpha = 0.14f), StatusError)
        SgmisBadgeVariant.INFO -> Pair(StatusInfo.copy(alpha = 0.14f), StatusInfo)
        SgmisBadgeVariant.NEUTRAL -> Pair(StatusNeutral.copy(alpha = 0.14f), StatusNeutral)
        SgmisBadgeVariant.PRIMARY -> Pair(NavyContainer.copy(alpha = 0.14f), NavyDark)
    }

    Box(
        modifier = modifier
            .clip(RoundedCornerShape(6.dp))
            .background(bgColor)
            .padding(horizontal = 8.dp, vertical = 3.dp),
        contentAlignment = Alignment.Center
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (showDot) {
                Box(
                    modifier = Modifier
                        .size(6.dp)
                        .clip(CircleShape)
                        .background(textColor)
                )
                Spacer(modifier = Modifier.width(5.dp))
            }
            Text(
                text = text,
                color = textColor,
                fontSize = 11.sp,
                fontWeight = FontWeight.Bold,
                letterSpacing = 0.3.sp
            )
        }
    }
}
