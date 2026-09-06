from rest_framework.decorators import api_view, permission_classes
from rest_framework.permissions import AllowAny
from rest_framework.response import Response

@api_view(["GET"])
@permission_classes([AllowAny])
def health_check(request):
    """
    Service health check endpoint.
    Safe for load balancers and container orchestrators.
    """
    return Response({
        "status": "ok",
        "service": "sgmis-api",
    })
