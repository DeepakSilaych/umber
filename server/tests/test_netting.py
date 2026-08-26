from app.netting import MerchantCredit, MerchantDebit, apply


def test_simple_full_reimbursement():
    debits = [MerchantDebit(merchant_norm="rahul", category="Food & Dining", paise=1000)]
    credits = [MerchantCredit(merchant_norm="rahul", paise=1000)]
    result = apply(debits, credits)
    assert result.gross_paise == 1000
    assert result.reimbursed_paise == 1000
    assert result.net_paise == 0
    assert result.net_by_category == {"Food & Dining": 0}


def test_credit_never_exceeds_debit_for_merchant():
    """A category can be driven to zero but never negative."""
    debits = [MerchantDebit(merchant_norm="rahul", category="Food & Dining", paise=500)]
    credits = [MerchantCredit(merchant_norm="rahul", paise=2000)]
    result = apply(debits, credits)
    assert result.reimbursed_paise == 500
    assert result.net_paise == 0
    assert result.net_by_category["Food & Dining"] == 0


def test_credit_from_unpaid_counterparty_is_not_reimbursement():
    """Money from someone you never paid is income, not a refund."""
    debits = [MerchantDebit(merchant_norm="swiggy", category="Food & Dining", paise=1000)]
    credits = [MerchantCredit(merchant_norm="unrelated_person", paise=1000)]
    result = apply(debits, credits)
    assert result.reimbursed_paise == 0
    assert result.net_paise == 1000
    assert result.net_by_category == {"Food & Dining": 1000}


def test_offset_distributes_proportionally_across_categories():
    debits = [
        MerchantDebit(merchant_norm="rahul", category="Food & Dining", paise=600),
        MerchantDebit(merchant_norm="rahul", category="Travel", paise=400),
    ]
    credits = [MerchantCredit(merchant_norm="rahul", paise=500)]
    result = apply(debits, credits)
    assert result.reimbursed_paise == 500
    assert result.net_paise == 500
    # 60/40 split of the 500 offset: 300 off Food, 200 off Travel.
    assert result.net_by_category == {"Food & Dining": 300, "Travel": 200}


def test_no_merchant_debit_is_never_netted():
    debits = [MerchantDebit(merchant_norm=None, category="Cash", paise=1000)]
    credits = [MerchantCredit(merchant_norm="someone", paise=1000)]
    result = apply(debits, credits)
    assert result.reimbursed_paise == 0
    assert result.net_by_category == {"Cash": 1000}
