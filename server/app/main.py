import time
from contextlib import asynccontextmanager
from pathlib import Path

from fastapi import FastAPI, HTTPException
from fastapi.middleware.cors import CORSMiddleware
from fastapi.responses import FileResponse
from fastapi.staticfiles import StaticFiles

from app.config import get_settings
from app.db.base import SessionLocal
from app.db.models import Device
from app.routers import (
    accounts,
    auth_router,
    budget,
    classify,
    devices,
    holdings,
    insights,
    statements,
    stats,
    sync,
    transactions,
)

settings = get_settings()


def _seed_virtual_devices() -> None:
    """The dashboard and any AI agent write transactions attributed to fixed virtual device ids
    ('dashboard' / 'agent') for provenance, per docs/SYNC.md. `transactions.device_id` has a real
    FK to `devices`, so these rows must exist before the first dashboard edit or statement import.
    Neither is looked up by token_hash (dashboard auth is a JWT cookie, agent auth is a direct
    token compare against settings), so the placeholder hash here is never read."""
    now = int(time.time() * 1000)
    with SessionLocal() as db:
        for device_id, kind in (("dashboard", "dashboard"), ("agent", "agent")):
            if db.get(Device, device_id) is None:
                db.add(Device(id=device_id, kind=kind, label=None, token_hash="", created_at=now))
        db.commit()


@asynccontextmanager
async def _lifespan(_app: FastAPI):
    _seed_virtual_devices()
    yield


app = FastAPI(title="Umber", version="1.0.0", lifespan=_lifespan)

app.add_middleware(
    CORSMiddleware,
    allow_origins=settings.cors_origins,
    allow_credentials=True,
    allow_methods=["*"],
    allow_headers=["*"],
)

app.include_router(devices.router)
app.include_router(sync.router)
app.include_router(transactions.router)
app.include_router(stats.router)
app.include_router(statements.router)
app.include_router(auth_router.router)
app.include_router(accounts.router)
app.include_router(holdings.router)
app.include_router(insights.router)
app.include_router(classify.router)
app.include_router(budget.router)


@app.get("/healthz")
def healthz() -> dict:
    return {"ok": True}


# The built dashboard (Phase 2, `server/web/`) is copied to `server/web/dist` at Docker build
# time and served from the same process — one container, one Dokploy app, matching the single
# base URL docs/SYNC.md already assumes. In local dev without a build present, this is skipped
# so `uvicorn app.main:app` still works against the API alone (run the Vite dev server separately).
_dist = Path(__file__).resolve().parent.parent / "web" / "dist"
if _dist.is_dir():
    app.mount("/assets", StaticFiles(directory=_dist / "assets"), name="dashboard-assets")

    @app.get("/favicon.svg", include_in_schema=False)
    def _favicon() -> FileResponse:
        return FileResponse(_dist / "favicon.svg")

    # React Router routes (e.g. /login, /accounts) exist only client-side — a direct load or a
    # full-page redirect to one of them (see web/src/lib/api.ts's 401 handler) hits this server
    # first, so every non-API GET must fall back to index.html and let the SPA's own router take
    # over. Registered last: every real API route above still matches before this catch-all does.
    @app.get("/{full_path:path}", include_in_schema=False)
    def _spa_fallback(full_path: str) -> FileResponse:
        if full_path.startswith(("v1/", "docs", "openapi.json", "redoc", "healthz")):
            raise HTTPException(status_code=404)
        return FileResponse(_dist / "index.html")
