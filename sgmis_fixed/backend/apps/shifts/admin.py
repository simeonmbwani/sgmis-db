from django.contrib import admin
from .models import DutyRosterCycle, Shift, Attendance, ShiftHandover

@admin.register(DutyRosterCycle)
class DutyRosterCycleAdmin(admin.ModelAdmin):
    list_display = ("station", "cycle_start_date", "cycle_length_days", "rotation_order", "is_active")
    list_filter = ("station", "is_active")
    search_fields = ("station__name", "station__code")

@admin.register(Shift)
class ShiftAdmin(admin.ModelAdmin):
    list_display = ("date", "shift_type", "station", "guard", "pair", "start_time", "end_time", "is_override")
    list_filter = ("date", "shift_type", "station", "is_override")
    search_fields = ("guard__username", "guard__employee_number", "station__name")
    date_hierarchy = "date"

@admin.register(Attendance)
class AttendanceAdmin(admin.ModelAdmin):
    list_display = ("shift", "guard", "clock_in", "clock_out", "is_late")
    list_filter = ("is_late", "shift__shift_type", "shift__station")
    search_fields = ("guard__username", "guard__employee_number")

@admin.register(ShiftHandover)
class ShiftHandoverAdmin(admin.ModelAdmin):
    list_display = ("created_at", "station", "outgoing_guard", "incoming_guard", "incoming_accepted")
    list_filter = ("incoming_accepted", "station")
    search_fields = ("outgoing_guard__username", "incoming_guard__username", "station__name")
