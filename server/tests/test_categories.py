from app.categories import ALL, beats


def test_taxonomy_matches_android_order():
    """Order is significant on the Android side (it's the classifier's label encoding) — this
    pins the server's copy to stay byte-identical to Categories.kt."""
    assert ALL == [
        "Food & Dining",
        "Groceries",
        "Transport",
        "Shopping",
        "Bills & Utilities",
        "Entertainment",
        "Health",
        "Education",
        "Rent & Housing",
        "Investments",
        "Transfers",
        "Cash",
        "Travel",
        "Income",
        "Other",
    ]


def test_user_beats_model():
    assert beats("MODEL", 1000, "USER", 1) is False
    assert beats("USER", 1, "MODEL", 1000) is True


def test_memory_and_user_are_same_tier_last_write_wins():
    assert beats("MEMORY", 100, "USER", 50) is True
    assert beats("USER", 50, "MEMORY", 100) is False


def test_dashboard_beats_remote_and_model_but_not_user():
    assert beats("DASHBOARD", 1, "REMOTE", 999999) is True
    assert beats("DASHBOARD", 1, "MODEL", 999999) is True
    assert beats("DASHBOARD", 999999, "USER", 1) is False


def test_equal_tier_tie_break_is_last_write_wins():
    assert beats("SEED", 200, "NONE", 100) is True
    assert beats("SEED", 100, "NONE", 200) is False
