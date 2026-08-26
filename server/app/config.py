from functools import lru_cache

from pydantic_settings import BaseSettings, SettingsConfigDict


class Settings(BaseSettings):
    model_config = SettingsConfigDict(env_file=".env", env_prefix="UMBER_")

    database_url: str = "postgresql+psycopg://umber:umber@localhost:5432/umber"

    # Guards POST /v1/devices/register — the phone is configured with this once, out of band.
    setup_key: str = "dev-setup-key-change-me"

    # Single-user dashboard login (this app has exactly one user).
    dashboard_password: str = "dev-password-change-me"
    jwt_secret: str = "dev-jwt-secret-change-me"
    jwt_cookie_name: str = "umber_session"
    session_ttl_seconds: int = 60 * 60 * 24 * 30  # 30 days
    # Off only for local http dev — production always runs behind Traefik/TLS.
    cookie_secure: bool = True

    # Static long-lived token for AI-agent access to /v1/transactions and /v1/statements.
    agent_api_token: str | None = None

    cors_origins: list[str] = ["http://localhost:5173"]

    # LLM gateway for /v1/insights/generate and /v1/classify — the self-hosted gateway docs/SYNC.md
    # is designed around. Key is server-only, never shipped to the phone APK. Model must be one the
    # gateway's client key actually has access to (verified: openai/gpt-oss-20b works; the older
    # llama-3.x ids in the infra notes no longer route for this key).
    llm_gateway_url: str = "https://llm.deepaksilaych.me/v1"
    llm_gateway_key: str | None = None
    llm_model: str = "openai/gpt-oss-20b"


@lru_cache
def get_settings() -> Settings:
    return Settings()
