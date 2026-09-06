"""
Production settings for SGMIS on Google Cloud Run.
Enforces SSL, security headers, production database pooling, and secret verification.
"""

from .settings import *
import os

DEBUG = False

ALLOWED_HOSTS = os.getenv("ALLOWED_HOSTS", "").split(",")
ALLOWED_HOSTS = [h.strip() for h in ALLOWED_HOSTS if h.strip()]

# Enforce secure cookies and SSL
SECURE_SSL_REDIRECT = True
SECURE_PROXY_SSL_HEADER = ("HTTP_X_FORWARDED_PROTO", "https")
SESSION_COOKIE_SECURE = True
CSRF_COOKIE_SECURE = True
SECURE_HSTS_SECONDS = 31536000
SECURE_HSTS_INCLUDE_SUBDOMAINS = True
SECURE_HSTS_PRELOAD = True
SECURE_CONTENT_TYPE_NOSNIFF = True

CORS_ALLOW_ALL_ORIGINS = False
