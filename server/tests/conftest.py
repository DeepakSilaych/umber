import os
import sys
import types
from dataclasses import dataclass

os.environ.setdefault("UMBER_DATABASE_URL", "postgresql+psycopg://umber:umber@localhost:5432/umber_test")
os.environ.setdefault("UMBER_SETUP_KEY", "test-setup-key")
os.environ.setdefault("UMBER_DASHBOARD_PASSWORD", "test-password")
os.environ.setdefault("UMBER_JWT_SECRET", "test-jwt-secret")
os.environ.setdefault("UMBER_AGENT_API_TOKEN", "test-agent-token")
os.environ.setdefault("UMBER_COOKIE_SECURE", "false")

# The statement-parsing module is being built in parallel by another agent and may not exist yet
# on disk. It's irrelevant to these tests (they cover sync/transactions/stats/auth/devices), so
# stub it out just enough that `app.main` (which imports the statements router) can be imported.
# If the real module is already present, this is a no-op — the real one wins the import.
if "app.parsing.statement" not in sys.modules:
    try:
        import app.parsing.statement  # noqa: F401
    except ModuleNotFoundError:
        stub = types.ModuleType("app.parsing.statement")

        @dataclass
        class ParsedTxnRow:
            occurred_at_ms: int
            amount_paise: int
            direction: str
            channel: str
            merchant_raw: str | None
            merchant_norm: str | None
            vpa_handle: str | None
            reference: str | None
            balance_paise: int | None
            raw_line: str

        @dataclass
        class StatementParseResult:
            rows: list
            total_data_rows: int
            skipped: int
            problem: str | None

        def parse_statement(data: bytes) -> StatementParseResult:
            return StatementParseResult(rows=[], total_data_rows=0, skipped=0, problem="stub: real parser not built yet")

        stub.ParsedTxnRow = ParsedTxnRow
        stub.StatementParseResult = StatementParseResult
        stub.parse_statement = parse_statement
        sys.modules["app.parsing.statement"] = stub

import pytest  # noqa: E402
from fastapi.testclient import TestClient  # noqa: E402
from sqlalchemy import create_engine, text  # noqa: E402
from sqlalchemy.orm import sessionmaker  # noqa: E402

from app.config import get_settings  # noqa: E402
from app.db.base import Base  # noqa: E402


@pytest.fixture(autouse=True)
def clean_db():
    settings = get_settings()
    engine = create_engine(settings.database_url)
    Base.metadata.create_all(engine)
    yield
    with engine.begin() as conn:
        # Every table Base knows about, not a hand-maintained subset — a table added to
        # models.py and forgotten here silently accumulates cross-test garbage (bit us once:
        # holdings/accounts totals ballooned because this list wasn't updated when those tables
        # were added).
        table_names = ", ".join(t.name for t in Base.metadata.sorted_tables)
        conn.execute(text(f"TRUNCATE {table_names} RESTART IDENTITY CASCADE"))


@pytest.fixture
def client():
    from app.main import app

    with TestClient(app) as c:
        yield c
