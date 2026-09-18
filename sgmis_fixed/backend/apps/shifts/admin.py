from django.contrib import admin
from .models import (
    DutyRosterCycle,
    DutyRoster,
    Shift,
    Attendance,
    ShiftHandover,
    PublicHoliday,
    PublicHolidayDutyRecord,
)

@admin.register(DutyRosterCycle)
class DutyRosterCycleAdmin(admin.ModelAdmin):
    list_display = ("station", "cycle_start_date", "cycle_length_days", "rotation_order", "is_active")
    list_filter = ("station", "is_active")
    search_fields = ("station__name", "station__code")

@admin.register(DutyRoster)
class DutyRosterAdmin(admin.ModelAdmin):
    list_display = ("station", "start_date", "end_date", "status", "approved_by", "approved_at", "created_at")
    list_filter = ("status", "station")
    search_fields = ("station__name", "station__code")
    date_hierarchy = "start_date"

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
