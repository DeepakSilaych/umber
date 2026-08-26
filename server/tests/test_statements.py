import io


def login(client):
    r = client.post("/v1/auth/login", json={"password": "test-password"})
    assert r.status_code == 200


def upload(client, csv_text: str):
    return client.post(
        "/v1/statements/import",
        files={"file": ("statement.csv", io.BytesIO(csv_text.encode("utf-8")), "text/csv")},
    )


BASIC_CSV = (
    "Date,Narration,Debit,Credit,Balance,Ref No\n"
    "15-08-2026,UPI/ZOMATO ONLINE/payment,350.00,,50000.00,UPI987654321012\n"
)


def test_import_requires_auth(client):
    r = upload(client, BASIC_CSV)
    assert r.status_code == 401


def test_import_inserts_and_flags_needs_review(client):
    login(client)
    r = upload(client, BASIC_CSV)
    assert r.status_code == 200, r.text
    body = r.json()
    assert body == {
        "total_rows": 1,
        "inserted": 1,
        "skipped_duplicate": 0,
        "needs_review": 1,
        "problem": None,
    }


def test_reimport_same_file_is_a_clean_noop(client):
    login(client)
    upload(client, BASIC_CSV)
    second = upload(client, BASIC_CSV)
    assert second.json()["inserted"] == 0
    assert second.json()["skipped_duplicate"] == 1


def test_within_file_duplicate_reference_is_skipped_not_a_500(client):
    """Regression: found via a real SBI statement upload — two rows in the SAME file sharing a
    reference (a real, if rare, occurrence, not just a parsing bug) used to crash the entire
    import at commit time with a raw IntegrityError/500, rolling back every row in the batch,
    because the session only commits once at the end and the per-row duplicate check only ever
    saw previously *committed* rows, never a sibling row staged earlier in this same upload."""
    login(client)
    csv_text = (
        "Date,Narration,Debit,Credit,Balance,Ref No\n"
        "15-08-2026,UPI/AAAA/first,100.00,,50000.00,DUPREF12345\n"
        "15-08-2026,UPI/BBBB/second,200.00,,49800.00,DUPREF12345\n"
        "15-08-2026,UPI/CCCC/unrelated,300.00,,49500.00,UNIQREF99999\n"
    )
    r = upload(client, csv_text)
    assert r.status_code == 200, r.text
    body = r.json()
    assert body["total_rows"] == 3
    assert body["inserted"] == 2
    assert body["skipped_duplicate"] == 1

    listed = client.get("/v1/transactions").json()
    refs = {t["reference"] for t in listed["items"]}
    assert refs == {"DUPREF12345", "UNIQREF99999"}


def test_known_merchant_category_is_reused_without_review(client):
    login(client)
    upload(client, BASIC_CSV)
    client_id = client.get("/v1/transactions").json()["items"][0]["client_id"]
    client.patch(f"/v1/transactions/{client_id}", json={"category": "Food & Dining"})

    r = upload(
        client,
        "Date,Narration,Debit,Credit,Balance,Ref No\n"
        "16-08-2026,UPI/ZOMATO ONLINE/payment,275.00,,49725.00,UPI987654321099\n",
    )
    body = r.json()
    assert body["needs_review"] == 0

    listed = client.get("/v1/transactions", params={"merchant": "zomato"}).json()["items"]
    new_row = next(t for t in listed if t["reference"] == "UPI987654321099")
    assert new_row["category"] == "Food & Dining"
    assert new_row["category_source"] == "DASHBOARD"
    assert new_row["needs_review"] is False


def test_import_problem_reported_without_crashing(client):
    login(client)
    r = upload(client, "not,a,valid,statement\njust,some,junk,text\n")
    assert r.status_code == 200
    assert r.json()["problem"] is not None
    assert r.json()["inserted"] == 0
