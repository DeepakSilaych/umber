import json
import uuid

import httpx

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
    return body["client_id"]


# --- spend_type patch ---------------------------------------------------------


def test_patch_spend_type_and_clear(client):
    login(client)
    device = register_device(client)
    headers = {"Authorization": f"Bearer {device['token']}"}
    cid = push(client, headers, device["device_id"])

    r = client.patch(f"/v1/transactions/{cid}", json={"spend_type": "special"})  # lowercase normalizes
    assert r.status_code == 200
    assert r.json()["spend_type"] == "SPECIAL"

    r2 = client.patch(f"/v1/transactions/{cid}", json={"spend_type": ""})  # clears
    assert r2.json()["spend_type"] is None


def test_patch_spend_type_rejects_bad_value(client):
    login(client)
    device = register_device(client)
    headers = {"Authorization": f"Bearer {device['token']}"}
    cid = push(client, headers, device["device_id"])
    r = client.patch(f"/v1/transactions/{cid}", json={"spend_type": "HUGE"})
    assert r.status_code == 422


def test_spend_type_never_in_phone_sync_response(client):
    login(client)
    device = register_device(client)
    headers = {"Authorization": f"Bearer {device['token']}"}
    cid = push(client, headers, device["device_id"])
    client.patch(f"/v1/transactions/{cid}", json={"spend_type": "NORMAL"})
    pull = client.post("/v1/sync", headers=headers, json={"device_id": device["device_id"], "since": 0, "transactions": []})
    for t in pull.json()["transactions"]:
        assert "spend_type" not in t


def test_list_filters_by_spend_type_and_subcategory(client):
    login(client)
    device = register_device(client)
    headers = {"Authorization": f"Bearer {device['token']}"}
    c1 = push(client, headers, device["device_id"], client_id=str(uuid.uuid4()))
    c2 = push(client, headers, device["device_id"], client_id=str(uuid.uuid4()))
    client.patch(f"/v1/transactions/{c1}", json={"spend_type": "SPECIAL", "subcategory": "Gadget"})
    client.patch(f"/v1/transactions/{c2}", json={"spend_type": "NORMAL"})

    special = client.get("/v1/transactions", params={"spend_type": "SPECIAL"}).json()
    assert special["total"] == 1 and special["items"][0]["client_id"] == c1
    sub = client.get("/v1/transactions", params={"subcategory": "Gadget"}).json()
    assert sub["total"] == 1


# --- distribution -------------------------------------------------------------


def test_distribution_splits_by_spend_type(client):
    login(client)
    device = register_device(client)
    headers = {"Authorization": f"Bearer {device['token']}"}
    now = 1_753_600_000_000
    day = 24 * 60 * 60 * 1000
    a = push(client, headers, device["device_id"], client_id=str(uuid.uuid4()), amount_paise=5000, category="Food & Dining", occurred_at=now)
    b = push(client, headers, device["device_id"], client_id=str(uuid.uuid4()), amount_paise=90000, category="Food & Dining", occurred_at=now)
    push(client, headers, device["device_id"], client_id=str(uuid.uuid4()), amount_paise=2000, category="Shopping", occurred_at=now)  # untyped
    client.patch(f"/v1/transactions/{a}", json={"spend_type": "NORMAL"})
    client.patch(f"/v1/transactions/{b}", json={"spend_type": "SPECIAL"})

    d = client.get("/v1/stats/distribution", params={"from_ms": now - day, "to_ms": now + day}).json()
    food = next(r for r in d["rows"] if r["category"] == "Food & Dining")
    assert food["normal_paise"] == 5000
    assert food["special_paise"] == 90000
    assert d["normal_total_paise"] == 5000
    assert d["special_total_paise"] == 90000
    assert d["untyped_total_paise"] == 2000  # the Shopping row


# --- classify subcategory (mock gateway) --------------------------------------


def test_classify_subcategory_caches_and_applies(client, monkeypatch):
    login(client)
    device = register_device(client)
    headers = {"Authorization": f"Bearer {device['token']}"}
    push(client, headers, device["device_id"], merchant_norm="zomato", merchant_raw="ZOMATO", category="Food & Dining", category_source="REMOTE")

    def fake(settings, system_prompt, user_content, *, timeout=8.0):
        merchants = [m["merchant"] for m in json.loads(user_content)]
        return json.dumps({m: "Food Delivery" for m in merchants})

    monkeypatch.setattr(classify_module, "chat_completion", fake)
    r = client.post("/v1/classify/subcategory").json()
    assert r["assigned"] == 1
    assert r["transactions_updated"] == 1

    listed = client.get("/v1/transactions", params={"merchant": "zomato"}).json()["items"]
    assert listed[0]["subcategory"] == "Food Delivery"

    # Re-run is a no-op (cached).
    assert client.post("/v1/classify/subcategory").json()["candidates_found"] == 0


def test_classify_spend_type_assigns_and_survives_gateway_failure(client, monkeypatch):
    login(client)
    device = register_device(client)
    headers = {"Authorization": f"Bearer {device['token']}"}
    push(client, headers, device["device_id"], amount_paise=500, category="Food & Dining")

    def fake(settings, system_prompt, user_content, *, timeout=8.0):
        ids = [x["id"] for x in json.loads(user_content)]
        return json.dumps({i: "NORMAL" for i in ids})

    monkeypatch.setattr(classify_module, "chat_completion", fake)
    r = client.post("/v1/classify/spend-type").json()
    assert r["assigned"] == 1

    # Gateway down on a fresh row → no 500, reported as failed batch.
    push(client, headers, device["device_id"], client_id=str(uuid.uuid4()), amount_paise=700, category="Shopping")

    def boom(settings, system_prompt, user_content, *, timeout=8.0):
        raise httpx.ConnectError("down")

    monkeypatch.setattr(classify_module, "chat_completion", boom)
    r2 = client.post("/v1/classify/spend-type")
    assert r2.status_code == 200
    assert r2.json()["failed_batches"] == 1
    assert r2.json()["assigned"] == 0


# --- budget subcategory-keyword matching --------------------------------------


def test_budget_subcategory_keyword_splits_subscriptions_from_bills(client):
    login(client)
    device = register_device(client)
    headers = {"Authorization": f"Bearer {device['token']}"}
    now = 1_753_600_000_000
    day = 24 * 60 * 60 * 1000

    # Netflix: category Entertainment, subcategory Streaming -> should land in Subscriptions bucket
    netflix = push(client, headers, device["device_id"], client_id=str(uuid.uuid4()), amount_paise=50000, category="Entertainment", occurred_at=now, merchant_norm="netflix")
    # Electricity: category Bills & Utilities, no subscription subcategory -> stays in Bills bucket
    elec = push(client, headers, device["device_id"], client_id=str(uuid.uuid4()), amount_paise=200000, category="Bills & Utilities", occurred_at=now, merchant_norm="electricity")
    client.patch(f"/v1/transactions/{netflix}", json={"subcategory": "Streaming"})
    client.patch(f"/v1/transactions/{elec}", json={"subcategory": "Electricity"})

    client.patch("/v1/budget", json={"monthly_income_paise": 1000000})
    # Subscriptions bucket declared BEFORE Bills so it wins for the streaming row (sort_order).
    client.post("/v1/budget/buckets", json={"name": "Subscriptions", "monthly_target_paise": 100000, "category_keys": [], "subcategory_keywords": ["streaming", "mobile"], "kind": "spend", "sort_order": 0})
    client.post("/v1/budget/buckets", json={"name": "Bills", "monthly_target_paise": 300000, "category_keys": ["Bills & Utilities"], "kind": "spend", "sort_order": 1})

    prog = client.get("/v1/budget/progress", params={"from_ms": now - day, "to_ms": now + day}).json()
    by_name = {b["name"]: b for b in prog["buckets"]}
    assert by_name["Subscriptions"]["actual_paise"] == 50000  # netflix, by subcategory keyword
    assert by_name["Bills"]["actual_paise"] == 200000  # electricity only, NOT netflix
    assert prog["unbudgeted_paise"] == 0
