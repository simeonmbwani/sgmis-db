# SGMIS — Security Guard Management Information System

SGMIS is a security operations system with a Django/DRF backend, a browser command portal for administrators/supervisors, and an Android client for guards.

## What was corrected in this release

- Repaired the production migration problem where `accounts_user.station_id` was missing even though migration `0002` was marked applied.
- Added a safe `accounts.0003_add_station_column` repair migration. It checks the live schema before adding the column/index, so it is suitable for an existing Render PostgreSQL database.
- Added initial migrations for all operational apps that previously had models but no migration files: shifts, leave, patrols, occurrence book, incidents, escorts, exams, and notifications.
- Added the missing leave policy fields for casual leave (1 day/month, 12-day accumulation ceiling/cycle) and vacation leave (2.5 days/month, 90-day ceiling), while retaining legacy annual/sick fields for compatibility.
- Corrected the standard day shift to 07:00–18:00 and night shift to 18:00–07:00.
- Added Django admin management for operational modules.
- Fixed OB and incident creation so a guard's assigned station is automatically used when the client does not send one.
- Added Android screens/API support for My Profile, Notifications, and Management Escort/Exam Duties.
- Added the same missing modules to the browser command portal.
- Pointed the Android and hosted web clients at the current Render backend by default.
- Added a Render static-site definition for the command portal.

## Local backend

From `backend`:

```powershell
.venv\Scripts\Activate.ps1
python manage.py check
python manage.py makemigrations --check
python manage.py migrate
python manage.py test tests
python manage.py runserver
```

Backend: `http://127.0.0.1:8000/`
Admin: `http://127.0.0.1:8000/admin/`
API docs: `http://127.0.0.1:8000/api/docs/`

## Render deployment

The Render service is configured to run migrations during startup. Push the corrected project to the repository connected to Render and trigger a new deploy.

The deploy logs should contain the new migrations being applied, including:

```text
Applying accounts.0003_add_station_column... OK
Applying escorts.0001_initial... OK
Applying exams.0001_initial... OK
Applying incidents.0001_initial... OK
Applying leave.0001_initial... OK
Applying leave.0002_... OK
Applying notifications.0001_initial... OK
Applying occurrence_book.0001_initial... OK
Applying patrols.0001_initial... OK
Applying shifts.0001_initial... OK
```

Do not use `--fake` for these migrations.

After deployment verify:

- `/health/` returns HTTP 200 and `{"status":"ok","service":"sgmis-api"}`.
- `/admin/` loads without the `station_id` ProgrammingError.
- Guard login works through `/auth/login/`.
- `/api/docs/` loads and shows the operational endpoints.

## Render environment variables

Keep these configured on the backend service:

- `DATABASE_URL` — the Render PostgreSQL connection string.
- `DJANGO_SECRET_KEY` — generated secret.
- `SGMIS_INITIAL_ADMIN_PASSWORD` — initial administrator password.
- `ALLOWED_HOSTS` — include the Render backend hostname if Render does not provide it automatically.
- `CORS_ALLOWED_ORIGINS` — after deploying the static command portal, add its exact HTTPS origin.

## Android client

Open the repository root in Android Studio. The Android client is under `app/`.

The default production API is now:

```text
https://sgmis-db.onrender.com/
```

The app still allows the server URL to be overridden through the existing session/configuration mechanism.

Guard modules now include:

- Dashboard
- Today's Duty / clock-in and clock-out
- Shift Handover
- Occurrence Book
- Incident Reporting
- Station Patrols / checkpoints
- Leave Manager
- Management Escort & Exam Duties
- Notifications
- My Profile

## Browser command portal

The `frontend/` directory is a static browser portal for administrators and supervisors. It includes personnel, stations, guard pairs, roster, attendance, handovers, incidents, occurrence book, patrols, leave, escorts, exams, notifications and profile management.

If deployed as a Render static site, add the resulting HTTPS origin to the backend's `CORS_ALLOWED_ORIGINS` environment variable and redeploy/restart the backend.
