import hashlib
import time
from dataclasses import dataclass

import jwt
from fastapi import Depends, HTTPException, Request, status
from sqlalchemy import select
from sqlalchemy.orm import Session

from app.config import Settings, get_settings
from app.db.base import get_db
from app.db.models import Device


def hash_token(token: str) -> str:
    return hashlib.sha256(token.encode("utf-8")).hexdigest()


@dataclass
class Actor:
    """Whoever is making the request — a phone, the dashboard, or an AI agent.

    `device_id` is always set: real device id for phones, the fixed virtual ids 'dashboard' /
    'agent' otherwise. Every write is attributed to one of these for provenance, per SYNC.md.
    """

    device_id: str
    kind: str  # 'phone' | 'dashboard' | 'agent'


def _bearer_token(request: Request) -> str | None:
    header = request.headers.get("authorization")
    if not header or not header.lower().startswith("bearer "):
        return None
    return header[len("bearer ") :].strip()


def get_actor(
    request: Request,
    db: Session = Depends(get_db),
    settings: Settings = Depends(get_settings),
) -> Actor:
    """Resolves any of the three token types to an Actor. Raises 401 if none match."""
    token = _bearer_token(request)

    if token and settings.agent_api_token and token == settings.agent_api_token:
        return Actor(device_id="agent", kind="agent")

    if token:
        device = db.execute(
            select(Device).where(Device.token_hash == hash_token(token), Device.kind == "phone")
        ).scalar_one_or_none()
        if device is not None:
            device.last_seen_at = int(time.time() * 1000)
            db.commit()
            return Actor(device_id=device.id, kind="phone")

    cookie = request.cookies.get(settings.jwt_cookie_name)
    if cookie:
        try:
            jwt.decode(cookie, settings.jwt_secret, algorithms=["HS256"])
            return Actor(device_id="dashboard", kind="dashboard")
        except jwt.PyJWTError:
            pass

    raise HTTPException(status_code=status.HTTP_401_UNAUTHORIZED, detail="Not authenticated")


def require_phone(actor: Actor = Depends(get_actor)) -> Actor:
    if actor.kind != "phone":
        raise HTTPException(status_code=status.HTTP_403_FORBIDDEN, detail="Phone device token required")
    return actor


def require_dashboard_or_agent(actor: Actor = Depends(get_actor)) -> Actor:
    if actor.kind not in ("dashboard", "agent"):
        raise HTTPException(status_code=status.HTTP_403_FORBIDDEN, detail="Dashboard session or agent token required")
    return actor
