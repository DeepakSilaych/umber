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


# --- context patch ------------------------------------------------------------


def test_patch_context_and_clear(client):
    login(client)
    device = register_device(client)
    headers = {"Authorization": f"Bearer {device['token']}"}
    cid = push(client, headers, device["device_id"])

    r = client.patch(f"/v1/transactions/{cid}", json={"context": "mumbai_trip"})
    assert r.status_code == 200
    assert r.json()["context"] == "mumbai_trip"

    r2 = client.patch(f"/v1/transactions/{cid}", json={"context": ""})  # clears → daily-expense default
    assert r2.json()["context"] is None


def test_context_never_in_phone_sync_response(client):
    login(client)
    device = register_device(client)
    headers = {"Authorization": f"Bearer {device['token']}"}
    cid = push(client, headers, device["device_id"])
    client.patch(f"/v1/transactions/{cid}", json={"context": "goa_trip"})
    pull = client.post("/v1/sync", headers=headers, json={"device_id": device["device_id"], "since": 0, "transactions": []})
    for t in pull.json()["transactions"]:
        assert "context" not in t


def test_list_filters_by_context_and_subcategory(client):
    login(client)
    device = register_device(client)
    headers = {"Authorization": f"Bearer {device['token']}"}
    c1 = push(client, headers, device["device_id"], client_id=str(uuid.uuid4()))
    c2 = push(client, headers, device["device_id"], client_id=str(uuid.uuid4()))
    client.patch(f"/v1/transactions/{c1}", json={"context": "mumbai_trip", "subcategory": "Gadget"})
    client.patch(f"/v1/transactions/{c2}", json={})  # stays untagged (daily expense)

    trip = client.get("/v1/transactions", params={"context": "mumbai_trip"}).json()
    assert trip["total"] == 1 and trip["items"][0]["client_id"] == c1
    daily = client.get("/v1/transactions", params={"context": "daily expense"}).json()
    assert daily["total"] == 1 and daily["items"][0]["client_id"] == c2
    sub = client.get("/v1/transactions", params={"subcategory": "Gadget"}).json()
    assert sub["total"] == 1


def test_contexts_autocomplete_lists_distinct(client):
    login(client)
    device = register_device(client)
    headers = {"Authorization": f"Bearer {device['token']}"}
    c1 = push(client, headers, device["device_id"], client_id=str(uuid.uuid4()))
    client.patch(f"/v1/transactions/{c1}", json={"context": "mumbai_trip"})
    assert client.get("/v1/transactions/contexts").json() == ["mumbai_trip"]


# --- tag-context (date-range trip tagging) ------------------------------------


def test_tag_context_by_date_range_and_category_scope(client):
    login(client)
    device = register_device(client)
    headers = {"Authorization": f"Bearer {device['token']}"}
    now = 1_753_600_000_000
    day = 24 * 60 * 60 * 1000
    # Two travel-ish rows inside the window, one bill inside, one row outside the window.
    in1 = push(client, headers, device["device_id"], client_id=str(uuid.uuid4()), category="Travel", occurred_at=now)
    in2 = push(client, headers, device["device_id"], client_id=str(uuid.uuid4()), category="Food & Dining", occurred_at=now + 1000)
    bill = push(client, headers, device["device_id"], client_id=str(uuid.uuid4()), category="Bills & Utilities", occurred_at=now + 2000)
    outside = push(client, headers, device["device_id"], client_id=str(uuid.uuid4()), category="Travel", occurred_at=now + 5 * day)

    # Scope to travel-ish categories so the routine bill in the window isn't swept in.
    r = client.post("/v1/transactions/tag-context", json={
        "context": "mumbai_trip", "from_ms": now - day, "to_ms": now + day,
        "categories": ["Travel", "Food & Dining"],
    })
    assert r.status_code == 200
    assert r.json()["updated"] == 2

    def ctx(cid):
        return client.get(f"/v1/transactions/{cid}", headers=headers).json()["context"]

    assert ctx(in1) == "mumbai_trip"
    assert ctx(in2) == "mumbai_trip"
    assert ctx(bill) is None       # excluded by category scope
    assert ctx(outside) is None    # excluded by date range


def test_tag_context_only_untagged_guard(client):
    login(client)
    device = register_device(client)
    headers = {"Authorization": f"Bearer {device['token']}"}
    now = 1_753_600_000_000
    day = 24 * 60 * 60 * 1000
    cid = push(client, headers, device["device_id"], category="Travel", occurred_at=now)
    client.patch(f"/v1/transactions/{cid}", json={"context": "goa_trip"})  # already tagged

    # A later overlapping trip tag must NOT stomp the existing one (only_untagged default True).
    r = client.post("/v1/transactions/tag-context", json={"context": "mumbai_trip", "from_ms": now - day, "to_ms": now + day})
    assert r.json()["updated"] == 0
    assert client.get(f"/v1/transactions/{cid}", headers=headers).json()["context"] == "goa_trip"

    # With only_untagged False it overwrites.
    r2 = client.post("/v1/transactions/tag-context", json={"context": "mumbai_trip", "from_ms": now - day, "to_ms": now + day, "only_untagged": False})
    assert r2.json()["updated"] == 1
    assert client.get(f"/v1/transactions/{cid}", headers=headers).json()["context"] == "mumbai_trip"


def test_tag_context_requires_auth(client):
    r = client.post("/v1/transactions/tag-context", json={"context": "x", "from_ms": 0, "to_ms": 1})
    assert r.status_code == 401


# --- distribution (category × context) ----------------------------------------


def test_distribution_matrix_by_context(client):
    login(client)
    device = register_device(client)
    headers = {"Authorization": f"Bearer {device['token']}"}
    now = 1_753_600_000_000
    day = 24 * 60 * 60 * 1000
    a = push(client, headers, device["device_id"], client_id=str(uuid.uuid4()), amount_paise=5000, category="Food & Dining", occurred_at=now)
    b = push(client, headers, device["device_id"], client_id=str(uuid.uuid4()), amount_paise=90000, category="Food & Dining", occurred_at=now)
    push(client, headers, device["device_id"], client_id=str(uuid.uuid4()), amount_paise=2000, category="Shopping", occurred_at=now)  # stays daily
    client.patch(f"/v1/transactions/{b}", json={"context": "mumbai_trip"})

    d = client.get("/v1/stats/distribution", params={"from_ms": now - day, "to_ms": now + day}).json()
    assert set(d["contexts"]) == {"daily expense", "mumbai_trip"}
    assert d["contexts"][0] == "daily expense"  # daily always first
    food = next(r for r in d["rows"] if r["category"] == "Food & Dining")
    assert food["by_context"]["daily expense"] == 5000  # row a, untagged
    assert food["by_context"]["mumbai_trip"] == 90000   # row b, tagged
    assert d["totals_by_context"]["mumbai_trip"] == 90000
    assert d["totals_by_context"]["daily expense"] == 7000  # 5000 food + 2000 shopping


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


def test_classify_subcategory_survives_gateway_failure(client, monkeypatch):
    login(client)
    device = register_device(client)
    headers = {"Authorization": f"Bearer {device['token']}"}
    push(client, headers, device["device_id"], merchant_norm="uber", merchant_raw="UBER", category="Transport", category_source="REMOTE")

    def boom(settings, system_prompt, user_content, *, timeout=8.0):
        raise httpx.ConnectError("down")

    monkeypatch.setattr(classify_module, "chat_completion", boom)
    r = client.post("/v1/classify/subcategory")
    assert r.status_code == 200  # no 500
    assert r.json()["failed_batches"] == 1
    assert r.json()["assigned"] == 0


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
