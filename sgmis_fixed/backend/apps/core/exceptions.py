from rest_framework.views import exception_handler
from rest_framework.response import Response
from rest_framework import status
import logging

logger = logging.getLogger(__name__)

def custom_exception_handler(exc, context):
    """
    Uniform DRF exception handler ensuring predictable JSON responses
    for mobile and API clients under all circumstances.
    """
    response = exception_handler(exc, context)

    if response is not None:
        # Standardize error structure if necessary
        if isinstance(response.data, dict):
            if "detail" not in response.data and len(response.data) > 0:
                first_key = next(iter(response.data))
                first_val = response.data[first_key]
                if isinstance(first_val, list) and len(first_val) > 0:
                    response.data["detail"] = f"{first_key}: {first_val[0]}"
                elif isinstance(first_val, str):
                    response.data["detail"] = f"{first_key}: {first_val}"
            response.data["status_code"] = response.status_code
        elif isinstance(response.data, list):
            first_msg = response.data[0] if len(response.data) > 0 else "Validation error"
            response.data = {
                "detail": str(first_msg),
                "errors": response.data,
                "status_code": response.status_code,
            }
        return response

    # If DRF returned None, it's an unhandled exception inside a view
    logger.exception("Unhandled DRF view exception: %s", exc)
    return Response(
        {
            "detail": "Internal server error occurred.",
            "error": exc.__class__.__name__,
            "message": str(exc),
            "status_code": 500,
        },
        status=status.HTTP_500_INTERNAL_SERVER_ERROR,
    )
