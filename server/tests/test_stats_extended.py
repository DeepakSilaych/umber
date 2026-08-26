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
        "category_source": "NONE",
        "updated_at": 1_753_600_000_000,
    }
    body.update(overrides)
    r = client.post("/v1/sync", headers=headers, json={"device_id": device_id, "since": 0, "transactions": [body]})
    assert r.status_code == 200, r.text
    return body["client_id"]


# 2025-08-01 00:00 IST, 2025-08-02 00:00 IST, 2025-08-03 00:00 IST in epoch ms
DAY1 = 1_754_002_200_000
DAY2 = DAY1 + 24 * 60 * 60 * 1000
DAY3 = DAY1 + 2 * 24 * 60 * 60 * 1000


def test_timeline_buckets_by_day_and_group_by_category(client):
    login(client)
    device = register_device(client)
    headers = {"Authorization": f"Bearer {device['token']}"}

    push(client, headers, device["device_id"], occurred_at=DAY1, amount_paise=1000, category="Food & Dining")
    push(client, headers, device["device_id"], occurred_at=DAY1 + 1000, amount_paise=500, category="Transport")
    push(client, headers, device["device_id"], occurred_at=DAY3, amount_paise=2000, category="Food & Dining")

    r = client.get(
        "/v1/stats/timeline",
        headers=headers,
        params={"from_ms": DAY1, "to_ms": DAY3 + 1000, "granularity": "day", "group_by": "category"},
    )
    assert r.status_code == 200, r.text
    body = r.json()
    assert body["granularity"] == "day"
    assert len(body["buckets"]) == 3
    assert body["buckets"][0]["gross_spend_paise"] == 1500
    assert body["buckets"][0]["breakdown"]["Food & Dining"] == 1000
    assert body["buckets"][0]["breakdown"]["Transport"] == 500
    assert body["buckets"][1]["gross_spend_paise"] == 0
    assert body["buckets"][2]["gross_spend_paise"] == 2000


def test_timeline_rejects_invalid_granularity(client):
    login(client)
    r = client.get("/v1/stats/timeline", params={"from_ms": DAY1, "to_ms": DAY3, "granularity": "fortnight"})
    assert r.status_code == 422  # FastAPI's own pattern validation on the query param


def test_timeline_too_many_buckets_errors(client):
    login(client)
    # ~3000 days at daily granularity, far past MAX_BUCKETS=400
    r = client.get(
        "/v1/stats/timeline",
        params={"from_ms": DAY1, "to_ms": DAY1 + 3000 * 24 * 60 * 60 * 1000, "granularity": "day"},
    )
    assert r.status_code == 400


def test_by_merchant_top_n_and_other(client):
    login(client)
    device = register_device(client)
    headers = {"Authorization": f"Bearer {device['token']}"}

    push(client, headers, device["device_id"], merchant_norm="swiggy", amount_paise=5000)
    push(client, headers, device["device_id"], merchant_norm="zomato", amount_paise=3000)
    push(client, headers, device["device_id"], merchant_norm="bigbasket", amount_paise=1000)

    r = client.get("/v1/stats/by-merchant", headers=headers, params={"from_ms": 0, "to_ms": 9_999_999_999_999, "limit": 2})
    assert r.status_code == 200
    body = r.json()
    assert [it["merchant_norm"] for it in body["items"]] == ["swiggy", "zomato"]
    assert body["other_paise"] == 1000


def test_by_channel_groups_all_channels(client):
    login(client)
    device = register_device(client)
    headers = {"Authorization": f"Bearer {device['token']}"}
    push(client, headers, device["device_id"], channel="UPI", amount_paise=1000)
    push(client, headers, device["device_id"], channel="UPI", amount_paise=500)
    push(client, headers, device["device_id"], channel="CARD", amount_paise=2000)

    r = client.get("/v1/stats/by-channel", headers=headers, params={"from_ms": 0, "to_ms": 9_999_999_999_999})
    assert r.status_code == 200
    items = {it["channel"]: it["amount_paise"] for it in r.json()["items"]}
    assert items["UPI"] == 1500
    assert items["CARD"] == 2000


def test_by_time_pattern_day_of_week(client):
    login(client)
    device = register_device(client)
    headers = {"Authorization": f"Bearer {device['token']}"}
    # DAY1 is a known Friday per the epoch chosen; just assert bucketing is self-consistent
    push(client, headers, device["device_id"], occurred_at=DAY1, amount_paise=1000)

    r = client.get(
        "/v1/stats/by-time-pattern",
        headers=headers,
        params={"from_ms": 0, "to_ms": 9_999_999_999_999, "dimension": "day_of_week"},
    )
    assert r.status_code == 200
    body = r.json()
    assert len(body["buckets"]) == 7
    assert sum(b["amount_paise"] for b in body["buckets"]) == 1000


def test_subcategory_patch_and_breakdown(client):
    login(client)
    device = register_device(client)
    headers = {"Authorization": f"Bearer {device['token']}"}
    client_id = push(client, headers, device["device_id"], category="Food & Dining", amount_paise=450)

    patch = client.patch(f"/v1/transactions/{client_id}", json={"subcategory": "Coffee"})
    assert patch.status_code == 200
    assert patch.json()["subcategory"] == "Coffee"

    autocomplete = client.get("/v1/transactions/subcategories", params={"category": "Food & Dining"})
    assert autocomplete.status_code == 200
    assert "Coffee" in autocomplete.json()

    breakdown = client.get("/v1/stats/by-subcategory", params={"from_ms": 0, "to_ms": 9_999_999_999_999})
    assert breakdown.status_code == 200
    items = breakdown.json()["items"]
    assert any(it["category"] == "Food & Dining" and it["subcategory"] == "Coffee" and it["amount_paise"] == 450 for it in items)

    # Clearing via empty string must work — the one field with real "clear" semantics.
    clear = client.patch(f"/v1/transactions/{client_id}", json={"subcategory": ""})
    assert clear.status_code == 200
    assert clear.json()["subcategory"] is None


def test_subcategory_never_appears_on_phone_sync_response(client):
    """TxnOut (used by /v1/sync) must never leak subcategory to the phone."""
    login(client)
    device = register_device(client)
    headers = {"Authorization": f"Bearer {device['token']}"}
    client_id = push(client, headers, device["device_id"])
    client.patch(f"/v1/transactions/{client_id}", json={"subcategory": "Secret Tag"})

    pull = client.post(
        "/v1/sync", headers=headers, json={"device_id": device["device_id"], "since": 0, "transactions": []}
    )
    assert pull.status_code == 200
    for txn in pull.json()["transactions"]:
        assert "subcategory" not in txn


def test_stats_account_filter(client):
    login(client)
    device = register_device(client)
    headers = {"Authorization": f"Bearer {device['token']}"}
    account = client.post("/v1/accounts", json={"label": "A", "kind": "BANK"}).json()

    c1 = push(client, headers, device["device_id"], amount_paise=1000)
    push(client, headers, device["device_id"], amount_paise=5000)  # unlinked, must be excluded

    client.patch(f"/v1/transactions/{c1}", json={"account_id": account["id"]})

    r = client.get("/v1/stats", params={"from_ms": 0, "to_ms": 9_999_999_999_999, "account_id": account["id"]})
    assert r.status_code == 200
    assert r.json()["gross_spend_paise"] == 1000
    assert r.json()["account_id"] == account["id"]
