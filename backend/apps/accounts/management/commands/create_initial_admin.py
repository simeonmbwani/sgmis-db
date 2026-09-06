import os
from django.core.management.base import BaseCommand, CommandError
from django.contrib.auth import get_user_model
from apps.accounts.models import UserRole

class Command(BaseCommand):
    help = "Bootstrap initial administrator account using SGMIS_INITIAL_ADMIN_PASSWORD environment variable."

    def handle(self, *args, **options):
        password = os.getenv("SGMIS_INITIAL_ADMIN_PASSWORD")
        if not password:
            raise CommandError(
                "SGMIS_INITIAL_ADMIN_PASSWORD environment variable is not set. "
                "Please configure this secret before bootstrapping the initial administrator."
            )

        username = "simeonmbwani"
        email = "simeonmbwani@gmail.com"
        UserModel = get_user_model()

        user, created = UserModel.objects.get_or_create(
            username=username,
            defaults={
                "email": email,
                "role": UserRole.ADMINISTRATOR,
                "first_name": "Simeon",
                "last_name": "Mbwani",
                "employee_number": "SEC-ADM01",
                "rank": "Chief Security Administrator",
                "is_staff": True,
                "is_superuser": True,
                "is_active": True,
            }
        )

        # Update attributes and set password securely
        user.email = email
        user.role = UserRole.ADMINISTRATOR
        user.is_staff = True
        user.is_superuser = True
        user.is_active = True
        user.set_password(password)
        user.save()

        if created:
            self.stdout.write(self.style.SUCCESS(f"Initial administrator '{username}' created successfully."))
        else:
            self.stdout.write(self.style.SUCCESS(f"Initial administrator '{username}' updated successfully."))
