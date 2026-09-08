from django.contrib import admin
from .models import LeaveBalance, LeaveApplication

@admin.register(LeaveBalance)
class LeaveBalanceAdmin(admin.ModelAdmin):
    list_display = ("guard", "year", "annual_days", "used_annual", "sick_days", "used_sick", "remaining_annual", "remaining_sick")
    list_filter = ("year",)
    search_fields = ("guard__username", "guard__employee_number", "guard__first_name", "guard__last_name")

@admin.register(LeaveApplication)
class LeaveApplicationAdmin(admin.ModelAdmin):
    list_display = ("guard", "leave_type", "start_date", "end_date", "status", "reviewer")
    list_filter = ("leave_type", "status", "start_date")
    search_fields = ("guard__username", "guard__employee_number", "guard__first_name", "guard__last_name")
