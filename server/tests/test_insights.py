import json
import uuid

import httpx
import pytest

import app.routers.insights as insights_module


def login(client):
    r = client.post("/v1/auth/login", json={"password": "test-password"})
    assert r.status_code == 200


def register_device(client):
    r = client.post("/v1/devices/register", json={"setup_key": "test-setup-key"})
    assert r.status_code == 200
    return r.json()


def push(client, headers, device_id, **overrides):
    body = {
        "client_id": str(uuid.uuid4()),
        "occurred_at": 1_753_600_000_000,
        "amount_paise": 45000,
        "direction": "DEBIT",
        "merchant_norm": "swiggy",
        "category": "Food & Dining",
        "category_source": "USER",
        "updated_at": 1_753_600_000_000,
    }
    body.update(overrides)
    r = client.post("/v1/sync", headers=headers, json={"device_id": device_id, "since": 0, "transactions": [body]})
    assert r.status_code == 200, r.text


@pytest.fixture
def seeded(client):
    login(client)
    device = register_device(client)
    headers = {"Authorization": f"Bearer {device['token']}"}
    push(client, headers, device["device_id"])
    return client


def test_insights_requires_dashboard_or_agent(client):
    r = client.post("/v1/insights/generate", params={"from_ms": 0, "to_ms": 9_999_999_999_999})
    assert r.status_code == 401


def test_insights_success_and_cache_hit(seeded, monkeypatch):
    calls = {"n": 0}

    def fake_chat_completion(settings, system_prompt, user_content, *, timeout=8.0):
        calls["n"] += 1
        return json.dumps({"summary": "You spent on Swiggy.", "suggestions": ["Cook more at home."]})

    monkeypatch.setattr(insights_module, "chat_completion", fake_chat_completion)

    r1 = seeded.post("/v1/insights/generate", params={"from_ms": 0, "to_ms": 9_999_999_999_999})
    assert r1.status_code == 200, r1.text
    body1 = r1.json()
    assert body1["llm_generated"] is True
    assert body1["cached"] is False
    assert body1["summary"] == "You spent on Swiggy."
    assert body1["suggestions"] == ["Cook more at home."]
    assert calls["n"] == 1

    r2 = seeded.post("/v1/insights/generate", params={"from_ms": 0, "to_ms": 9_999_999_999_999})
    assert r2.status_code == 200
    body2 = r2.json()
    assert body2["cached"] is True
    assert body2["summary"] == body1["summary"]
    assert calls["n"] == 1  # gateway not called again — served from insights_cache


def test_insights_force_refresh_bypasses_cache(seeded, monkeypatch):
    calls = {"n": 0}

    def fake_chat_completion(settings, system_prompt, user_content, *, timeout=8.0):
        calls["n"] += 1
        return json.dumps({"summary": f"call {calls['n']}", "suggestions": []})

    monkeypatch.setattr(insights_module, "chat_completion", fake_chat_completion)

    seeded.post("/v1/insights/generate", params={"from_ms": 0, "to_ms": 9_999_999_999_999})
    r2 = seeded.post("/v1/insights/generate", params={"from_ms": 0, "to_ms": 9_999_999_999_999, "force_refresh": True})
    assert r2.json()["summary"] == "call 2"
    assert calls["n"] == 2


def test_insights_gateway_failure_falls_back_without_500(seeded, monkeypatch):
    def raise_error(settings, system_prompt, user_content, *, timeout=8.0):
        raise httpx.ConnectError("gateway unreachable")

    monkeypatch.setattr(insights_module, "chat_completion", raise_error)

    r = seeded.post("/v1/insights/generate", params={"from_ms": 0, "to_ms": 9_999_999_999_999})
    assert r.status_code == 200
    body = r.json()
    assert body["llm_generated"] is False
    assert body["suggestions"] == []
    assert "Net spend" in body["summary"]


def test_insights_unparseable_response_falls_back(seeded, monkeypatch):
    def bad_response(settings, system_prompt, user_content, *, timeout=8.0):
        return "not json at all"

    monkeypatch.setattr(insights_module, "chat_completion", bad_response)

    r = seeded.post("/v1/insights/generate", params={"from_ms": 0, "to_ms": 9_999_999_999_999})
    assert r.status_code == 200
    assert r.json()["llm_generated"] is False


def test_insights_fallback_never_cached(seeded, monkeypatch):
    state = {"fail": True}

    def flaky(settings, system_prompt, user_content, *, timeout=8.0):
        if state["fail"]:
            raise httpx.ConnectError("down")
        return json.dumps({"summary": "recovered", "suggestions": ["ok"]})

    monkeypatch.setattr(insights_module, "chat_completion", flaky)

    r1 = seeded.post("/v1/insights/generate", params={"from_ms": 0, "to_ms": 9_999_999_999_999})
    assert r1.json()["llm_generated"] is False

    state["fail"] = False
    r2 = seeded.post("/v1/insights/generate", params={"from_ms": 0, "to_ms": 9_999_999_999_999})
    assert r2.json()["llm_generated"] is True
    assert r2.json()["summary"] == "recovered"
