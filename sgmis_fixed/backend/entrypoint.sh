#!/usr/bin/env bash
set -e

echo "=== SGMIS Production Backend Starting ==="

# Wait for database if needed, or apply migrations
echo "Applying database migrations..."
python manage.py migrate --noinput

echo "Collecting static files..."
python manage.py collectstatic --noinput

# If SGMIS_INITIAL_ADMIN_PASSWORD is set, bootstrap initial administrator
if [ -n "$SGMIS_INITIAL_ADMIN_PASSWORD" ]; then
    echo "Bootstrapping initial administrator account..."
    python manage.py bootstrap_admin || echo "Admin bootstrap warning (may already exist)."
fi

# Determine port for Cloud Run
PORT="${PORT:-8080}"
echo "Starting Gunicorn on port ${PORT}..."

exec gunicorn sgmis_backend.wsgi:application \
    --bind "0.0.0.0:${PORT}" \
    --workers 3 \
    --threads 2 \
    --timeout 120 \
    --access-logfile - \
    --error-logfile -
