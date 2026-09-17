import json
import logging
from django.core.serializers.json import DjangoJSONEncoder
from rest_framework.response import Response
from apps.core.models import IdempotencyRecord

logger = logging.getLogger(__name__)

def get_idempotency_key(request) -> str:
    return (
        request.headers.get("X-Idempotency-Key")
        or request.headers.get("Idempotency-Key")
        or request.META.get("HTTP_X_IDEMPOTENCY_KEY")
        or request.META.get("HTTP_IDEMPOTENCY_KEY")
        or ""
    ).strip()

def check_idempotency(request):
    key = get_idempotency_key(request)
    if not key or not request.user or not request.user.is_authenticated:
        return None
    try:
        record = IdempotencyRecord.objects.filter(key=key, user=request.user).first()
        if record:
            return Response(record.response_body, status=record.response_status)
    except Exception as e:
        logger.warning(f"Error checking idempotency record for key {key}: {e}")
    return None

def store_idempotency(request, response):
    key = get_idempotency_key(request)
    if not key or not request.user or not request.user.is_authenticated:
        return
    try:
        if 200 <= response.status_code < 300:
            data = response.data if hasattr(response, "data") else {}
            safe_body = json.loads(json.dumps(data, cls=DjangoJSONEncoder))
            IdempotencyRecord.objects.get_or_create(
                key=key,
                defaults={
                    "user": request.user,
                    "endpoint": request.path,
                    "response_status": response.status_code,
                    "response_body": safe_body,
                },
            )
    except Exception as e:
        logger.warning(f"Error storing idempotency record for key {key}: {e}")
