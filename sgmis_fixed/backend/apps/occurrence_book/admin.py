from django.contrib import admin
from .models import OccurrenceBookEntry

@admin.register(OccurrenceBookEntry)
class OccurrenceBookEntryAdmin(admin.ModelAdmin):
    list_display = ("entry_number", "created_at", "station", "guard", "category", "check_record")
    list_filter = ("station", "category", "created_at")
    search_fields = ("entry_number", "occurrence_text", "guard__username", "guard__employee_number")
    date_hierarchy = "created_at"
