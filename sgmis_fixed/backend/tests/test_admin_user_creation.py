from django.test import TestCase, Client
from django.contrib.auth import get_user_model
from apps.accounts.models import UserRole
from apps.stations.models import Station

UserModel = get_user_model()


class AdminUserCreationRegressionTests(TestCase):
    """
    Regression test suite for Django Admin User creation (/admin/accounts/user/add/).
    Verifies that:
    1. /admin/accounts/user/add/ loads with HTTP 200 without template context errors.
    2. Non-superuser accounts (Guards/Supervisors) can be created through Admin.
    3. Passwords submitted through Admin are cryptographically hashed, not stored in plaintext.
    4. Created users can successfully authenticate with check_password.
    5. Blank employee numbers are saved cleanly as NULL/None without collision.
    """

    def setUp(self):
        self.client = Client()
        self.admin_password = "SecureAdminPassword123!"
        self.admin = UserModel.objects.create_superuser(
            username="test_admin_runner",
            email="admin@sgmis.local",
            password=self.admin_password,
            role=UserRole.ADMINISTRATOR,
            employee_number="SEC-ADM999",
        )
        self.station = Station.objects.create(
            name="Command Station North",
            code="STN-NRTH01",
            address="100 North Gate Road",
        )
        self.client.force_login(self.admin)

    def test_admin_user_add_page_loads_http_200(self):
        """Verify GET /admin/accounts/user/add/ renders successfully (regression for Python 3.14 template copy)."""
        response = self.client.get("/admin/accounts/user/add/")
        self.assertEqual(response.status_code, 200)
        self.assertContains(response, "Add user")
        self.assertContains(response, "username")
        self.assertContains(response, "password1")
        self.assertContains(response, "password2")

    def test_admin_create_guard_user_success_and_password_hashed(self):
        """Verify creating a Guard through Admin securely hashes password and sets attributes."""
        post_data = {
            "username": "guard_johndoe",
            "password1": "GuardComplexPass123!",
            "password2": "GuardComplexPass123!",
            "role": UserRole.GUARD,
            "employee_number": "SEC-GD001",
            "station": str(self.station.id),
            "rank": "Senior Patrol Officer",
            "_save": "Save",
        }
        response = self.client.post("/admin/accounts/user/add/", data=post_data, follow=False)
        self.assertEqual(response.status_code, 302)

        # Confirm user was created in database
        guard = UserModel.objects.filter(username="guard_johndoe").first()
        self.assertIsNotNone(guard)
        self.assertEqual(guard.role, UserRole.GUARD)
        self.assertEqual(guard.employee_number, "SEC-GD001")
        self.assertEqual(guard.station, self.station)
        self.assertEqual(guard.rank, "Senior Patrol Officer")
        self.assertFalse(guard.is_superuser)
        self.assertFalse(guard.is_staff)
        self.assertTrue(guard.is_active)

        # Cryptographic password security verification
        from django.contrib.auth.hashers import identify_hasher, is_password_usable
        self.assertNotIn("GuardComplexPass123!", guard.password)
        self.assertTrue(is_password_usable(guard.password))
        self.assertIsNotNone(identify_hasher(guard.password))
        self.assertTrue(guard.check_password("GuardComplexPass123!"))
        self.assertFalse(guard.check_password("WrongPassword123!"))

    def test_admin_create_supervisor_user_success(self):
        """Verify creating a Supervisor through Admin sets role and station properly."""
        post_data = {
            "username": "sup_janedoe",
            "password1": "SupervisorPass123!",
            "password2": "SupervisorPass123!",
            "role": UserRole.SUPERVISOR,
            "employee_number": "SEC-SUP001",
            "station": str(self.station.id),
            "rank": "Duty Supervisor",
            "_save": "Save",
        }
        response = self.client.post("/admin/accounts/user/add/", data=post_data, follow=False)
        self.assertEqual(response.status_code, 302)

        supervisor = UserModel.objects.filter(username="sup_janedoe").first()
        self.assertIsNotNone(supervisor)
        self.assertEqual(supervisor.role, UserRole.SUPERVISOR)
        self.assertEqual(supervisor.employee_number, "SEC-SUP001")
        self.assertTrue(supervisor.check_password("SupervisorPass123!"))

    def test_admin_create_user_with_blank_employee_number(self):
        """Verify blank employee_number is saved as None/NULL without unique constraint conflict."""
        post_data1 = {
            "username": "guard_no_emp_1",
            "password1": "Password123456!",
            "password2": "Password123456!",
            "role": UserRole.GUARD,
            "employee_number": "",
            "_save": "Save",
        }
        response1 = self.client.post("/admin/accounts/user/add/", data=post_data1, follow=False)
        self.assertEqual(response1.status_code, 302)

        post_data2 = {
            "username": "guard_no_emp_2",
            "password1": "Password123456!",
            "password2": "Password123456!",
            "role": UserRole.GUARD,
            "employee_number": "",
            "_save": "Save",
        }
        response2 = self.client.post("/admin/accounts/user/add/", data=post_data2, follow=False)
        self.assertEqual(response2.status_code, 302)

        u1 = UserModel.objects.get(username="guard_no_emp_1")
        u2 = UserModel.objects.get(username="guard_no_emp_2")
        self.assertIsNone(u1.employee_number)
        self.assertIsNone(u2.employee_number)
