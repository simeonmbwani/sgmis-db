from rest_framework.permissions import BasePermission
from .models import UserRole

class IsAdministrator(BasePermission):
    """Allows access only to Administrators."""
    def has_permission(self, request, view):
        return bool(
            request.user and request.user.is_authenticated and
            (request.user.role == UserRole.ADMINISTRATOR or request.user.is_superuser)
        )

class IsSupervisor(BasePermission):
    """Allows access to Supervisors (and Administrators)."""
    def has_permission(self, request, view):
        return bool(
            request.user and request.user.is_authenticated and
            (request.user.role in (UserRole.SUPERVISOR, UserRole.ADMINISTRATOR) or request.user.is_superuser)
        )

class IsGuard(BasePermission):
    """Allows access to Guards."""
    def has_permission(self, request, view):
        return bool(
            request.user and request.user.is_authenticated and request.user.role == UserRole.GUARD
        )

class IsSupervisorOrAdmin(BasePermission):
    """Allows access to Supervisors and Administrators."""
    def has_permission(self, request, view):
        return bool(
            request.user and request.user.is_authenticated and
            (request.user.role in (UserRole.SUPERVISOR, UserRole.ADMINISTRATOR) or request.user.is_superuser)
        )
