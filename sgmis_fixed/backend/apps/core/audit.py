import logging
from typing import Optional, Any, Dict
from apps.core.models import SecurityAuditEvent

logger = logging.getLogger(__name__)

def log_security_event(
    event_type: str,
    actor=None,
    actor_username: str = "",
    ip_address: str = "",
    target_model: str = "",
    target_id: str = "",
    details: Optional[Dict[str, Any]] = None,
) -> Optional[SecurityAuditEvent]:
    """
    Log an immutable security audit event.
    Safe against exceptions to prevent audit failures from breaking application flows,
    while logging to standard logger if database write fails.
    """
    try:
        username = actor_username
        user_instance = None

        if actor and getattr(actor, "is_authenticated", False):
            user_instance = actor
            if not username:
                username = getattr(actor, "username", "")
        elif not username and actor:
            username = str(actor)

        event = SecurityAuditEvent.objects.create(
            event_type=event_type,
            actor=user_instance,
            actor_username=username,
            ip_address=ip_address or "",
            target_model=target_model or "",
            target_id=str(target_id) if target_id else "",
            details=details or {},
        )
        return event
    except Exception as e:
        logger.error(
            f"Failed to record SecurityAuditEvent [{event_type}] for {actor_username or actor}: {e}",
            exc_info=True,
        )
        return None
