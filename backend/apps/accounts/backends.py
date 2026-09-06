from django.contrib.auth.backends import ModelBackend
from django.contrib.auth import get_user_model
from django.db.models import Q

class UsernameOrEmployeeNumberBackend(ModelBackend):
    """
    Authenticate against User model using either username OR employee_number.
    """
    def authenticate(self, request, username=None, password=None, **kwargs):
        UserModel = get_user_model()
        if username is None:
            username = kwargs.get("employee_number") or kwargs.get("identifier")
        if not username or not password:
            return None

        try:
            # Query by exact username or employee_number (case-insensitive where possible)
            user = UserModel.objects.get(
                Q(username__iexact=username) | Q(employee_number__iexact=username)
            )
        except UserModel.DoesNotExist:
            return None
        except UserModel.MultipleObjectsReturned:
            user = UserModel.objects.filter(
                Q(username__iexact=username) | Q(employee_number__iexact=username)
            ).first()

        if user and user.check_password(password) and self.user_can_authenticate(user):
            return user
        return None
