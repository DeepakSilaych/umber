package com.deepak.umber.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.deepak.umber.data.db.TxnEntity
import com.deepak.umber.data.model.Categories
import com.deepak.umber.data.model.Direction
import com.deepak.umber.data.model.LocConfidence
import com.deepak.umber.data.model.Money
import com.deepak.umber.data.repo.WindowSummary
import com.deepak.umber.parse.Normalize
import com.deepak.umber.ui.theme.categoryColor
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

private val TIME_FORMAT: DateTimeFormatter =
    DateTimeFormatter.ofPattern("d MMM, h:mm a", Locale.ENGLISH)

fun formatWhen(millis: Long): String =
    Instant.ofEpochMilli(millis).atZone(ZoneId.systemDefault()).format(TIME_FORMAT)

@Composable
fun SummaryCard(summary: WindowSummary, emphasis: Boolean, modifier: Modifier = Modifier) {
    Card(
        modifier = modifier,
        colors = CardDefaults.cardColors(
            containerColor = if (emphasis) {
                MaterialTheme.colorScheme.primaryContainer
            } else {
                MaterialTheme.colorScheme.surfaceVariant
            },
        ),
    ) {
        Column(Modifier.padding(14.dp)) {
            Text(
                text = summary.window.label,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(6.dp))
            Text(
                text = Money.format(summary.spentPaise),
                style = if (emphasis) {
                    MaterialTheme.typography.headlineMedium
                } else {
                    MaterialTheme.typography.titleLarge
                },
                fontWeight = FontWeight.Bold,
            )
            Spacer(Modifier.height(2.dp))
            Text(
                text = buildString {
                    append("${summary.txnCount} transactions")
                    summary.topCategory?.let { append(" · top: ${it.category}") }
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/**
 * Bar chart of daily spend.
 *
 * Deliberately unlabelled — at this size the useful information is the shape (which days spiked),
 * not the values, and axis labels would crowd out the bars themselves.
 */
@Composable
fun DailyBars(daily: List<Long>, modifier: Modifier = Modifier, height: Int = 56) {
    val peak = (daily.maxOrNull() ?: 0L).coerceAtLeast(1L)
    val accent = MaterialTheme.colorScheme.primary
    val muted = MaterialTheme.colorScheme.surfaceVariant

    Row(
        modifier = modifier.height(height.dp),
        verticalAlignment = Alignment.Bottom,
        horizontalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        daily.forEach { value ->
            val ratio = value.toFloat() / peak.toFloat()
            val barHeight = (3f + ratio * (height - 3f)).toInt()
            Box(
                modifier = Modifier
                    .weight(1f)
                    .height(barHeight.dp)
                    .clip(RoundedCornerShape(2.dp))
                    .background(if (ratio > 0.6f) accent else muted),
            )
        }
    }
}

@Composable
fun CategoryDot(category: String, size: Int = 10) {
    Box(
        modifier = Modifier
            .size(size.dp)
            .clip(RoundedCornerShape(percent = 50))
            .background(categoryColor(category)),
    )
}

@Composable
fun TxnRow(
    txn: TxnEntity,
    onClick: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .then(if (onClick != null) Modifier.clickable { onClick() } else Modifier)
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        CategoryDot(txn.category)
        Spacer(Modifier.width(12.dp))

        Column(Modifier.weight(1f)) {
            Text(
                text = Normalize.display(txn.merchantRaw ?: txn.merchantNorm),
                style = MaterialTheme.typography.bodyLarge,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = buildString {
                    append(txn.category)
                    append(" · ")
                    append(formatWhen(txn.occurredAt))
                    // Only surface location when it's fresh enough to plausibly be where the
                    // money was actually spent. A stale fix is worse than none.
                    if (txn.locConfidence == LocConfidence.HIGH && txn.lat != null) {
                        append(" · 📍")
                    }
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }

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
}

/**
 * Category chooser as a dropdown.
 *
 * Replaces an inline grid of all fifteen categories, which took five rows per card and pushed
 * everything else off screen. A dropdown costs one extra tap but keeps a transaction to a single
 * line, which is what makes a long review queue or history list workable.
 */
@Composable
fun CategoryPicker(
    current: String,
    onPick: (String) -> Unit,
    modifier: Modifier = Modifier,
    compact: Boolean = false,
) {
    var expanded by remember { mutableStateOf(false) }

    Box(modifier) {
        OutlinedButton(
            onClick = { expanded = true },
            contentPadding = PaddingValues(horizontal = 12.dp, vertical = if (compact) 2.dp else 8.dp),
        ) {
            CategoryDot(current, size = 8)
            Spacer(Modifier.width(8.dp))
            Text(
                text = current,
                style = if (compact) MaterialTheme.typography.labelMedium else MaterialTheme.typography.bodyMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Icon(
                imageVector = Icons.Filled.ArrowDropDown,
                contentDescription = "Change category",
                modifier = Modifier.size(20.dp),
            )
        }

        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            Categories.ALL.forEach { category ->
                DropdownMenuItem(
                    text = {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            CategoryDot(category, size = 8)
                            Spacer(Modifier.width(10.dp))
                            Text(category)
                        }
                    },
                    onClick = {
                        expanded = false
                        onPick(category)
                    },
                    trailingIcon = {
                        if (category == current) {
                            Icon(Icons.Filled.Check, contentDescription = "Selected", modifier = Modifier.size(18.dp))
                        }
                    },
                )
            }
        }
    }
}

/** Where a transaction came from, in words the user recognises. */
fun sourceLabel(source: String?): String = when (source) {
    "SMS" -> "SMS"
    "NOTIFICATION" -> "Notification"
    "STATEMENT" -> "Statement"
    "LEDGER_CSV" -> "CSV"
    "MANUAL" -> "Manual"
    else -> "Imported"
}

@Composable
fun SourceBadge(source: String?, modifier: Modifier = Modifier) {
    Text(
        text = sourceLabel(source),
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = modifier
            .clip(RoundedCornerShape(4.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .padding(horizontal = 6.dp, vertical = 2.dp),
    )
}

@Composable
fun SectionHeader(text: String, modifier: Modifier = Modifier) {
    Text(
        text = text,
        style = MaterialTheme.typography.titleSmall,
        fontWeight = FontWeight.SemiBold,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = modifier.padding(horizontal = 16.dp, vertical = 8.dp),
    )
}
