from django.contrib import admin
from .models import Checkpoint, PatrolLog, CheckpointScan

@admin.register(Checkpoint)
class CheckpointAdmin(admin.ModelAdmin):
    list_display = ("station", "order", "name", "code", "nfc_uid", "min_interval_seconds", "is_active")
    list_filter = ("station", "is_active")
    search_fields = ("name", "code", "nfc_uid", "station__name")

@admin.register(PatrolLog)
class PatrolLogAdmin(admin.ModelAdmin):
    list_display = ("start_time", "station", "guard", "status", "is_approved", "approved_by", "end_time")
    list_filter = ("status", "is_approved", "station")
    search_fields = ("guard__username", "guard__employee_number", "station__name")

@admin.register(CheckpointScan)
class CheckpointScanAdmin(admin.ModelAdmin):
    list_display = ("scanned_at", "patrol_log", "checkpoint", "gps_coords")
    list_filter = ("checkpoint__station",)
    search_fields = ("checkpoint__name", "checkpoint__code", "patrol_log__guard__username")
