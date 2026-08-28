"""rename spend_type to context, free text

Revision ID: 121f9a0eac9e
Revises: c595d6084fb5
Create Date: 2026-08-28 12:21:13.387087

"""
from typing import Sequence, Union

from alembic import op
import sqlalchemy as sa


# revision identifiers, used by Alembic.
revision: str = '121f9a0eac9e'
down_revision: Union[str, None] = 'c595d6084fb5'
branch_labels: Union[str, Sequence[str], None] = None
depends_on: Union[str, Sequence[str], None] = None


def upgrade() -> None:
    # spend_type (NORMAL/SPECIAL binary) is replaced by a free-text `context` (trip/occasion).
    op.drop_constraint("ck_txn_spend_type", "transactions", type_="check")
    op.drop_index("ix_txn_spend_type", table_name="transactions", postgresql_where=sa.text("spend_type IS NOT NULL"))
    op.alter_column("transactions", "spend_type", new_column_name="context")
    op.create_index("ix_txn_context", "transactions", ["context"], postgresql_where=sa.text("context IS NOT NULL"))
    # Clear the seeded NORMAL/SPECIAL values — that binary is gone; NULL = the "daily expense"
    # default, and trips get explicit names from here on.
    op.execute("UPDATE transactions SET context = NULL")


def downgrade() -> None:
    op.execute("UPDATE transactions SET context = NULL")
    op.drop_index("ix_txn_context", table_name="transactions", postgresql_where=sa.text("context IS NOT NULL"))
    op.alter_column("transactions", "context", new_column_name="spend_type")
    op.create_index("ix_txn_spend_type", "transactions", ["spend_type"], postgresql_where=sa.text("spend_type IS NOT NULL"))
    op.create_check_constraint(
        "ck_txn_spend_type", "transactions", "spend_type is null or spend_type in ('NORMAL','SPECIAL')"
    )
