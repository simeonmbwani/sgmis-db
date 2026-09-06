#!/usr/bin/env bash
# ==============================================================================
# SGMIS Pilot Cloud Run & Firebase Deployment Script
# Designed for minimum pilot expenditure (min instances = 0, scale-to-zero)
# ==============================================================================
set -euo pipefail

PROJECT_ID="${1:-sgmis-backend}"
REGION="${2:-europe-west2}"

echo "=== SGMIS Pilot Deployment ==="
echo "Target Project ID: ${PROJECT_ID}"
echo "Target Region: ${REGION}"

if [ -z "${PROJECT_ID}" ]; then
  echo "ERROR: Please specify a GCP Project ID as the first argument, e.g.:"
  echo "  ./deploy/deploy_cloud_run.sh my-gcp-project europe-west2"
  exit 1
fi

# 1. Enable required minimal APIs
echo "Enabling necessary Google Cloud APIs..."
gcloud services enable \
  run.googleapis.com \
  cloudbuild.googleapis.com \
  secretmanager.googleapis.com \
  --project="${PROJECT_ID}"

# 2. Build backend container using Cloud Build
echo "Submitting Docker build to Cloud Build..."
gcloud builds submit backend \
  --tag="gcr.io/${PROJECT_ID}/sgmis-api:latest" \
  --project="${PROJECT_ID}"

# 3. Grant Secret Accessor permission to Cloud Run service account
PROJECT_NUMBER=$(gcloud projects describe "${PROJECT_ID}" --format="value(projectNumber)")
RUN_SA="${PROJECT_NUMBER}-compute@developer.gserviceaccount.com"

echo "Granting Secret Accessor to Cloud Run Service Account: ${RUN_SA}..."
for SECRET_NAME in sgmis-database-url sgmis-initial-admin-password sgmis-django-secret-key; do
  gcloud secrets add-iam-policy-binding "${SECRET_NAME}" \
    --member="serviceAccount:${RUN_SA}" \
    --role="roles/secretmanager.secretAccessor" \
    --project="${PROJECT_ID}" --quiet || true
done

# 4. Deploy to Cloud Run with scale-to-zero configuration (min-instances=0)
# Production secrets are mounted from Secret Manager, NEVER passed as plaintext env vars.
# CORS is restricted via CORS_ALLOWED_ORIGINS, NEVER CORS_ALLOW_ALL.
CORS_ORIGINS="${3:-}"

echo "Deploying to Cloud Run (min-instances=0, 512MiB RAM)..."
gcloud run deploy sgmis-api \
  --image="gcr.io/${PROJECT_ID}/sgmis-api:latest" \
  --platform=managed \
  --region="${REGION}" \
  --project="${PROJECT_ID}" \
  --allow-unauthenticated \
  --min-instances=0 \
  --max-instances=2 \
  --memory=512Mi \
  --cpu=1 \
  --port=8080 \
  --set-env-vars="DJANGO_DEBUG=False,ALLOWED_HOSTS=*,CORS_ALLOWED_ORIGINS=${CORS_ORIGINS}" \
  --set-secrets="DATABASE_URL=sgmis-database-url:latest,SGMIS_INITIAL_ADMIN_PASSWORD=sgmis-initial-admin-password:latest,DJANGO_SECRET_KEY=sgmis-django-secret-key:latest"

# 5. Fetch the deployed HTTPS Cloud Run URL
SERVICE_URL=$(gcloud run services describe sgmis-api \
  --platform=managed \
  --region="${REGION}" \
  --project="${PROJECT_ID}" \
  --format="value(status.url)")

echo "=== Deployment Successful ==="
echo "Cloud Run HTTPS Service URL: ${SERVICE_URL}"
echo "Health Check URL: ${SERVICE_URL}/health/"
echo "OpenAPI Documentation: ${SERVICE_URL}/api/docs/"
echo ""
echo "Next steps:"
echo "1. Deploy web frontend: firebase deploy --only hosting --project=${PROJECT_ID}"
echo "2. If Firebase URL is https://${PROJECT_ID}.web.app, update CORS on Cloud Run:"
echo "   gcloud run services update sgmis-api --region=${REGION} --project=${PROJECT_ID} --update-env-vars=\"CORS_ALLOWED_ORIGINS=https://${PROJECT_ID}.web.app,https://${PROJECT_ID}.firebaseapp.com\""
