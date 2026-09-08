from django.contrib import admin
from .models import Notification

@admin.register(Notification)
class NotificationAdmin(admin.ModelAdmin):
    list_display = ("created_at", "user", "title", "notification_type", "read")
    list_filter = ("notification_type", "read", "created_at")
    search_fields = ("user__username", "user__employee_number", "title", "message")
    date_hierarchy = "created_at"
