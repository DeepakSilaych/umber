"""Investment holdings taxonomy. Server-only, like accounts.py — never crosses the phone sync wire."""

STOCK = "STOCK"
MUTUAL_FUND = "MUTUAL_FUND"
FD = "FD"
GOLD = "GOLD"
PPF = "PPF"
OTHER = "OTHER"

HOLDING_KINDS = (STOCK, MUTUAL_FUND, FD, GOLD, PPF, OTHER)

BUY = "BUY"
SELL = "SELL"
VALUATION_UPDATE = "VALUATION_UPDATE"

HOLDING_TXN_TYPES = (BUY, SELL, VALUATION_UPDATE)
