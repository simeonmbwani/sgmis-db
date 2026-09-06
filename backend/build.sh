#!/usr/bin/env bash
# ==============================================================================
# SGMIS Backend Build Script (for Render / Linux Deployments)
# ==============================================================================
set -euo pipefail

echo "=== SGMIS Build: Installing dependencies ==="
pip install --upgrade pip
pip install -r requirements.txt

echo "=== SGMIS Build: Collecting static assets via WhiteNoise ==="
python manage.py collectstatic --noinput

echo "=== SGMIS Build: Complete ==="
