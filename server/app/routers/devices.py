import secrets
import time
import uuid

from fastapi import APIRouter, Depends, HTTPException, status
from sqlalchemy.orm import Session

from app.auth import hash_token
from app.config import Settings, get_settings
from app.db.base import get_db
from app.db.models import Device
from app.schemas import DeviceRegisterRequest, DeviceRegisterResponse

router = APIRouter(prefix="/v1/devices", tags=["devices"])


@router.post("/register", response_model=DeviceRegisterResponse)
def register_device(
    body: DeviceRegisterRequest,
    db: Session = Depends(get_db),
    settings: Settings = Depends(get_settings),
) -> DeviceRegisterResponse:
    if not secrets.compare_digest(body.setup_key, settings.setup_key):
        raise HTTPException(status_code=status.HTTP_401_UNAUTHORIZED, detail="Invalid setup key")

    device_id = str(uuid.uuid4())
    token = secrets.token_urlsafe(32)
    now = int(time.time() * 1000)

    db.add(
        Device(
            id=device_id,
            kind="phone",
            label=body.label,
            token_hash=hash_token(token),
            created_at=now,
            last_seen_at=None,
        )
    )
    db.commit()

    return DeviceRegisterResponse(device_id=device_id, token=token)
