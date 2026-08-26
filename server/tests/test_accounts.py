import uuid


def register_device(client, label="test-phone"):
    r = client.post("/v1/devices/register", json={"setup_key": "test-setup-key", "label": label})
    assert r.status_code == 200, r.text
    return r.json()


def login(client):
    r = client.post("/v1/auth/login", json={"password": "test-password"})
    assert r.status_code == 200


def push_txn(client, headers, device_id, **overrides):
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


def test_account_crud(client):
    login(client)
    create = client.post(
        "/v1/accounts",
        json={"label": "HDFC Salary", "bank_name": "HDFC", "kind": "BANK", "account_tail": "1234"},
    )
    assert create.status_code == 201, create.text
    account = create.json()
    assert account["balance_paise"] == 0

    patch = client.patch(f"/v1/accounts/{account['id']}", json={"label": "HDFC Primary"})
    assert patch.status_code == 200
    assert patch.json()["label"] == "HDFC Primary"

    listed = client.get("/v1/accounts")
    assert listed.status_code == 200
    assert len(listed.json()["items"]) == 1

    deleted = client.delete(f"/v1/accounts/{account['id']}")
    assert deleted.status_code == 204


def test_account_rejects_unknown_kind(client):
    login(client)
    r = client.post("/v1/accounts", json={"label": "Weird", "kind": "CRYPTO_WALLET"})
    assert r.status_code == 422


def test_auto_link_on_sync_push_exact_match(client):
    login(client)
    device = register_device(client)
    headers = {"Authorization": f"Bearer {device['token']}"}

    account = client.post("/v1/accounts", json={"label": "SBI Card", "kind": "CREDIT_CARD", "account_tail": "9988"}).json()

    client_id = push_txn(client, headers, device["device_id"], account_tail="9988")

    row = client.get(f"/v1/transactions/{client_id}", headers=headers).json()
    assert row["account_id"] == account["id"]


def test_auto_link_ambiguous_leaves_unlinked(client):
    """Two accounts sharing the same last-4 digits must never cause a guess."""
    login(client)
    device = register_device(client)
    headers = {"Authorization": f"Bearer {device['token']}"}

    client.post("/v1/accounts", json={"label": "Card A", "kind": "CREDIT_CARD", "account_tail": "5555"})
    client.post("/v1/accounts", json={"label": "Card B", "kind": "CREDIT_CARD", "account_tail": "5555"})

    client_id = push_txn(client, headers, device["device_id"], account_tail="5555")
    row = client.get(f"/v1/transactions/{client_id}", headers=headers).json()
    assert row["account_id"] is None


def test_relink_backfills_and_reports_counts(client):
    login(client)
    device = register_device(client)
    headers = {"Authorization": f"Bearer {device['token']}"}

    # Transaction synced before any account existed for its tail.
    push_txn(client, headers, device["device_id"], account_tail="4321")
    # And one with no account_tail at all — should be excluded entirely, not counted anywhere.
    push_txn(client, headers, device["device_id"], account_tail=None)

    account = client.post("/v1/accounts", json={"label": "Late Account", "kind": "BANK", "account_tail": "4321"}).json()

    relink = client.post("/v1/accounts/relink")
    assert relink.status_code == 200
    body = relink.json()
    assert body["scanned"] == 1  # only the account_tail-bearing, still-unlinked row
    assert body["linked"] == 1
    assert body["ambiguous"] == 0
    assert body["no_match"] == 0

    # Second relink call must be a safe no-op (idempotent).
    relink2 = client.post("/v1/accounts/relink").json()
    assert relink2["scanned"] == 0


def test_account_balance_reflects_opening_balance_and_transactions(client):
    login(client)
    device = register_device(client)
    headers = {"Authorization": f"Bearer {device['token']}"}

    account = client.post(
        "/v1/accounts",
        json={
            "label": "Wallet",
            "kind": "WALLET",
            "opening_balance_paise": 500000,
            "opening_balance_as_of": 1_753_500_000_000,
        },
    ).json()

    push_txn(
        client, headers, device["device_id"],
        account_tail=None, amount_paise=100000, direction="DEBIT", occurred_at=1_753_600_000_000,
        client_id=str(uuid.uuid4()), updated_at=1_753_600_000_000,
    )
    push_txn(
        client, headers, device["device_id"],
        account_tail=None, amount_paise=20000, direction="CREDIT", occurred_at=1_753_600_100_000,
        client_id=str(uuid.uuid4()), updated_at=1_753_600_100_000, category="Income",
    )

    # Neither auto-linked (no account_tail) — assign manually via PATCH to prove that path too.
    txns = client.get("/v1/transactions", headers=headers).json()["items"]
    for t in txns:
        client.patch(f"/v1/transactions/{t['client_id']}", json={"account_id": account["id"]})

    balance = client.get(f"/v1/accounts/{account['id']}/balance")
    assert balance.status_code == 200
    # 500000 opening - 100000 debit + 20000 credit = 420000
    assert balance.json()["balance_paise"] == 420000


def test_bulk_assign_and_unlink(client):
    login(client)
    device = register_device(client)
    headers = {"Authorization": f"Bearer {device['token']}"}
    account = client.post("/v1/accounts", json={"label": "Cash", "kind": "CASH"}).json()

    c1 = push_txn(client, headers, device["device_id"], client_id=str(uuid.uuid4()))
    c2 = push_txn(client, headers, device["device_id"], client_id=str(uuid.uuid4()))

    bulk = client.post(
        "/v1/transactions/bulk-assign-account", json={"client_ids": [c1, c2, "nonexistent"], "account_id": account["id"]}
    )
    assert bulk.status_code == 200
    body = bulk.json()
    assert body["updated"] == 2
    assert body["not_found"] == ["nonexistent"]

    row = client.get(f"/v1/transactions/{c1}", headers=headers).json()
    assert row["account_id"] == account["id"]

    unlinked = client.delete(f"/v1/transactions/{c1}/account")
    assert unlinked.status_code == 200
    assert unlinked.json()["account_id"] is None


def test_delete_account_unlinks_never_deletes_transactions(client):
    login(client)
    device = register_device(client)
    headers = {"Authorization": f"Bearer {device['token']}"}
    account = client.post("/v1/accounts", json={"label": "Temp", "kind": "BANK", "account_tail": "7777"}).json()
    client_id = push_txn(client, headers, device["device_id"], account_tail="7777")

    row = client.get(f"/v1/transactions/{client_id}", headers=headers).json()
    assert row["account_id"] == account["id"]

    client.delete(f"/v1/accounts/{account['id']}")

    row_after = client.get(f"/v1/transactions/{client_id}", headers=headers).json()
    assert row_after["account_id"] is None  # unlinked, not deleted
