from django.contrib import admin
from .models import User


@admin.register(User)
class UserAdmin(admin.ModelAdmin):
    list_display = (
        "username",
        "first_name",
        "last_name",
        "email",
        "role",
        "station",
        "is_active",
    )
    list_filter = ("role", "is_active", "station")
    search_fields = (
        "username",
        "first_name",
        "last_name",
        "email",
    )
