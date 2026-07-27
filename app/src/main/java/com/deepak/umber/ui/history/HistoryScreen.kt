package com.deepak.umber.ui.history

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.deepak.umber.data.db.TxnWithSource
import com.deepak.umber.data.model.Direction
import com.deepak.umber.data.model.LocConfidence
import com.deepak.umber.data.model.Money
import com.deepak.umber.parse.Normalize
import com.deepak.umber.ui.components.CategoryPicker
import com.deepak.umber.ui.components.SourceBadge
import com.deepak.umber.ui.components.formatWhen
import androidx.compose.ui.graphics.Color

/**
 * The full ledger: every transaction, where it came from, and a one-tap way to recategorise.
 *
 * Separate from Home because the two answer different questions — Home is "how am I doing", this is
 * "find that one payment". Mixing them meant the stats were always half a screen above a list you
 * had to scroll past.
 */
@Composable
fun HistoryScreen(
    items: List<TxnWithSource>,
    query: String,
    onQueryChange: (String) -> Unit,
    onCategoryChange: (TxnWithSource, String) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier.fillMaxSize()) {

        OutlinedTextField(
            value = query,
            onValueChange = onQueryChange,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp),
            placeholder = { Text("Search merchant or category") },
            leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
            trailingIcon = {
                if (query.isNotEmpty()) {
                    IconButton(onClick = { onQueryChange("") }) {
                        Icon(Icons.Filled.Clear, contentDescription = "Clear search")
                    }
                }
            },
            singleLine = true,
        )

        if (items.isEmpty()) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(
                    text = if (query.isBlank()) {
                        "No transactions yet. Import your SMS history from Settings."
                    } else {
                        "Nothing matches \"$query\""
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(32.dp),
                )
            }
            return
        }

        Text(
            text = "${items.size} transaction${if (items.size == 1) "" else "s"}",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
        )

        LazyColumn(Modifier.fillMaxSize()) {
            items(items, key = { it.txn.id }) { row ->
                HistoryRow(row) { category -> onCategoryChange(row, category) }
                HorizontalDivider(color = MaterialTheme.colorScheme.surfaceVariant)
            }
            item { Spacer(Modifier.height(24.dp)) }
        }
    }
}

@Composable
private fun HistoryRow(row: TxnWithSource, onCategoryChange: (String) -> Unit) {
    val txn = row.txn

    Column(Modifier.padding(horizontal = 16.dp, vertical = 10.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = Normalize.display(txn.merchantRaw ?: txn.merchantNorm),
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.Medium,
                modifier = Modifier.weight(1f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.width(8.dp))
            Text(
                text = (if (txn.direction == Direction.CREDIT) "+" else "−") + Money.format(txn.amountPaise),
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
                color = if (txn.direction == Direction.CREDIT) {
                    Color(0xFF2E7D32)
                } else {
                    MaterialTheme.colorScheme.onSurface
                },
            )
        }

        Spacer(Modifier.height(2.dp))

        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = buildString {
                    append(formatWhen(txn.occurredAt))
                    txn.accountTail?.let { append(" · ••$it") }
                    // Location is shown only when fresh enough to plausibly be where the money
                    // was actually spent.
                    if (txn.locConfidence == LocConfidence.HIGH && txn.lat != null) append(" · 📍")
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            SourceBadge(row.source)
        }

        Spacer(Modifier.height(6.dp))

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.Start,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            CategoryPicker(current = txn.category, onPick = onCategoryChange, compact = true)
        }
    }
}
