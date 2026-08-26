import uuid


def register_device(client, label="test-phone"):
    r = client.post("/v1/devices/register", json={"setup_key": "test-setup-key", "label": label})
    assert r.status_code == 200, r.text
    return r.json()


def test_device_register_rejects_wrong_setup_key(client):
    r = client.post("/v1/devices/register", json={"setup_key": "wrong"})
    assert r.status_code == 401


def test_sync_push_then_pull_roundtrip(client):
    device = register_device(client)
    headers = {"Authorization": f"Bearer {device['token']}"}

    client_id = str(uuid.uuid4())
    push = client.post(
        "/v1/sync",
        headers=headers,
        json={
            "device_id": device["device_id"],
            "since": 0,
            "transactions": [
                {
                    "client_id": client_id,
                    "occurred_at": 1_753_600_000_000,
                    "amount_paise": 45000,
                    "direction": "DEBIT",
                    "channel": "UPI",
                    "merchant_norm": "swiggy",
                    "merchant_raw": "SWIGGY",
                    "account_tail": "1234",
                    "reference": "512345678901",
                    "balance_paise": 950000,
                    "category": "Food & Dining",
                    "category_source": "USER",
                    "updated_at": 1_753_600_100_000,
                }
            ],
        },
    )
    assert push.status_code == 200, push.text
    body = push.json()
    assert body["applied"] == 1
    assert body["rejected"] == []
    cursor = body["cursor"]

    # A second device pulling from since=0 should see the row the first device just pushed.
    other = register_device(client, label="second-device")
    pull = client.post(
        "/v1/sync",
        headers={"Authorization": f"Bearer {other['token']}"},
        json={"device_id": other["device_id"], "since": 0, "transactions": []},
    )
    assert pull.status_code == 200
    txns = pull.json()["transactions"]
    assert any(t["client_id"] == client_id and t["category"] == "Food & Dining" for t in txns)

    # Pulling again from the new cursor with nothing changed should come back empty.
    quiet = client.post(
        "/v1/sync",
        headers={"Authorization": f"Bearer {other['token']}"},
        json={"device_id": other["device_id"], "since": cursor, "transactions": []},
    )
    assert quiet.json()["transactions"] == []


def test_sync_duplicate_reference_rejected(client):
    device = register_device(client)
    headers = {"Authorization": f"Bearer {device['token']}"}
    common_ref = "UPI999999999999"

    first = client.post(
        "/v1/sync",
        headers=headers,
        json={
            "device_id": device["device_id"],
            "since": 0,
            "transactions": [
                {
                    "client_id": str(uuid.uuid4()),
                    "occurred_at": 1_753_600_000_000,
                    "amount_paise": 1000,
                    "direction": "DEBIT",
                    "category": "Other",
                    "category_source": "NONE",
                    "reference": common_ref,
                    "updated_at": 1_753_600_000_000,
                }
            ],
        },
    )
    assert first.json()["applied"] == 1

    second = client.post(
        "/v1/sync",
        headers=headers,
        json={
            "device_id": device["device_id"],
            "since": 0,
            "transactions": [
                {
                    "client_id": str(uuid.uuid4()),
                    "occurred_at": 1_753_600_050_000,
                    "amount_paise": 1000,
                    "direction": "DEBIT",
                    "category": "Other",
                    "category_source": "NONE",
                    "reference": common_ref,
                    "updated_at": 1_753_600_050_000,
                }
            ],
        },
    )
    body = second.json()
    assert body["applied"] == 0
    assert body["rejected"][0]["reason"] == "duplicate_reference"


def test_conflict_rule_user_edit_beats_model(client):
    """A user's own correction always wins — the phone re-pushing a MODEL-sourced category after
    the dashboard already set a USER-tier one must not clobber it."""
    device = register_device(client)
    headers = {"Authorization": f"Bearer {device['token']}"}
    client_id = str(uuid.uuid4())

    client.post(
        "/v1/sync",
        headers=headers,
        json={
            "device_id": device["device_id"],
            "since": 0,
            "transactions": [
                {
                    "client_id": client_id,
                    "occurred_at": 1_753_600_000_000,
                    "amount_paise": 2000,
                    "direction": "DEBIT",
                    "category": "Shopping",
                    "category_source": "MODEL",
                    "updated_at": 1_753_600_000_000,
                }
            ],
        },
    )

    # Dashboard/agent edit lands at DASHBOARD tier, which outranks MODEL.
    login = client.post("/v1/auth/login", json={"password": "test-password"})
    assert login.status_code == 200
    patch = client.patch(f"/v1/transactions/{client_id}", json={"category": "Groceries"})
    assert patch.status_code == 200
    assert patch.json()["category_source"] == "DASHBOARD"

    # An older MODEL-sourced re-push must not overwrite the DASHBOARD-tier edit.
    stale_push = client.post(
        "/v1/sync",
        headers=headers,
        json={
            "device_id": device["device_id"],
            "since": 0,
            "transactions": [
                {
                    "client_id": client_id,
                    "occurred_at": 1_753_600_000_000,
                    "amount_paise": 2000,
                    "direction": "DEBIT",
                    "category": "Entertainment",
                    "category_source": "MODEL",
                    "updated_at": 1_753_600_000_000,
                }
            ],
        },
    )
    assert stale_push.status_code == 200
    get_row = client.get(f"/v1/transactions/{client_id}", headers=headers)
    assert get_row.json()["category"] == "Groceries"
    assert get_row.json()["category_source"] == "DASHBOARD"


def test_transactions_list_filter_and_patch(client):
    device = register_device(client)
    headers = {"Authorization": f"Bearer {device['token']}"}
    client.post(
        "/v1/sync",
        headers=headers,
        json={
            "device_id": device["device_id"],
            "since": 0,
            "transactions": [
                {
                    "client_id": str(uuid.uuid4()),
                    "occurred_at": 1_753_600_000_000,
                    "amount_paise": 500,
                    "direction": "DEBIT",
                    "category": "Other",
                    "category_source": "NONE",
                    "updated_at": 1_753_600_000_000,
                },
                {
                    "client_id": str(uuid.uuid4()),
                    "occurred_at": 1_753_600_100_000,
                    "amount_paise": 700,
                    "direction": "DEBIT",
                    "category": "Food & Dining",
                    "category_source": "USER",
                    "updated_at": 1_753_600_100_000,
                },
            ],
        },
    )

    client.post("/v1/auth/login", json={"password": "test-password"})
    listed = client.get("/v1/transactions", params={"category": "Food & Dining"})
    assert listed.status_code == 200
    assert listed.json()["total"] == 1


def test_dashboard_actions_require_auth(client):
    r = client.post("/v1/transactions", json={"occurred_at": 0, "amount_paise": 100, "direction": "DEBIT", "category": "Cash"})
    assert r.status_code == 401


def test_stats_netting_offsets_reimbursement(client):
    device = register_device(client)
    headers = {"Authorization": f"Bearer {device['token']}"}
    now = 1_753_600_000_000
    day_ms = 24 * 60 * 60 * 1000

    client.post(
        "/v1/sync",
        headers=headers,
        json={
            "device_id": device["device_id"],
            "since": 0,
            "transactions": [
                {
                    "client_id": str(uuid.uuid4()),
                    "occurred_at": now,
                    "amount_paise": 100000,
                    "direction": "DEBIT",
                    "merchant_norm": "rahul",
                    "category": "Food & Dining",
                    "category_source": "USER",
                    "updated_at": now,
                },
                {
                    "client_id": str(uuid.uuid4()),
                    "occurred_at": now + 1000,
                    "amount_paise": 100000,
                    "direction": "CREDIT",
                    "merchant_norm": "rahul",
                    "category": "Other",
                    "category_source": "NONE",
                    "updated_at": now + 1000,
                },
            ],
        },
    )

    stats = client.get(
        "/v1/stats",
        headers=headers,
        params={"from_ms": now - day_ms, "to_ms": now + day_ms},
    )
    assert stats.status_code == 200, stats.text
    body = stats.json()
    assert body["gross_spend_paise"] == 100000
    assert body["reimbursed_paise"] == 100000
    assert body["net_spend_paise"] == 0
