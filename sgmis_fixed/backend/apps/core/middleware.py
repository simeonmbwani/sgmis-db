import json
from django.http import JsonResponse
from django.utils.deprecation import MiddlewareMixin

class JsonOnlyApiMiddleware(MiddlewareMixin):
    """
    Guarantees that any request to API, auth, or operational modules returns
    a well-formed JSON response (never HTML error pages or redirects) on failure
    or unhandled exceptions, protecting mobile and REST clients (such as Retrofit/Moshi).
    """

    API_PREFIXES = (
        "/api/",
        "/auth/",
        "/core/",
        "/stations/",
        "/shifts/",
        "/leave/",
        "/patrols/",
        "/occurrence_book/",
        "/incidents/",
        "/escorts/",
        "/exams/",
        "/notifications/",
        "/accounts/",
        "/telemetry/",
        "/health/",
    )

    def is_api_request(self, request):
        path = request.path
        if any(path.startswith(prefix) for prefix in self.API_PREFIXES):
            return True
        accept = request.META.get("HTTP_ACCEPT", "")
        if "application/json" in accept:
            return True
        content_type = request.META.get("CONTENT_TYPE", "")
        if "application/json" in content_type:
            return True
        return False

    def process_response(self, request, response):
        if not self.is_api_request(request):
            return response

        # If it's an error response (>= 400) and not already application/json, convert to JSON
        content_type = response.get("Content-Type", "")
        if response.status_code >= 400 and not content_type.startswith("application/json"):
            status_code = response.status_code
            detail_msg = getattr(response, "reason_phrase", "An error occurred.")
            if status_code == 401:
                detail_msg = "Authentication credentials were not provided or have expired."
            elif status_code == 403:
                detail_msg = "You do not have permission to perform this action."
            elif status_code == 404:
                detail_msg = f"Resource not found at {request.path}"
            elif status_code == 405:
                detail_msg = f"Method {request.method} not allowed for {request.path}"
            elif status_code >= 500:
                detail_msg = "An internal server error occurred. Please contact system administration."

            return JsonResponse(
                {
                    "detail": detail_msg,
                    "error": f"HTTP_{status_code}",
                    "status_code": status_code,
                    "path": request.path,
                },
                status=status_code,
            )

        return response

    def process_exception(self, request, exception):
        if not self.is_api_request(request):
            return None

        return JsonResponse(
            {
                "detail": "An unhandled server exception occurred.",
                "error": exception.__class__.__name__,
                "message": str(exception),
                "status_code": 500,
                "path": request.path,
            },
            status=500,
        )
