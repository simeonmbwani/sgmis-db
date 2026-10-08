from django.contrib import admin
from django.contrib.admin.helpers import ActionForm
from django.contrib import messages
from django import forms
from django.core.exceptions import PermissionDenied, ValidationError
from django.db import transaction
from apps.accounts.models import UserRole
from .services import reopen_duty_roster_as_draft
from .models import (
    DutyRosterCycle,
    DutyRoster,
    Shift,
    Attendance,
    ShiftHandover,
    PublicHoliday,
    PublicHolidayDutyRecord,
)


class DutyRosterActionForm(ActionForm):
    reason = forms.CharField(
        label="Reason (required for roster reopen)",
        required=False,
        max_length=2000,
        widget=forms.TextInput(attrs={"size": 56, "placeholder": "Explain the correction required"}),
    )


@admin.register(DutyRosterCycle)
class DutyRosterCycleAdmin(admin.ModelAdmin):
    list_display = ("station", "cycle_start_date", "cycle_length_days", "rotation_order", "is_active")
    list_filter = ("station", "is_active")
    search_fields = ("station__name", "station__code")

@admin.register(DutyRoster)
class DutyRosterAdmin(admin.ModelAdmin):
    action_form = DutyRosterActionForm
    actions = ["reopen_selected_rosters_to_draft"]
    list_display = ("station", "start_date", "end_date", "status", "approved_by", "approved_at", "created_at")
    list_filter = ("status", "station")
    search_fields = ("station__name", "station__code")
    date_hierarchy = "start_date"

    @staticmethod
    def _is_authorized_reopener(user):
        return bool(
            getattr(user, "is_superuser", False)
            or getattr(user, "role", None) == UserRole.ADMINISTRATOR
        )

    def get_actions(self, request):
        actions = super().get_actions(request)
        if not self._is_authorized_reopener(request.user):
            actions.pop("reopen_selected_rosters_to_draft", None)
        return actions

    @admin.action(
        description=(
            "Reopen selected roster(s) to DRAFT for correction "
            "(generated shifts will be removed)"
        )
    )
    def reopen_selected_rosters_to_draft(self, request, queryset):
        if not self._is_authorized_reopener(request.user):
            self.message_user(request, "Only administrators can reopen rosters.", level=messages.ERROR)
            return None

        reason = request.POST.get("reason", "").strip()
        if not reason:
            self.message_user(
                request,
                "No rosters were reopened. Enter a correction reason in the action form.",
                level=messages.ERROR,
            )
            return None

        reopened_count = 0
        deleted_shift_count = 0
        try:
            # Make a multi-selection all-or-nothing as well: if any roster fails
            # a safety check, none of the selected rosters are changed.
            with transaction.atomic():
                for roster_id in queryset.values_list("pk", flat=True):
                    deleted_shift_count += reopen_duty_roster_as_draft(
                        roster_id,
                        admin_user=request.user,
                        reason=reason,
                    )
                    reopened_count += 1
        except (PermissionDenied, ValidationError) as exc:
            detail = "; ".join(exc.messages) if isinstance(exc, ValidationError) else str(exc)
            self.message_user(
                request,
                f"No rosters were reopened. {detail}",
                level=messages.ERROR,
            )
            return None

        self.message_user(
            request,
            f"Reopened {reopened_count} roster(s) to DRAFT; removed {deleted_shift_count} generated shift(s).",
            level=messages.SUCCESS,
        )
        return None

@admin.register(Shift)
class ShiftAdmin(admin.ModelAdmin):
    list_display = ("date", "shift_type", "station", "guard", "pair", "roster", "start_time", "end_time", "is_override")
    list_filter = ("date", "shift_type", "station", "is_override", "roster")
    search_fields = ("guard__username", "guard__employee_number", "station__name")
    date_hierarchy = "date"


@admin.register(Attendance)
class AttendanceAdmin(admin.ModelAdmin):
    list_display = ("shift", "guard", "clock_in", "clock_out", "is_late", "is_serious_late", "escalation_notified")
    list_filter = ("is_late", "is_serious_late", "escalation_notified", "shift__shift_type", "shift__station")
    search_fields = ("guard__username", "guard__employee_number")

@admin.register(ShiftHandover)
class ShiftHandoverAdmin(admin.ModelAdmin):
    list_display = ("created_at", "station", "outgoing_guard", "incoming_guard", "incoming_accepted")
    list_filter = ("incoming_accepted", "station")
    search_fields = ("outgoing_guard__username", "incoming_guard__username", "station__name")

@admin.register(PublicHoliday)
class PublicHolidayAdmin(admin.ModelAdmin):
    list_display = ("name", "date", "country_code", "is_active", "created_at")
    list_filter = ("country_code", "is_active")
    search_fields = ("name",)
    date_hierarchy = "date"

@admin.register(PublicHolidayDutyRecord)
class PublicHolidayDutyRecordAdmin(admin.ModelAdmin):
    list_display = ("guard", "public_holiday", "shift", "status", "compensated_days", "approved_by", "approved_at")
    list_filter = ("status", "public_holiday")
    search_fields = ("guard__username", "guard__employee_number", "public_holiday__name")
    date_hierarchy = "created_at"
