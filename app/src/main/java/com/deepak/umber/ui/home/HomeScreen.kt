package com.deepak.umber.ui.home

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
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.deepak.umber.data.model.Money
import com.deepak.umber.data.repo.SpendWindow
import com.deepak.umber.ui.HomeUiState
import com.deepak.umber.ui.components.CategoryDot
import com.deepak.umber.ui.components.DailyBars
import com.deepak.umber.ui.components.SectionHeader
import com.deepak.umber.ui.components.SummaryCard
import com.deepak.umber.ui.theme.categoryColor

/**
 * Stats only.
 *
 * The transaction list moved to History: the two answer different questions, and having them on one
 * screen meant the numbers were permanently half a scroll above a list you had to get past.
 */
@Composable
fun HomeScreen(
    state: HomeUiState,
    onOpenReview: () -> Unit,
    modifier: Modifier = Modifier,
) {
    if (state.loading) {
        Box(modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            CircularProgressIndicator()
        }
        return
    }

    val byWindow = state.summaries.associateBy { it.window }
    val month = byWindow[SpendWindow.THIS_MONTH]

    // Days elapsed this month, so the daily average divides by days actually lived rather than a
    // flat 30 — on the 3rd, dividing by 30 understates spending tenfold.
    val dayOfMonth = remember { java.time.LocalDate.now().dayOfMonth }

    LazyColumn(modifier = modifier.fillMaxSize()) {

        item {
            byWindow[SpendWindow.LAST_24H]?.let {
                SummaryCard(
                    summary = it,
                    emphasis = true,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                )
            }
        }

        // Calendar periods in pairs, so each figure sits next to the one it invites comparison
        // with: this week against last week, this month against last.
        item {
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                byWindow[SpendWindow.THIS_WEEK]?.let {
                    SummaryCard(it, emphasis = false, modifier = Modifier.weight(1f))
                }
                byWindow[SpendWindow.LAST_WEEK]?.let {
                    SummaryCard(it, emphasis = false, modifier = Modifier.weight(1f))
                }
            }
        }

        item {
            Spacer(Modifier.height(8.dp))
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                month?.let {
                    SummaryCard(it, emphasis = false, modifier = Modifier.weight(1f))
                }
                byWindow[SpendWindow.LAST_MONTH]?.let {
                    SummaryCard(it, emphasis = false, modifier = Modifier.weight(1f))
                }
            }
        }

        if (state.reviewCount > 0) {
            item {
                Card(
                    modifier = Modifier.fillMaxWidth().padding(16.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.tertiaryContainer,
                    ),
                ) {
                    Column(Modifier.padding(14.dp)) {
                        Text(
                            text = "${state.reviewCount} to categorise",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold,
                        )
                        Spacer(Modifier.height(4.dp))
                        Text(
                            text = "Every one you confirm teaches the model. It gets quieter over time.",
                            style = MaterialTheme.typography.bodySmall,
                        )
                        Spacer(Modifier.height(8.dp))
                        AssistChip(onClick = onOpenReview, label = { Text("Review now") })
                    }
                }
            }
        }

        month?.let { m ->
            item {
                Card(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp)) {
                    Column(Modifier.padding(14.dp)) {
                        Text(
                            "This month",
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.SemiBold,
                        )
                        Spacer(Modifier.height(10.dp))

                        // Only show the gross/reimbursed pair when something actually netted off;
                        // otherwise the extra rows are noise that always read the same.
                        if (m.reimbursedPaise > 0) {
                            StatLine("Paid out", Money.format(m.grossSpentPaise))
                            StatLine("Paid back to you", "−" + Money.format(m.reimbursedPaise))
                            StatLine("Actually spent", Money.format(m.spentPaise), emphasise = true)
                        } else {
                            StatLine("Spent", Money.format(m.spentPaise), emphasise = true)
                        }

                        Spacer(Modifier.height(6.dp))
                        StatLine("Income", Money.format(m.incomePaise))
                        StatLine("Saved", Money.format(m.incomePaise - m.spentPaise))
                        StatLine("Average per day", Money.format(m.spentPaise / dayOfMonth.coerceAtLeast(1)))
                        StatLine("Transactions", m.txnCount.toString())

                        if (m.reimbursedPaise > 0) {
                            Spacer(Modifier.height(8.dp))
                            Text(
                                text = "Money returned by someone you paid is netted off rather than " +
                                    "counted as income. Salary is never netted.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }
        }

        if (state.accounts.isNotEmpty()) {
            item { SectionHeader("By account & card") }

            val biggestAccount = state.accounts.maxOfOrNull { it.totalPaise }?.coerceAtLeast(1L) ?: 1L

            items(state.accounts) { account ->
                Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(
                                text = accountLabel(account.accountTail),
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.Medium,
                            )
                            Text(
                                text = buildString {
                                    // Majority channel is the only signal available for telling a
                                    // card apart from a bank account — the tail alone can't.
                                    append(if (account.cardCount * 2 >= account.txnCount) "Card" else "Account")
                                    append(" · ${account.txnCount} txn")
                                },
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        Text(
                            text = Money.format(account.totalPaise),
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.SemiBold,
                        )
                    }
                    Spacer(Modifier.height(4.dp))
                    LinearProgressIndicator(
                        progress = { account.totalPaise.toFloat() / biggestAccount.toFloat() },
                        modifier = Modifier.fillMaxWidth().height(4.dp),
                        color = MaterialTheme.colorScheme.primary,
                        trackColor = MaterialTheme.colorScheme.surfaceVariant,
                    )
                }
            }
        }

        item { SectionHeader("Daily spend") }

        item {
            DailyBars(
                daily = state.daily,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                height = 72,
            )
        }

        if (state.topCategories.isNotEmpty()) {
            item { SectionHeader("Where it went") }

            val biggest = state.topCategories.maxOfOrNull { it.totalPaise }?.coerceAtLeast(1L) ?: 1L

            items(state.topCategories) { entry ->
                Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        CategoryDot(entry.category)
                        Spacer(Modifier.width(12.dp))
                        Text(
                            text = entry.category,
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.weight(1f),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Text(
                            text = Money.format(entry.totalPaise),
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.Medium,
                        )
                    }
                    Spacer(Modifier.height(4.dp))
                    // A bar per category makes relative weight readable at a glance, which a
                    // column of numbers does not.
                    LinearProgressIndicator(
                        progress = { entry.totalPaise.toFloat() / biggest.toFloat() },
                        modifier = Modifier.fillMaxWidth().height(4.dp),
                        color = categoryColor(entry.category),
                        trackColor = MaterialTheme.colorScheme.surfaceVariant,
                    )
                }
            }
        }

        if (state.totalCount == 0) {
            item {
                Text(
                    text = "No transactions yet. Import your SMS history or a bank statement from Settings.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(16.dp),
                )
            }
        }

        item { Spacer(Modifier.height(24.dp)) }
    }
}

/** Only the masked tail is ever known, so that is what gets shown. */
private fun accountLabel(tail: String?): String =
    if (tail.isNullOrBlank()) "Unidentified" else "••$tail"

@Composable
private fun StatLine(label: String, value: String, emphasise: Boolean = false) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 3.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f),
        )
        Text(
            text = value,
            style = if (emphasise) MaterialTheme.typography.titleSmall else MaterialTheme.typography.bodyMedium,
            fontWeight = if (emphasise) FontWeight.Bold else FontWeight.Medium,
        )
    }
}
