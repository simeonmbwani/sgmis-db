import os
from unittest.mock import patch
from django.test import TestCase
from django.core.management import call_command
from io import StringIO
from apps.accounts.models import User, UserRole


class CreateInitialSuperuserCommandTests(TestCase):
    def test_skipped_when_flag_not_enabled(self):
        out = StringIO()
        err = StringIO()
        with patch.dict(os.environ, {}, clear=True):
            call_command("create_initial_superuser", stdout=out, stderr=err)
        
        self.assertIn("Skipped", out.getvalue())
        self.assertEqual(User.objects.filter(is_superuser=True).count(), 0)

    def test_skipped_when_flag_false_or_arbitrary(self):
        out = StringIO()
        err = StringIO()
        with patch.dict(os.environ, {"CREATE_INITIAL_SUPERUSER": "false"}, clear=True):
            call_command("create_initial_superuser", stdout=out, stderr=err)
        
        self.assertIn("Skipped", out.getvalue())
        self.assertEqual(User.objects.filter(is_superuser=True).count(), 0)

    def test_missing_username_or_password(self):
        out = StringIO()
        err = StringIO()
        env = {
            "CREATE_INITIAL_SUPERUSER": "true",
            "SUPERUSER_USERNAME": "testadmin",
            # No password
        }
        with patch.dict(os.environ, env, clear=True):
            call_command("create_initial_superuser", stdout=out, stderr=err)
        
        self.assertIn("Required password environment variable", err.getvalue())
        self.assertEqual(User.objects.filter(username="testadmin").count(), 0)

    def test_creates_superuser_successfully(self):
        out = StringIO()
        err = StringIO()
        env = {
            "CREATE_INITIAL_SUPERUSER": "true",
            "SUPERUSER_USERNAME": "testadmin",
            "SUPERUSER_PASSWORD": "SecureAdminPassword123!",
            "SUPERUSER_EMAIL": "testadmin@example.com",
            "SUPERUSER_EMPLOYEE_NUMBER": "SEC-TEST01",
        }
        with patch.dict(os.environ, env, clear=True):
            call_command("create_initial_superuser", stdout=out, stderr=err)
        
        self.assertIn("created successfully", out.getvalue())
        self.assertNotIn("SecureAdminPassword123!", out.getvalue())
        self.assertNotIn("SecureAdminPassword123!", err.getvalue())

        user = User.objects.get(username="testadmin")
        self.assertTrue(user.is_superuser)
        self.assertTrue(user.is_staff)
        self.assertTrue(user.is_active)
        self.assertEqual(user.role, UserRole.ADMINISTRATOR)
        self.assertEqual(user.employee_number, "SEC-TEST01")
        self.assertEqual(user.email, "testadmin@example.com")
        self.assertTrue(user.check_password("SecureAdminPassword123!"))
        # Password is not plaintext
        self.assertNotEqual(user.password, "SecureAdminPassword123!")

    def test_noop_when_user_already_exists(self):
        # Create an existing user with known password
        user = User.objects.create_superuser(
            username="existingadmin",
            email="existing@example.com",
            password="OriginalPassword456!",
            role=UserRole.ADMINISTRATOR,
            employee_number="SEC-ORIG01",
        )

        out = StringIO()
        err = StringIO()
        env = {
            "CREATE_INITIAL_SUPERUSER": "true",
            "SUPERUSER_USERNAME": "existingadmin",
            "SUPERUSER_PASSWORD": "NewDifferentPassword789!",
            "SUPERUSER_EMAIL": "newemail@example.com",
        }
        with patch.dict(os.environ, env, clear=True):
            call_command("create_initial_superuser", stdout=out, stderr=err)

        self.assertIn("already exists. No action taken", out.getvalue())
        
        # Verify user was NOT modified
        user.refresh_from_db()
        self.assertTrue(user.check_password("OriginalPassword456!"))
        self.assertFalse(user.check_password("NewDifferentPassword789!"))
        self.assertEqual(user.email, "existing@example.com")

    def test_conflict_on_existing_employee_number(self):
        # Create an existing user with employee number SEC-ADM01
        User.objects.create_user(
            username="guard_one",
            password="GuardPassword123!",
            employee_number="SEC-ADM01",
            role=UserRole.GUARD,
        )

        out = StringIO()
        err = StringIO()
        env = {
            "CREATE_INITIAL_SUPERUSER": "true",
            "SUPERUSER_USERNAME": "newadmin",
            "SUPERUSER_PASSWORD": "AdminPassword123!",
            "SUPERUSER_EMPLOYEE_NUMBER": "SEC-ADM01",
        }
        with patch.dict(os.environ, env, clear=True):
            call_command("create_initial_superuser", stdout=out, stderr=err)

        self.assertIn("already assigned to another user", err.getvalue())
        self.assertFalse(User.objects.filter(username="newadmin").exists())
        # The existing user is unharmed
        guard = User.objects.get(username="guard_one")
        self.assertEqual(guard.role, UserRole.GUARD)

    def test_auto_increments_employee_number_when_default_sec_adm01_taken(self):
        # Create an existing user who holds SEC-ADM01
        User.objects.create_user(
            username="existing_user",
            password="Password123!",
            employee_number="SEC-ADM01",
            role=UserRole.GUARD,
        )

        out = StringIO()
        err = StringIO()
        env = {
            "CREATE_INITIAL_SUPERUSER": "true",
            "SUPERUSER_USERNAME": "autoadmin",
            "SUPERUSER_PASSWORD": "AdminPassword123!",
            # No SUPERUSER_EMPLOYEE_NUMBER provided
        }
        with patch.dict(os.environ, env, clear=True):
            call_command("create_initial_superuser", stdout=out, stderr=err)

        self.assertIn("created successfully", out.getvalue())
        admin = User.objects.get(username="autoadmin")
        self.assertTrue(admin.is_superuser)
        self.assertEqual(admin.employee_number, "SEC-ADM02")

