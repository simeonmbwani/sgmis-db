from django.contrib import admin
from .models import IncidentReport

@admin.register(IncidentReport)
class IncidentReportAdmin(admin.ModelAdmin):
    list_display = ("created_at", "priority", "title", "station", "reporting_guard", "status", "acknowledged_by")
    list_filter = ("priority", "status", "station", "created_at")
    search_fields = ("title", "description", "location", "reporting_guard__username", "reporting_guard__employee_number")
    date_hierarchy = "created_at"
