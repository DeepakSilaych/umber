"""Port of Netting.kt (app/src/main/java/com/deepak/umber/data/repo/Netting.kt).

Paying a friend and being paid back isn't spend + income, it's net zero. Offsets credits against
that merchant's debits, per merchant, up to the smaller of the two — never negative, never more
than was actually paid to them.

Same contract as the Kotlin original: the caller has already excluded credits filed as Income and
scoped both lists to one settlement window. This module only does the aggregation math.
"""

from collections import defaultdict
from dataclasses import dataclass


@dataclass
class MerchantDebit:
    merchant_norm: str | None
    category: str
    paise: int


@dataclass
class MerchantCredit:
    merchant_norm: str
    paise: int


@dataclass
class NettingResult:
    gross_paise: int
    reimbursed_paise: int
    net_paise: int
    net_by_category: dict[str, int]


def apply(debits: list[MerchantDebit], credits: list[MerchantCredit]) -> NettingResult:
    gross = sum(d.paise for d in debits)

    by_merchant: dict[str, dict[str, int]] = defaultdict(lambda: defaultdict(int))
    no_merchant_by_category: dict[str, int] = defaultdict(int)
    for d in debits:
        if d.merchant_norm:
            by_merchant[d.merchant_norm][d.category] += d.paise
        else:
            no_merchant_by_category[d.category] += d.paise

    credit_by_merchant: dict[str, int] = defaultdict(int)
    for c in credits:
        credit_by_merchant[c.merchant_norm] += c.paise

    net_by_category: dict[str, int] = defaultdict(int)
    reimbursed = 0

    for merchant, categories in by_merchant.items():
        merchant_total = sum(categories.values())
        credit_available = credit_by_merchant.get(merchant, 0)
        offset = min(merchant_total, credit_available)

        if offset <= 0:
            for category, amount in categories.items():
                net_by_category[category] += amount
            continue

        reimbursed += offset
        items = list(categories.items())
        remaining_offset = offset
        for i, (category, amount) in enumerate(items):
            if i == len(items) - 1:
                category_offset = remaining_offset
            else:
                category_offset = round(offset * amount / merchant_total)
                remaining_offset -= category_offset
            net_by_category[category] += amount - category_offset

    for category, amount in no_merchant_by_category.items():
        net_by_category[category] += amount

    net = gross - reimbursed
    return NettingResult(
        gross_paise=gross,
        reimbursed_paise=reimbursed,
        net_paise=net,
        net_by_category=dict(net_by_category),
    )
