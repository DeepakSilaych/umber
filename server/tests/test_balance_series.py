import io


def login(client):
    r = client.post("/v1/auth/login", json={"password": "test-password"})
    assert r.status_code == 200


def upload(client, csv_text: str, account_id: str | None = None):
    data = {"account_id": account_id} if account_id else {}
    return client.post(
        "/v1/statements/import",
        files={"file": ("statement.csv", io.BytesIO(csv_text.encode("utf-8")), "text/csv")},
        data=data,
    )


# Two transactions on the same day (both noon) with different running balances — the day's closing
# balance must be the one that came LAST in the file (highest import_seq), not an arbitrary pick.
SAME_DAY_CSV = (
    "Date,Narration,Debit,Credit,Balance,Ref No\n"
    "15-08-2026,UPI/AAA/first spend of day,100.00,,49900.00,REF0001\n"
    "15-08-2026,UPI/BBB/second spend of day,50.00,,49850.00,REF0002\n"
    "16-08-2026,UPI/CCC/next day,25.00,,49825.00,REF0003\n"
)


def test_import_tags_account_and_balance_series_uses_closing_balance(client):
    login(client)
    account = client.post("/v1/accounts", json={"label": "SBI", "kind": "BANK", "account_tail": "1205"}).json()

    r = upload(client, SAME_DAY_CSV, account_id=account["id"])
    assert r.status_code == 200, r.text
    assert r.json()["inserted"] == 3

    # Every imported row got the account.
    listed = client.get("/v1/transactions", params={"account_id": account["id"]}).json()
    assert listed["total"] == 3

    series = client.get(f"/v1/accounts/{account['id']}/balance-series")
    assert series.status_code == 200, series.text
    points = series.json()["points"]
    assert len(points) == 2  # two distinct days
    # Day 1 closing balance = the SECOND row's balance (49850), not the first (49900).
    assert points[0]["balance_paise"] == 4985000
    assert points[1]["balance_paise"] == 4982500
    # Ascending by day.
    assert points[0]["day_ms"] < points[1]["day_ms"]


def test_balance_series_ignores_unrelated_account(client):
    login(client)
    sbi = client.post("/v1/accounts", json={"label": "SBI", "kind": "BANK"}).json()
    idfc = client.post("/v1/accounts", json={"label": "IDFC", "kind": "BANK"}).json()
    upload(client, SAME_DAY_CSV, account_id=sbi["id"])

    assert client.get(f"/v1/accounts/{idfc['id']}/balance-series").json()["points"] == []


def test_balance_series_404_for_unknown_account(client):
    login(client)
    assert client.get("/v1/accounts/nope/balance-series").status_code == 404


def test_import_rejects_unknown_account_id(client):
    login(client)
    r = upload(client, SAME_DAY_CSV, account_id="does-not-exist")
    assert r.status_code == 404


def test_import_seq_monotonic_across_two_imports(client):
    """A second account's import must continue the sequence, so each account's own days still
    order correctly and the two never interleave their seq ranges within a shared day."""
    login(client)
    a = client.post("/v1/accounts", json={"label": "A", "kind": "BANK"}).json()
    b = client.post("/v1/accounts", json={"label": "B", "kind": "BANK"}).json()
    upload(client, SAME_DAY_CSV, account_id=a["id"])
    second = (
        "Date,Narration,Debit,Credit,Balance,Ref No\n"
        "15-08-2026,UPI/DDD/b account first,10.00,,9990.00,REF1001\n"
        "15-08-2026,UPI/EEE/b account second,10.00,,9980.00,REF1002\n"
    )
    upload(client, second, account_id=b["id"])

    pts = client.get(f"/v1/accounts/{b['id']}/balance-series").json()["points"]
    assert len(pts) == 1
    # b's own closing balance = its LAST row (9980.00), not its first (9990.00) and not a's.
    assert pts[0]["balance_paise"] == 998000
