import secrets
import time

import jwt
from fastapi import APIRouter, Depends, HTTPException, Response, status

from app.config import Settings, get_settings
from app.schemas import LoginRequest

router = APIRouter(prefix="/v1/auth", tags=["auth"])


@router.post("/login")
def login(body: LoginRequest, response: Response, settings: Settings = Depends(get_settings)) -> dict:
    if not secrets.compare_digest(body.password, settings.dashboard_password):
        raise HTTPException(status_code=status.HTTP_401_UNAUTHORIZED, detail="Wrong password")

    now = int(time.time())
    token = jwt.encode(
        {"sub": "dashboard", "iat": now, "exp": now + settings.session_ttl_seconds},
        settings.jwt_secret,
        algorithm="HS256",
    )
    response.set_cookie(
        settings.jwt_cookie_name,
        token,
        httponly=True,
        secure=settings.cookie_secure,
        samesite="lax",
        max_age=settings.session_ttl_seconds,
    )
    return {"ok": True}


@router.post("/logout")
def logout(response: Response, settings: Settings = Depends(get_settings)) -> dict:
    response.delete_cookie(settings.jwt_cookie_name)
    return {"ok": True}
