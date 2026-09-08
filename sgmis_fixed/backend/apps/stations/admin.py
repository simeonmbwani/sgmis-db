from django.contrib import admin
from .models import Station, GuardPair


@admin.register(Station)
class StationAdmin(admin.ModelAdmin):
    list_display = (
        "name",
        "code",
        "is_active",
        "geofence_radius_meters",
        "created_at",
    )
    list_filter = ("is_active",)
    search_fields = ("name", "code", "address")


@admin.register(GuardPair)
class GuardPairAdmin(admin.ModelAdmin):
    list_display = (
        "station",
        "rotation_order",
        "guard_a",
        "guard_b",
        "is_active",
        "created_at",
    )
    list_filter = ("station", "is_active")
    search_fields = (
        "guard_a__username",
        "guard_a__first_name",
        "guard_a__last_name",
        "guard_b__username",
        "guard_b__first_name",
        "guard_b__last_name",
    )
