package com.deepak.umber.ui.review

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
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.deepak.umber.data.db.TxnEntity
import com.deepak.umber.data.model.CategorySource
import com.deepak.umber.data.model.Money
import com.deepak.umber.parse.Normalize
import com.deepak.umber.ui.components.CategoryPicker
import com.deepak.umber.ui.components.formatWhen

/**
 * The training loop, as a screen.
 *
 * Everything the classifier was unsure about lands here. Confirming writes merchant memory,
 * retro-labels that merchant's history and takes a gradient step, so the queue shrinks faster than
 * the user works through it.
 *
 * The guess is pre-selected in the dropdown and "Looks right" accepts it, so the common case — the
 * model was correct — is one tap. Only a wrong guess costs the extra tap of opening the menu.
 */
@Composable
fun ReviewScreen(
    items: List<TxnEntity>,
    onConfirm: (TxnEntity, String) -> Unit,
    modifier: Modifier = Modifier,
) {
    if (items.isEmpty()) {
        Box(modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text("All caught up", style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.height(4.dp))
                Text(
                    text = "New transactions appear here when the model isn't sure.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        return
    }

    LazyColumn(modifier = modifier.fillMaxSize()) {
        item {
            Text(
                text = "${items.size} to categorise · each one you confirm teaches the model",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            )
        }

        items(items, key = { it.id }) { txn ->
            ReviewCard(txn) { category -> onConfirm(txn, category) }
        }

        item { Spacer(Modifier.height(24.dp)) }
    }
}

@Composable
private fun ReviewCard(txn: TxnEntity, onConfirm: (String) -> Unit) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 5.dp),
    ) {
        Column(Modifier.padding(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = Normalize.display(txn.merchantRaw ?: txn.merchantNorm),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.weight(1f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = Money.formatExact(txn.amountPaise),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                )
            }

            Text(
                text = buildString {
                    append(formatWhen(txn.occurredAt))
                    txn.accountTail?.let { append(" · ••$it") }
                    append(" · ${txn.channel.name.lowercase()}")
                    append(" · ${sourceLabel(txn.categorySource)}")
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2,
            )

            Spacer(Modifier.height(10.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                CategoryPicker(
                    current = txn.category,
                    // Picking from the menu is itself a confirmation — requiring a second tap on
                    // "Looks right" afterwards would be pure friction.
                    onPick = onConfirm,
                    modifier = Modifier.weight(1f),
                )
                Button(onClick = { onConfirm(txn.category) }) { Text("Looks right") }
            }
        }
    }
}

/**
 * Says where the pre-selected guess came from, so the user knows how much to trust it before
 * tapping "Looks right".
 */
private fun sourceLabel(source: CategorySource): String = when (source) {
    CategorySource.USER -> "you set this"
    CategorySource.MEMORY -> "from memory"
    CategorySource.MODEL -> "model's guess"
    CategorySource.SEED -> "built-in list"
    CategorySource.NONE -> "no guess"
}
