#!/usr/bin/env bash
# ==============================================================================
# SGMIS Backend Start Script (for Render / Linux Deployments)
# ==============================================================================
set -euo pipefail

echo "=== SGMIS Runtime: Checking Database Migrations ==="
if [ -n "${DATABASE_URL:-}" ]; then
    echo "Applying database migrations to external PostgreSQL..."
    python manage.py migrate --noinput
    
    if [ -n "${SGMIS_INITIAL_ADMIN_PASSWORD:-}" ]; then
        echo "Ensuring initial administrator is bootstrapped..."
        python manage.py bootstrap_admin || echo "Admin bootstrap notice: administrator may already exist."
    fi
else
    echo "Notice: DATABASE_URL is not set. Running migrations on local fallback database..."
    python manage.py migrate --noinput
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
