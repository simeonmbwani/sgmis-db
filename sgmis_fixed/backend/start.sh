#!/usr/bin/env bash
# ==============================================================================
# SGMIS Backend Start Script (for Render / Linux Deployments)
# ==============================================================================
set -euo pipefail

echo "=== SGMIS Runtime: Checking Database Migrations ==="
if [ -n "${DATABASE_URL:-}" ]; then
    echo "Applying database migrations to external PostgreSQL..."
    python manage.py migrate --noinput
else
    echo "Notice: DATABASE_URL is not set. Running migrations on local fallback database..."
    python manage.py migrate --noinput
fi

if [ "${CREATE_INITIAL_SUPERUSER:-false}" = "true" ] || [ "${CREATE_INITIAL_SUPERUSER:-false}" = "1" ]; then
    echo "=== SGMIS Runtime: Initial superuser creation enabled ==="
    python manage.py create_initial_superuser || echo "Notice: Initial superuser creation did not complete or user already exists."
fi

PORT="${PORT:-10000}"
echo "=== SGMIS Runtime: Launching Gunicorn on port ${PORT} ==="
exec gunicorn sgmis_backend.wsgi:application \
    --bind "0.0.0.0:${PORT}" \
    --workers 2 \
    --threads 2 \
    --timeout 120 \
    --access-logfile - \
    --error-logfile -