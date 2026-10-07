import os
from django.core.management.base import BaseCommand
from django.contrib.auth import get_user_model
from apps.accounts.models import UserRole


class Command(BaseCommand):
    help = "Safely bootstrap an initial Django superuser from environment variables when explicitly enabled."

    def handle(self, *args, **options):
        # 1. Run ONLY when CREATE_INITIAL_SUPERUSER is exactly "true" or "1"
        flag = os.getenv("CREATE_INITIAL_SUPERUSER", "").strip().lower()
        if flag not in ("true", "1"):
            self.stdout.write(
                "create_initial_superuser: Skipped (CREATE_INITIAL_SUPERUSER is not set to 'true' or '1')."
            )
            return

        # 2. Read credentials and employee number from environment variables
        username = (
            os.getenv("SUPERUSER_USERNAME")
            or os.getenv("DJANGO_SUPERUSER_USERNAME")
            or os.getenv("ADMIN_USERNAME")
            or "admin"
        ).strip()

        password = (
            os.getenv("SUPERUSER_PASSWORD")
            or os.getenv("DJANGO_SUPERUSER_PASSWORD")
            or os.getenv("SGMIS_INITIAL_ADMIN_PASSWORD")
            or ""
        )

        email = (
            os.getenv("SUPERUSER_EMAIL")
            or os.getenv("DJANGO_SUPERUSER_EMAIL")
            or ""
        ).strip()

        employee_number = (
            os.getenv("SUPERUSER_EMPLOYEE_NUMBER")
            or os.getenv("DJANGO_SUPERUSER_EMPLOYEE_NUMBER")
            or "SEC-ADM01"
        ).strip()

        if not username:
            username = "admin"

        if not password:
            self.stderr.write(
                "create_initial_superuser: Required password environment variable (SUPERUSER_PASSWORD or DJANGO_SUPERUSER_PASSWORD) is missing. No user created."
            )
            return

        UserModel = get_user_model()

        # 3. Check whether the username already exists
        if UserModel.objects.filter(username=username).exists():
            self.stdout.write(
                self.style.NOTICE(
                    f"create_initial_superuser: User '{username}' already exists. No action taken."
                )
            )
            return

        # 4. Check whether employee_number is already in use by another user
        if UserModel.objects.filter(employee_number=employee_number).exists():
            self.stderr.write(
                self.style.ERROR(
                    f"create_initial_superuser: Employee number '{employee_number}' is already assigned to another user. Superuser '{username}' was not created."
                )
            )
            return

        # 5. Create a proper Django superuser with securely hashed password
        try:
            extra_fields = {
                "role": UserRole.ADMINISTRATOR,
                "is_staff": True,
                "is_superuser": True,
                "is_active": True,
                "employee_number": employee_number,
            }

            user = UserModel.objects.create_superuser(
                username=username,
                email=email,
                password=password,
                **extra_fields,
            )
            self.stdout.write(
                self.style.SUCCESS(
                    f"create_initial_superuser: Superuser '{username}' created successfully."
                )
            )
        except Exception as e:
            # Never print password or credentials in error messages
            self.stderr.write(
                self.style.ERROR(
                    f"create_initial_superuser: Failed to create superuser '{username}'. Reason: {type(e).__name__}: {str(e)}"
                )
            )
