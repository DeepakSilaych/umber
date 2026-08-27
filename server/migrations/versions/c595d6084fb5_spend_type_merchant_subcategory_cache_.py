"""spend_type, merchant subcategory cache, bucket subcategory_keywords

Revision ID: c595d6084fb5
Revises: e8eb74ba17db
Create Date: 2026-08-27 23:45:19.885765

"""
from typing import Sequence, Union

from alembic import op
import sqlalchemy as sa


# revision identifiers, used by Alembic.
revision: str = 'c595d6084fb5'
down_revision: Union[str, None] = 'e8eb74ba17db'
branch_labels: Union[str, Sequence[str], None] = None
depends_on: Union[str, Sequence[str], None] = None


def upgrade() -> None:
    # server_default so existing budget_buckets rows backfill to an empty list; the model itself
    # has no server_default (app always supplies the value), so this is migration-local.
    op.add_column(
        'budget_buckets',
        sa.Column('subcategory_keywords', sa.JSON(), nullable=False, server_default='[]'),
    )
    op.add_column('merchant_categories', sa.Column('subcategory', sa.Text(), nullable=True))
    op.add_column('transactions', sa.Column('spend_type', sa.Text(), nullable=True))
    op.create_index('ix_txn_spend_type', 'transactions', ['spend_type'], unique=False, postgresql_where=sa.text('spend_type IS NOT NULL'))
    op.create_check_constraint(
        'ck_txn_spend_type', 'transactions', "spend_type is null or spend_type in ('NORMAL','SPECIAL')"
    )


def downgrade() -> None:
    op.drop_constraint('ck_txn_spend_type', 'transactions', type_='check')
    op.drop_index('ix_txn_spend_type', table_name='transactions', postgresql_where=sa.text('spend_type IS NOT NULL'))
    op.drop_column('transactions', 'spend_type')
    op.drop_column('merchant_categories', 'subcategory')
    op.drop_column('budget_buckets', 'subcategory_keywords')
