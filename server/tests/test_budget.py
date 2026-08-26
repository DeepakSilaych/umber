import uuid


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
        "category_source": "USER",
        "updated_at": 1_753_600_000_000,
    }
    body.update(overrides)
    r = client.post("/v1/sync", headers=headers, json={"device_id": device_id, "since": 0, "transactions": [body]})
    assert r.status_code == 200, r.text


def test_budget_starts_empty(client):
    login(client)
    r = client.get("/v1/budget")
    assert r.status_code == 200
    assert r.json() == {"monthly_income_paise": 0, "buckets": []}


def test_set_income_and_create_buckets(client):
    login(client)
    inc = client.patch("/v1/budget", json={"monthly_income_paise": 16590700})
    assert inc.status_code == 200
    assert inc.json()["monthly_income_paise"] == 16590700

    b = client.post(
        "/v1/budget/buckets",
        json={
            "name": "Investments",
            "monthly_target_paise": 8000000,
            "category_keys": ["Investments"],
            "kind": "spend",
            "sort_order": 0,
        },
    )
    assert b.status_code == 201, b.text
    assert b.json()["category_keys"] == ["Investments"]

    got = client.get("/v1/budget").json()
    assert got["monthly_income_paise"] == 16590700
    assert len(got["buckets"]) == 1


def test_bucket_rejects_unknown_category(client):
    login(client)
    r = client.post("/v1/budget/buckets", json={"name": "X", "monthly_target_paise": 1, "category_keys": ["Crypto"]})
    assert r.status_code == 422


def test_bucket_crud(client):
    login(client)
    created = client.post("/v1/budget/buckets", json={"name": "Daily", "monthly_target_paise": 2500000, "category_keys": ["Food & Dining"]}).json()
    patched = client.patch(f"/v1/budget/buckets/{created['id']}", json={"monthly_target_paise": 3000000})
    assert patched.json()["monthly_target_paise"] == 3000000
    deleted = client.delete(f"/v1/budget/buckets/{created['id']}")
    assert deleted.status_code == 204
    assert client.get("/v1/budget").json()["buckets"] == []


def test_budget_requires_auth_for_writes(client):
    r = client.post("/v1/budget/buckets", json={"name": "X", "monthly_target_paise": 1, "category_keys": []})
    assert r.status_code == 401


def test_progress_bucket_actual_sums_category_spend(client):
    login(client)
    device = register_device(client)
    headers = {"Authorization": f"Bearer {device['token']}"}
    now = 1_753_600_000_000
    day = 24 * 60 * 60 * 1000

    push(client, headers, device["device_id"], client_id=str(uuid.uuid4()), amount_paise=5000, category="Food & Dining", occurred_at=now)
    push(client, headers, device["device_id"], client_id=str(uuid.uuid4()), amount_paise=3000, category="Groceries", occurred_at=now)
    push(client, headers, device["device_id"], client_id=str(uuid.uuid4()), amount_paise=80000, category="Investments", occurred_at=now)
    push(client, headers, device["device_id"], client_id=str(uuid.uuid4()), amount_paise=99999, category="Transfers", occurred_at=now)  # unbudgeted

    client.patch("/v1/budget", json={"monthly_income_paise": 200000})
    client.post("/v1/budget/buckets", json={"name": "Daily", "monthly_target_paise": 10000, "category_keys": ["Food & Dining", "Groceries"], "kind": "spend"})
    client.post("/v1/budget/buckets", json={"name": "Investments", "monthly_target_paise": 80000, "category_keys": ["Investments"], "kind": "spend"})
    client.post("/v1/budget/buckets", json={"name": "Buffer", "monthly_target_paise": 20000, "category_keys": [], "kind": "savings"})

    r = client.get("/v1/budget/progress", params={"from_ms": now - day, "to_ms": now + day})
    assert r.status_code == 200, r.text
    body = r.json()
    by_name = {b["name"]: b for b in body["buckets"]}
    assert by_name["Daily"]["actual_paise"] == 8000  # 5000 + 3000
    assert by_name["Investments"]["actual_paise"] == 80000
    # total outflow = 5000+3000+80000+99999 = 187999; savings = income - outflow
    assert body["total_spent_paise"] == 187999
    assert by_name["Buffer"]["actual_paise"] == 200000 - 187999
    # Transfers (99999) is in no bucket => unbudgeted
    assert body["unbudgeted_paise"] == 99999


def test_progress_savings_excludes_income_from_outflow(client):
    login(client)
    device = register_device(client)
    headers = {"Authorization": f"Bearer {device['token']}"}
    now = 1_753_600_000_000
    day = 24 * 60 * 60 * 1000

    push(client, headers, device["device_id"], client_id=str(uuid.uuid4()), amount_paise=10000, category="Food & Dining", occurred_at=now)
    # An Income credit must NOT count as outflow.
    push(client, headers, device["device_id"], client_id=str(uuid.uuid4()), amount_paise=500000, direction="CREDIT", category="Income", occurred_at=now, merchant_norm="employer")

    client.patch("/v1/budget", json={"monthly_income_paise": 100000})
    client.post("/v1/budget/buckets", json={"name": "Buffer", "monthly_target_paise": 50000, "category_keys": [], "kind": "savings"})

    body = client.get("/v1/budget/progress", params={"from_ms": now - day, "to_ms": now + day}).json()
    assert body["total_spent_paise"] == 10000  # income excluded
    assert body["buckets"][0]["actual_paise"] == 100000 - 10000
