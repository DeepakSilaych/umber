import json
import uuid

import httpx
import pytest

import app.routers.classify as classify_module


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
        "amount_paise": 1000,
        "direction": "DEBIT",
        "category": "Other",
        "category_source": "NONE",
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
    push(client, headers, device["device_id"], merchant_norm="ze pto.payu", client_id=str(uuid.uuid4()))
    push(client, headers, device["device_id"], merchant_norm="ze pto.payu", client_id=str(uuid.uuid4()))
    push(client, headers, device["device_id"], merchant_norm="bl inkit.pa", client_id=str(uuid.uuid4()))
    return client


def test_classify_requires_dashboard_or_agent(client):
    r = client.post("/v1/classify")
    assert r.status_code == 401


def test_classify_applies_category_to_all_matching_transactions(seeded, monkeypatch):
    def fake_chat_completion(settings, system_prompt, user_content, *, timeout=8.0):
        merchants = json.loads(user_content)
        return json.dumps({m: ("Groceries" if "pto" in m else "Shopping") for m in merchants})

    monkeypatch.setattr(classify_module, "chat_completion", fake_chat_completion)

    r = seeded.post("/v1/classify")
    assert r.status_code == 200, r.text
    body = r.json()
    assert body["candidates_found"] == 2  # two distinct merchant_norm values
    assert body["merchants_classified"] == 2
    assert body["transactions_updated"] == 3  # both "ze pto.payu" rows + one "bl inkit.pa" row

    listed = seeded.get("/v1/transactions").json()["items"]
    by_merchant = {t["merchant_norm"]: t for t in listed}
    assert by_merchant["ze pto.payu"]["category"] == "Groceries"
    assert by_merchant["ze pto.payu"]["category_source"] == "REMOTE"
    assert by_merchant["ze pto.payu"]["needs_review"] is False
    assert by_merchant["bl inkit.pa"]["category"] == "Shopping"


def test_classify_never_overwrites_a_user_edit(seeded, monkeypatch):
    txns = seeded.get("/v1/transactions").json()["items"]
    zepto_txn = next(t for t in txns if t["merchant_norm"] == "ze pto.payu")
    seeded.patch(f"/v1/transactions/{zepto_txn['client_id']}", json={"category": "Cash"})

    def fake_chat_completion(settings, system_prompt, user_content, *, timeout=8.0):
        merchants = json.loads(user_content)
        return json.dumps({m: "Groceries" for m in merchants})

    monkeypatch.setattr(classify_module, "chat_completion", fake_chat_completion)
    seeded.post("/v1/classify")

    row = seeded.get(f"/v1/transactions/{zepto_txn['client_id']}").json()
    assert row["category"] == "Cash"
    assert row["category_source"] == "DASHBOARD"


def test_classify_caches_and_skips_already_classified_merchants(seeded, monkeypatch):
    calls = {"n": 0}

    def fake_chat_completion(settings, system_prompt, user_content, *, timeout=8.0):
        calls["n"] += 1
        merchants = json.loads(user_content)
        return json.dumps({m: "Other" for m in merchants})

    monkeypatch.setattr(classify_module, "chat_completion", fake_chat_completion)

    first = seeded.post("/v1/classify").json()
    assert first["candidates_found"] == 2

    second = seeded.post("/v1/classify").json()
    assert second["candidates_found"] == 0
    assert calls["n"] == 1  # second call found nothing new to classify, never touched the gateway


def test_classify_invalid_category_falls_back_to_other(seeded, monkeypatch):
    def fake_chat_completion(settings, system_prompt, user_content, *, timeout=8.0):
        merchants = json.loads(user_content)
        return json.dumps({m: "Made Up Category" for m in merchants})

    monkeypatch.setattr(classify_module, "chat_completion", fake_chat_completion)
    seeded.post("/v1/classify")

    listed = seeded.get("/v1/transactions").json()["items"]
    for t in listed:
        assert t["category"] == "Other"


def test_classify_gateway_failure_does_not_crash(seeded, monkeypatch):
    def raise_error(settings, system_prompt, user_content, *, timeout=8.0):
        raise httpx.ConnectError("down")

    monkeypatch.setattr(classify_module, "chat_completion", raise_error)
    r = seeded.post("/v1/classify")
    assert r.status_code == 200
    body = r.json()
    assert body["failed_batches"] == 1
    assert body["merchants_classified"] == 0
