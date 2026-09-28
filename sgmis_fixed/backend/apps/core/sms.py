import logging
import os
import re
import sys
from dataclasses import dataclass, field
from datetime import datetime
from typing import List, Optional
from django.conf import settings

logger = logging.getLogger("sgmis.sms")

@dataclass
class SMSResult:
    success: bool
    to_phone: str
    provider: str
    message_id: str = ""
    error: str = ""
    timestamp: str = field(default_factory=lambda: datetime.utcnow().isoformat())


def mask_phone_number(phone: str) -> str:
    """Masks phone number for secure audit logging without exposing PII."""
    clean = re.sub(r"[^\d+]", "", phone.strip())
    if len(clean) <= 4:
        return "****"
    return f"{clean[:3]}****{clean[-4:]}"


class BaseSMSProvider:
    """Abstract base class for SGMIS SMS gateway providers."""
    name: str = "base"

    def send(self, to_phone: str, message: str) -> SMSResult:
        raise NotImplementedError("Subclasses must implement send()")


class InMemorySMSProvider(BaseSMSProvider):
    """
    In-memory SMS provider for testing, test suites, and deterministic verification.
    Records all dispatched SMS messages to an accessible outbox.
    """
    name: str = "memory"
    outbox: List[dict] = []

    def send(self, to_phone: str, message: str) -> SMSResult:
        msg_id = f"mem-sms-{len(self.outbox) + 1:05d}"
        record = {
            "to": to_phone,
            "message": message,
            "message_id": msg_id,
            "timestamp": datetime.utcnow().isoformat(),
        }
        self.outbox.append(record)
        logger.info("InMemorySMS: Sent message to %s (id=%s)", mask_phone_number(to_phone), msg_id)
        return SMSResult(success=True, to_phone=to_phone, provider=self.name, message_id=msg_id)

    @classmethod
    def get_last_sms(cls, to_phone: Optional[str] = None) -> Optional[dict]:
        if not cls.outbox:
            return None
        if to_phone is None:
            return cls.outbox[-1]
        for item in reversed(cls.outbox):
            if item["to"] == to_phone or item["to"].endswith(to_phone[-6:]):
                return item
        return None

    @classmethod
    def clear(cls):
        cls.outbox.clear()


class ConsoleSMSProvider(BaseSMSProvider):
    """Development console SMS provider that logs dispatches to logger and outbox."""
    name: str = "console"

    def send(self, to_phone: str, message: str) -> SMSResult:
        msg_id = f"con-sms-{datetime.utcnow().strftime('%Y%m%d%H%M%S%f')}"
        logger.info("[SMS CONSOLE DISPATCH] Destination: %s | ID: %s", mask_phone_number(to_phone), msg_id)
        # Also preserve in memory outbox so tests/diagnostics can inspect
        InMemorySMSProvider.outbox.append({
            "to": to_phone,
            "message": message,
            "message_id": msg_id,
            "timestamp": datetime.utcnow().isoformat(),
        })
        return SMSResult(success=True, to_phone=to_phone, provider=self.name, message_id=msg_id)


class TwilioSMSProvider(BaseSMSProvider):
    """Production Twilio SMS gateway implementation."""
    name: str = "twilio"

    def send(self, to_phone: str, message: str) -> SMSResult:
        sid = getattr(settings, "TWILIO_ACCOUNT_SID", "") or os.getenv("TWILIO_ACCOUNT_SID", "")
        token = getattr(settings, "TWILIO_AUTH_TOKEN", "") or os.getenv("TWILIO_AUTH_TOKEN", "")
        from_num = getattr(settings, "TWILIO_FROM_NUMBER", "") or os.getenv("TWILIO_FROM_NUMBER", "")

        if not sid or not token or not from_num:
            err = "Twilio credentials (ACCOUNT_SID, AUTH_TOKEN, FROM_NUMBER) are not fully configured."
            logger.error("Twilio SMS dispatch failed: %s", err)
            return SMSResult(success=False, to_phone=to_phone, provider=self.name, error=err)

        try:
            import urllib.request
            import urllib.parse
            import base64
            import json

            url = f"https://api.twilio.com/2010-04-01/Accounts/{sid}/Messages.json"
            data = urllib.parse.urlencode({
                "To": to_phone,
                "From": from_num,
                "Body": message,
            }).encode("utf-8")

            auth_str = f"{sid}:{token}"
            auth_b64 = base64.b64encode(auth_str.encode("utf-8")).decode("utf-8")

            req = urllib.request.Request(url, data=data, method="POST")
            req.add_header("Authorization", f"Basic {auth_b64}")

            with urllib.request.urlopen(req, timeout=10) as resp:
                resp_data = json.loads(resp.read().decode("utf-8"))
                msg_id = resp_data.get("sid", "")
                logger.info("Twilio SMS sent to %s (sid=%s)", mask_phone_number(to_phone), msg_id)
                return SMSResult(success=True, to_phone=to_phone, provider=self.name, message_id=msg_id)
        except Exception as exc:
            logger.error("Twilio SMS exception: %s", exc)
            return SMSResult(success=False, to_phone=to_phone, provider=self.name, error=str(exc))


class GenericHttpSMSProvider(BaseSMSProvider):
    """Generic HTTP Gateway for regional carriers (e.g., Econet, NetOne)."""
    name: str = "http_gateway"

    def send(self, to_phone: str, message: str) -> SMSResult:
        gw_url = getattr(settings, "SMS_GATEWAY_URL", "") or os.getenv("SMS_GATEWAY_URL", "")
        api_key = getattr(settings, "SMS_GATEWAY_API_KEY", "") or os.getenv("SMS_GATEWAY_API_KEY", "")

        if not gw_url:
            err = "SMS_GATEWAY_URL is not configured."
            logger.error("GenericHttp SMS failed: %s", err)
            return SMSResult(success=False, to_phone=to_phone, provider=self.name, error=err)

        try:
            import urllib.request
            import json

            payload = json.dumps({
                "to": to_phone,
                "message": message,
            }).encode("utf-8")

            req = urllib.request.Request(gw_url, data=payload, method="POST")
            req.add_header("Content-Type", "application/json")
            if api_key:
                req.add_header("Authorization", f"Bearer {api_key}")

            with urllib.request.urlopen(req, timeout=10) as resp:
                msg_id = f"gw-{datetime.utcnow().strftime('%Y%m%d%H%M%S')}"
                return SMSResult(success=True, to_phone=to_phone, provider=self.name, message_id=msg_id)
        except Exception as exc:
            logger.error("GenericHttp SMS exception: %s", exc)
            return SMSResult(success=False, to_phone=to_phone, provider=self.name, error=str(exc))


_provider_instances = {}

def get_sms_provider() -> BaseSMSProvider:
    """Returns the configured SMS provider instance."""
    backend = getattr(settings, "SMS_BACKEND", None) or os.getenv("SMS_BACKEND", "memory")

    # In test mode, always use in-memory provider for isolation and test introspection
    if "test" in sys.argv or getattr(settings, "TESTING", False):
        backend = "memory"

    backend = backend.lower()
    if backend not in _provider_instances:
        if backend == "twilio":
            _provider_instances[backend] = TwilioSMSProvider()
        elif backend in ("http", "gateway", "http_gateway"):
            _provider_instances[backend] = GenericHttpSMSProvider()
        elif backend == "console":
            _provider_instances[backend] = ConsoleSMSProvider()
        else:
            _provider_instances[backend] = InMemorySMSProvider()

    return _provider_instances[backend]


def send_sms(to_phone: str, message: str) -> SMSResult:
    """
    High-level SGMIS SMS dispatch interface.
    Validates destination phone number and delivers message via configured provider.
    """
    if not to_phone or not to_phone.strip():
        logger.warning("send_sms called with empty or blank phone number")
        return SMSResult(success=False, to_phone="", provider="none", error="Destination mobile number is required.")

    clean_phone = re.sub(r"[^\d+]", "", to_phone.strip())
    if len(re.sub(r"[^\d]", "", clean_phone)) < 6:
        logger.warning("send_sms called with invalid phone number format: %s", mask_phone_number(clean_phone))
        return SMSResult(success=False, to_phone=clean_phone, provider="none", error="Invalid mobile number format.")

    provider = get_sms_provider()
    return provider.send(clean_phone, message)


# Re-export for test assertions
sms_outbox = InMemorySMSProvider.outbox
