from django.contrib import admin
from .models import EscortDuty

@admin.register(EscortDuty)
class EscortDutyAdmin(admin.ModelAdmin):
    list_display = ("start_time", "guard", "mission_name", "origin", "destination", "status")
    list_filter = ("status", "start_time")
    search_fields = ("mission_name", "guard__username", "guard__employee_number", "destination")
