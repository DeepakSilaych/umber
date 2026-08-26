def login(client):
    r = client.post("/v1/auth/login", json={"password": "test-password"})
    assert r.status_code == 200


def create_holding(client, name="Reliance", kind="STOCK"):
    r = client.post("/v1/holdings", json={"name": name, "kind": kind})
    assert r.status_code == 201, r.text
    return r.json()


def test_holding_crud(client):
    login(client)
    h = create_holding(client)
    assert h["units"] is None
    assert h["current_value_paise"] is None

    patch = client.patch(f"/v1/holdings/{h['id']}", json={"notes": "long term hold"})
    assert patch.status_code == 200
    assert patch.json()["notes"] == "long term hold"

    listed = client.get("/v1/holdings").json()
    assert len(listed["items"]) == 1

    deleted = client.delete(f"/v1/holdings/{h['id']}")
    assert deleted.status_code == 204


def test_buy_weighted_average_cost(client):
    login(client)
    h = create_holding(client)

    buy1 = client.post(
        f"/v1/holdings/{h['id']}/transactions",
        json={"type": "BUY", "units": "10", "price_paise": "10000", "amount_paise": 100000, "occurred_at": 1_753_000_000_000},
    )
    assert buy1.status_code == 201, buy1.text

    buy2 = client.post(
        f"/v1/holdings/{h['id']}/transactions",
        json={"type": "BUY", "units": "10", "price_paise": "20000", "amount_paise": 200000, "occurred_at": 1_753_100_000_000},
    )
    assert buy2.status_code == 201

    row = client.get(f"/v1/holdings/{h['id']}").json()
    assert row["units"] == "20.000000"
    # (10*10000 + 10*20000) / 20 = 15000 per unit
    assert row["avg_cost_paise"] == "15000.0000"


def test_sell_leaves_remaining_avg_cost_unchanged_and_records_realized_gain(client):
    login(client)
    h = create_holding(client)
    client.post(
        f"/v1/holdings/{h['id']}/transactions",
        json={"type": "BUY", "units": "10", "price_paise": "10000", "amount_paise": 100000, "occurred_at": 1_753_000_000_000},
    )

    sell = client.post(
        f"/v1/holdings/{h['id']}/transactions",
        json={"type": "SELL", "units": "4", "price_paise": "15000", "amount_paise": 60000, "occurred_at": 1_753_200_000_000},
    )
    assert sell.status_code == 201
    sell_body = sell.json()
    # (15000 - 10000) * 4 = 20000 realized gain
    assert sell_body["realized_gain_paise"] == 20000

    row = client.get(f"/v1/holdings/{h['id']}").json()
    assert row["units"] == "6.000000"
    assert row["avg_cost_paise"] == "10000.0000"  # unchanged by the sale
    assert row["realized_gain_paise"] == 20000


def test_cannot_sell_more_units_than_held(client):
    login(client)
    h = create_holding(client)
    client.post(
        f"/v1/holdings/{h['id']}/transactions",
        json={"type": "BUY", "units": "5", "price_paise": "10000", "amount_paise": 50000, "occurred_at": 1_753_000_000_000},
    )
    over_sell = client.post(
        f"/v1/holdings/{h['id']}/transactions",
        json={"type": "SELL", "units": "10", "price_paise": "10000", "amount_paise": 100000, "occurred_at": 1_753_100_000_000},
    )
    assert over_sell.status_code == 422


def test_value_tracked_holding_fd(client):
    """FD-style holding: no units, BUY/SELL are pure cash flows, gain/loss is whole-holding only."""
    login(client)
    h = create_holding(client, name="SBI FD 2027", kind="FD")

    buy = client.post(
        f"/v1/holdings/{h['id']}/transactions",
        json={"type": "BUY", "price_paise": None, "amount_paise": 100000, "occurred_at": 1_753_000_000_000},
    )
    assert buy.status_code == 201

    valuation = client.post(
        f"/v1/holdings/{h['id']}/transactions",
        json={"type": "VALUATION_UPDATE", "amount_paise": 108000, "occurred_at": 1_753_500_000_000},
    )
    assert valuation.status_code == 201

    row = client.get(f"/v1/holdings/{h['id']}").json()
    assert row["units"] is None
    assert row["current_value_paise"] == 108000
    assert row["net_invested_paise"] == 100000
    assert row["unrealized_gain_paise"] == 8000


def test_value_tracked_rejects_units(client):
    login(client)
    h = create_holding(client, name="Gold", kind="GOLD")
    client.post(
        f"/v1/holdings/{h['id']}/transactions",
        json={"type": "BUY", "amount_paise": 50000, "occurred_at": 1_753_000_000_000},
    )
    with_units = client.post(
        f"/v1/holdings/{h['id']}/transactions",
        json={"type": "BUY", "units": "5", "price_paise": "1000", "amount_paise": 5000, "occurred_at": 1_753_100_000_000},
    )
    assert with_units.status_code == 422


def test_holdings_summary_aggregates_across_kinds(client):
    login(client)
    stock = create_holding(client, name="Infosys", kind="STOCK")
    client.post(
        f"/v1/holdings/{stock['id']}/transactions",
        json={"type": "BUY", "units": "10", "price_paise": "10000", "amount_paise": 100000, "occurred_at": 1_753_000_000_000},
    )
    client.post(
        f"/v1/holdings/{stock['id']}/transactions",
        json={"type": "VALUATION_UPDATE", "amount_paise": 120000, "occurred_at": 1_753_100_000_000},
    )

    fd = create_holding(client, name="FD", kind="FD")
    client.post(
        f"/v1/holdings/{fd['id']}/transactions",
        json={"type": "BUY", "amount_paise": 50000, "occurred_at": 1_753_000_000_000},
    )

    summary = client.get("/v1/holdings/summary")
    assert summary.status_code == 200
    body = summary.json()
    assert body["total_current_value_paise"] == 120000  # FD never valued, excluded from value total
    assert body["total_invested_paise"] == 150000
    assert body["total_unrealized_gain_paise"] == 20000  # only the stock has a current_value_paise
    assert body["by_kind"]["STOCK"]["count"] == 1
    assert body["by_kind"]["FD"]["invested_paise"] == 50000


def test_holdings_timeline_marks_estimated_points(client):
    login(client)
    h1 = create_holding(client, name="A", kind="STOCK")
    client.post(
        f"/v1/holdings/{h1['id']}/transactions",
        json={"type": "BUY", "units": "1", "price_paise": "10000", "amount_paise": 10000, "occurred_at": 1_753_000_000_000},
    )
    client.post(
        f"/v1/holdings/{h1['id']}/transactions",
        json={"type": "VALUATION_UPDATE", "amount_paise": 12000, "occurred_at": 1_753_100_000_000},
    )

    # h2 bought after h1's valuation point but never itself valued — at that earlier timestamp
    # h2 doesn't exist yet, so it shouldn't appear at all in that point's total.
    h2 = create_holding(client, name="B", kind="STOCK")
    client.post(
        f"/v1/holdings/{h2['id']}/transactions",
        json={"type": "BUY", "units": "1", "price_paise": "5000", "amount_paise": 5000, "occurred_at": 1_753_200_000_000},
    )
    client.post(
        f"/v1/holdings/{h2['id']}/transactions",
        json={"type": "VALUATION_UPDATE", "amount_paise": 6000, "occurred_at": 1_753_300_000_000},
    )

    timeline = client.get("/v1/holdings/timeline")
    assert timeline.status_code == 200
    points = timeline.json()["points"]
    assert len(points) == 2
    assert points[0]["total_value_paise"] == 12000  # only h1 existed at this point
    assert points[0]["is_estimated"] is False
    # At the second point, h1 has no valuation update at/after its first one — reuses last known
    # real valuation (12000), h2 has its own real valuation (6000) — both real, not estimated.
    assert points[1]["total_value_paise"] == 18000
    assert points[1]["is_estimated"] is False


def test_holdings_endpoints_require_auth(client):
    r = client.post("/v1/holdings", json={"name": "X", "kind": "STOCK"})
    assert r.status_code == 401
