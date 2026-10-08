package com.example.util

/**
 * Structured user guidance error answering the 3 essential operational questions:
 * 1. What happened?
 * 2. Why did it happen?
 * 3. What can I do now?
 */
data class UserGuidanceError(
    val whatHappened: String,
    val whyItHappened: String,
    val whatCanIDoNow: String,
    val actionLabel: String = "Understood",
    val actionRoute: String? = null
)

/**
 * Centralized translator converting raw backend exception strings and HTTP errors
 * into actionable, respectful guidance for security officers and supervisors.
 */
object ApiErrorTranslator {

    fun translate(rawError: String?): UserGuidanceError {
        if (rawError.isNullOrBlank()) {
            return UserGuidanceError(
                whatHappened = "An unexpected issue occurred.",
                whyItHappened = "The operation could not be completed at this time.",
                whatCanIDoNow = "Please try again or contact your supervisor if the issue persists.",
                actionLabel = "Dismiss"
            )
        }

        val lower = rawError.lowercase()

        return when {
            // Roster generation overlaps / duplicate date ranges
            lower.contains("already covers the requested generation period") ||
            lower.contains("approved dutyroster") ||
            lower.contains("roster overlap") ||
            lower.contains("cycle overlap") -> {
                UserGuidanceError(
                    whatHappened = "Roster could not be generated.",
                    whyItHappened = "A duty roster is already generated or approved for these dates at this station.",
                    whatCanIDoNow = "Review existing rosters for this station or choose different coverage dates.",
                    actionLabel = "View Rosters",
                    actionRoute = "roster"
                )
            }

            // Validated roster edit protection
            lower.contains("validated roster cannot be edited") ||
            lower.contains("approved roster cannot be edited") -> {
                UserGuidanceError(
                    whatHappened = "Roster is locked against direct edits.",
                    whyItHappened = "This roster has already been validated and approved for official station duty.",
                    whatCanIDoNow = "Request an administrator to reopen this roster for revision if corrections are required.",
                    actionLabel = "Review Roster",
                    actionRoute = "roster"
                )
            }

            // Guard availability conflicts (leave, exam, escort)
            lower.contains("on approved leave") ||
            lower.contains("leave conflict") ||
            lower.contains("leaveapplication") -> {
                UserGuidanceError(
                    whatHappened = "Guard is unavailable for assignment.",
                    whyItHappened = "The selected guard has approved leave scheduled during this shift period.",
                    whatCanIDoNow = "Select an available off-duty guard to provide relief.",
                    actionLabel = "Choose Relief Guard"
                )
            }

            lower.contains("examduty") || lower.contains("exam duty") ||
            lower.contains("escortduty") || lower.contains("escort duty") -> {
                UserGuidanceError(
                    whatHappened = "Guard is assigned to a special mission.",
                    whyItHappened = "This guard is committed to an examination or armed escort detail today.",
                    whatCanIDoNow = "Assign an available alternative guard for station coverage.",
                    actionLabel = "Choose Alternative"
                )
            }

            // GPS Geofence violations
            lower.contains("geofence") || lower.contains("outside") ||
            lower.contains("perimeter") || lower.contains("gps") -> {
                UserGuidanceError(
                    whatHappened = "Duty clock-in blocked by post security.",
                    whyItHappened = "Your current GPS position is outside the authorized station boundary.",
                    whatCanIDoNow = "Please move within the post boundary and verify your device location.",
                    actionLabel = "Refresh GPS"
                )
            }

            // Duty window / early clock-in
            lower.contains("too early") || lower.contains("duty window") ||
            lower.contains("reporting window") -> {
                UserGuidanceError(
                    whatHappened = "Clock-in window is not yet active.",
                    whyItHappened = "Guard check-in opens 30 minutes before your scheduled shift start time.",
                    whatCanIDoNow = "Please report for duty closer to your shift start time.",
                    actionLabel = "View Shift Details",
                    actionRoute = "today_shift"
                )
            }

            // Early departure / OTP required
            lower.contains("early clock-out") || lower.contains("otp") ||
            lower.contains("authorization code") -> {
                UserGuidanceError(
                    whatHappened = "Early clock-out requires authorization.",
                    whyItHappened = "You are departing before your shift ends without an authorized supervisor code.",
                    whatCanIDoNow = "Request an Early Clock-Out verification code from your duty supervisor.",
                    actionLabel = "Request Code"
                )
            }

            // 403 Forbidden / Unauthorized role
            lower.contains("permission denied") || lower.contains("403") ||
            lower.contains("do not have permission") || lower.contains("forbidden") -> {
                UserGuidanceError(
                    whatHappened = "Access restricted to authorized personnel.",
                    whyItHappened = "Your current account role does not have clearance for this operational action.",
                    whatCanIDoNow = "Please consult your shift supervisor or system administrator for access.",
                    actionLabel = "Back to Console"
                )
            }

            // 401 Unauthorized / Token expired / Invalid Credentials
            lower.contains("invalid credentials") || lower.contains("no active account") ||
            lower.contains("authentication failed") || lower.contains("incorrect password") ||
            lower.contains("wrong password") || lower.contains("bad credentials") -> {
                UserGuidanceError(
                    whatHappened = "Authentication could not be completed.",
                    whyItHappened = "The username, employee number, or security password entered does not match system records.",
                    whatCanIDoNow = "Please re-check your credentials or tap 'Forgot Password?' to reset.",
                    actionLabel = "Try Again"
                )
            }

            lower.contains("required") && (lower.contains("username") || lower.contains("password") || lower.contains("employee")) -> {
                UserGuidanceError(
                    whatHappened = "Credentials required.",
                    whyItHappened = "Both your Username / Employee ID and security password must be entered to access the system.",
                    whatCanIDoNow = "Please fill in both fields before authenticating.",
                    actionLabel = "Understood"
                )
            }

            lower.contains("401") || lower.contains("unauthorized") ||
            lower.contains("token") || lower.contains("credentials") -> {
                UserGuidanceError(
                    whatHappened = "Security session expired.",
                    whyItHappened = "Your authentication credentials have expired for your security.",
                    whatCanIDoNow = "Please sign in again to continue your duty operations.",
                    actionLabel = "Log In Again",
                    actionRoute = "login"
                )
            }

            // Network / Connection
            lower.contains("network") || lower.contains("timeout") ||
            lower.contains("failed to connect") || lower.contains("unable to resolve host") -> {
                UserGuidanceError(
                    whatHappened = "Unable to reach security server.",
                    whyItHappened = "Your mobile data connection is weak or the station network is offline.",
                    whatCanIDoNow = "Check your data connection. Local duty records remain safe and will sync once reconnected.",
                    actionLabel = "Retry"
                )
            }

            // HTTP 500 Server error
            lower.contains("500") || lower.contains("internal server error") -> {
                UserGuidanceError(
                    whatHappened = "Security headquarters service error.",
                    whyItHappened = "The central server encountered a temporary processing condition.",
                    whatCanIDoNow = "Please try again in a few moments or notify system support if this persists.",
                    actionLabel = "Try Again"
                )
            }

            // Default fallback
            else -> {
                UserGuidanceError(
                    whatHappened = "Action could not be completed.",
                    whyItHappened = rawError,
                    whatCanIDoNow = "Please review your inputs or contact your supervisor if this continues.",
                    actionLabel = "Understood"
                )
            }
        }
    }
}
