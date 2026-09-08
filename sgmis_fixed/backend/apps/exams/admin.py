from django.contrib import admin
from .models import ExamDuty

@admin.register(ExamDuty)
class ExamDutyAdmin(admin.ModelAdmin):
    list_display = ("date", "guard", "institution", "exam_title", "start_time", "end_time", "status")
    list_filter = ("date", "status", "institution")
    search_fields = ("institution", "exam_title", "guard__username", "guard__employee_number")
