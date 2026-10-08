from django.contrib import admin
from django.contrib.auth.admin import UserAdmin as BaseUserAdmin
from django.contrib.auth.forms import UserCreationForm, UserChangeForm
from django.contrib.auth import get_user_model
from .models import User


class CustomUserCreationForm(UserCreationForm):
    class Meta:
        model = get_user_model()
        fields = ("username", "email", "role", "employee_number", "station", "rank", "phone_number")

    def clean_employee_number(self):
        val = self.cleaned_data.get("employee_number")
        if val:
            val = val.strip()
        return val if val else None


class CustomUserChangeForm(UserChangeForm):
    class Meta:
        model = get_user_model()
        fields = "__all__"

    def clean_employee_number(self):
        val = self.cleaned_data.get("employee_number")
        if val:
            val = val.strip()
        return val if val else None


@admin.register(User)
class UserAdmin(BaseUserAdmin):
    form = CustomUserChangeForm
    add_form = CustomUserCreationForm

    list_display = (
        "username",
        "employee_number",
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
        "employee_number",
        "first_name",
        "last_name",
        "email",
    )

    fieldsets = (
        (None, {"fields": ("username", "password")}),
        (
            "Personal Info",
            {"fields": ("first_name", "last_name", "email", "phone_number", "address", "profile_photo")},
        ),
        (
            "Operational Details",
            {"fields": ("role", "employee_number", "station", "rank")},
        ),
        (
            "Permissions",
            {"fields": ("is_active", "is_staff", "is_superuser", "groups", "user_permissions")},
        ),
        (
            "Important Dates",
            {"fields": ("last_login", "date_joined")},
        ),
    )

    add_fieldsets = (
        (
            None,
            {
                "classes": ("wide",),
                "fields": (
                    "username",
                    "password1",
                    "password2",
                    "role",
                    "employee_number",
                    "station",
                    "rank",
                ),
            },
        ),
    )
