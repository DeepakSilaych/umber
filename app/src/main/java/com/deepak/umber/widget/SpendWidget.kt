package com.deepak.umber.widget

import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.ImageProvider
import androidx.glance.LocalSize
import androidx.glance.action.clickable
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.SizeMode
import androidx.glance.appwidget.action.actionStartActivity
import androidx.glance.appwidget.provideContent
import androidx.glance.background
import androidx.glance.layout.Alignment
import androidx.glance.layout.Box
import androidx.glance.layout.Column
import androidx.glance.layout.ColumnScope
import androidx.glance.layout.Row
import androidx.glance.layout.Spacer
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.fillMaxWidth
import androidx.glance.layout.height
import androidx.glance.layout.padding
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextStyle
import androidx.glance.unit.ColorProvider
import com.deepak.umber.MainActivity
import com.deepak.umber.R
import com.deepak.umber.UmberApp
import com.deepak.umber.data.model.Money
import com.deepak.umber.data.repo.SpendWindow
import com.deepak.umber.data.repo.WidgetSnapshot
import com.deepak.umber.data.repo.WindowSummary
import kotlin.math.abs

/**
 * Home-screen spend widget: rolling 24h / 7d / 30d totals.
 *
 * The whole point of the app is ambient awareness, so the numbers have to be readable without
 * opening anything. Windows are *rolling*, which means they go stale on their own — `RollupWorker`
 * exists purely to move the boundaries forward, and ingest pushes an update the moment a
 * transaction lands.
 */
class SpendWidget : GlanceAppWidget() {

    override val sizeMode = SizeMode.Responsive(setOf(SMALL, MEDIUM, LARGE))

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        // A throw here leaves a permanently broken widget on the home screen with no obvious way to
        // retry, so every failure degrades to a state that at least says something useful.
        val snapshot = runCatching {
            (context.applicationContext as UmberApp).container.repository.widgetSnapshot()
        }.onFailure { Log.e(TAG, "widget snapshot failed", it) }.getOrNull()

        provideContent {
            Column(
                modifier = GlanceModifier
                    .fillMaxSize()
                    .background(ImageProvider(R.drawable.widget_background))
                    .padding(12.dp)
                    .clickable(actionStartActivity(openTab(context, null))),
            ) {
                when {
                    snapshot == null -> Message("Couldn't load", "Tap to open Umber")
                    // "Nothing spent" and "nothing imported" look identical as ₹0, but mean very
                    // different things and need very different next steps.
                    snapshot.isEmpty -> Message("No transactions yet", "Tap to import your SMS")
                    else -> Body(context, snapshot)
                }
            }
        }
    }

    @Composable
    private fun Message(title: String, detail: String) {
        Box(modifier = GlanceModifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Column(horizontalAlignment = Alignment.Horizontal.CenterHorizontally) {
                Text(title, style = TextStyle(color = PRIMARY, fontSize = 14.sp, fontWeight = FontWeight.Medium))
                Spacer(GlanceModifier.height(4.dp))
                Text(detail, style = TextStyle(color = MUTED, fontSize = 11.sp))
            }
        }
    }

    @Composable
    private fun ColumnScope.Body(context: Context, snapshot: WidgetSnapshot) {
        val size = LocalSize.current
        val compact = size.width < MEDIUM.width
        val tall = size.height >= TALL_THRESHOLD

        if (compact) {
            // Only room for one figure: the 24h number, which is the one that can still change
            // today's behaviour.
            Amount("Last 24h", snapshot.summaries[SpendWindow.LAST_24H], emphasis = true)
        } else {
            Row(modifier = GlanceModifier.fillMaxWidth()) {
                Amount("24h", snapshot.summaries[SpendWindow.LAST_24H], true, GlanceModifier.defaultWeight())
                Amount("7d", snapshot.summaries[SpendWindow.LAST_7D], false, GlanceModifier.defaultWeight())
                Amount("30d", snapshot.summaries[SpendWindow.LAST_30D], false, GlanceModifier.defaultWeight())
            }
        }

        snapshot.weekTrendPercent?.let { trend ->
            Spacer(GlanceModifier.height(6.dp))
            Text(
                // Direction of travel is what makes a number actionable. "▲ 23% vs last week"
                // means something; "₹4,200" alone does not.
                text = (if (trend >= 0) "▲ " else "▼ ") + "${abs(trend)}% vs last week",
                style = TextStyle(
                    color = if (trend >= 0) ACCENT else POSITIVE,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Medium,
                ),
            )
        }

        if (snapshot.reviewCount > 0) {
            Spacer(GlanceModifier.height(6.dp))
            Text(
                text = "${snapshot.reviewCount} to categorise →",
                style = TextStyle(color = MUTED, fontSize = 11.sp, fontWeight = FontWeight.Medium),
                modifier = GlanceModifier.clickable(actionStartActivity(openTab(context, TAB_REVIEW))),
            )
        }

        if (tall) {
            // Pushes the chart to the bottom edge so the figures sit at the top and the space
            // between them is filled, rather than everything bunching under the header.
            Spacer(GlanceModifier.defaultWeight())
            TopCategories(snapshot)
            Spacer(GlanceModifier.height(8.dp))
            Sparkline(bucketSeries(snapshot.daily))
        }
    }

    @Composable
    private fun Amount(
        label: String,
        summary: WindowSummary?,
        emphasis: Boolean,
        modifier: GlanceModifier = GlanceModifier,
    ) {
        Column(modifier = modifier) {
            Text(label, style = TextStyle(color = MUTED, fontSize = 11.sp, fontWeight = FontWeight.Medium))
            Spacer(GlanceModifier.height(2.dp))
            Text(
                text = Money.compact(summary?.spentPaise ?: 0L),
                style = TextStyle(
                    color = if (emphasis) ACCENT else PRIMARY,
                    fontSize = if (emphasis) 22.sp else 17.sp,
                    fontWeight = FontWeight.Bold,
                ),
            )
            Text("${summary?.txnCount ?: 0} txn", style = TextStyle(color = MUTED, fontSize = 10.sp))
        }
    }

    /**
     * A bar per 3-day bucket over the last 30 days, most recent on the right and highlighted.
     *
     * Bucketed rather than one bar per day because Glance renders through RemoteViews, where a
     * container with 30 children is both fragile and unreadable at widget width.
     */
    @Composable
    private fun Sparkline(buckets: List<Long>) {
        val peak = (buckets.maxOrNull() ?: 0L).coerceAtLeast(1L)

        Row(
            modifier = GlanceModifier.fillMaxWidth().height(SPARK_HEIGHT_DP.dp),
            verticalAlignment = Alignment.Vertical.Bottom,
        ) {
            buckets.forEachIndexed { index, value ->
                val ratio = value.toFloat() / peak.toFloat()
                // A 2dp floor keeps empty buckets visible as a baseline rather than vanishing,
                // which would leave the axis unreadable.
                val barHeight = (2f + ratio * (SPARK_HEIGHT_DP - 2f)).toInt().coerceAtLeast(2)
                val newest = index == buckets.lastIndex

                Box(modifier = GlanceModifier.defaultWeight().padding(horizontal = 1.dp)) {
                    Box(
                        modifier = GlanceModifier
                            .fillMaxWidth()
                            .height(barHeight.dp)
                            .background(if (newest) ACCENT else BAR),
                    ) {}
                }
            }
        }
    }

    @Composable
    private fun TopCategories(snapshot: WidgetSnapshot) {
        Column(modifier = GlanceModifier.fillMaxWidth()) {
            snapshot.topCategories.take(3).forEach { entry ->
                Row(modifier = GlanceModifier.fillMaxWidth()) {
                    Text(
                        text = entry.category,
                        style = TextStyle(color = MUTED, fontSize = 11.sp),
                        modifier = GlanceModifier.defaultWeight(),
                        maxLines = 1,
                    )
                    Text(
                        text = Money.compact(entry.totalPaise),
                        style = TextStyle(color = PRIMARY, fontSize = 11.sp, fontWeight = FontWeight.Medium),
                    )
                }
            }
        }
    }

    companion object {
        const val TAB_REVIEW = "REVIEW"
        const val EXTRA_TAB = "com.deepak.umber.EXTRA_TAB"

        private const val TAG = "SpendWidget"

        /**
         * Breakpoints sized against real launcher cells, not round numbers.
         *
         * The first cut used 250×200 for LARGE, which no 2×2 widget ever reaches — a 2×2 is around
         * 160×160dp. Every 2×2 therefore rendered the short layout inside a tall box and left two
         * thirds of it empty.
         */
        val SMALL = DpSize(100.dp, 70.dp)
        val MEDIUM = DpSize(250.dp, 70.dp)
        val LARGE = DpSize(140.dp, 140.dp)

        /** Above this height there is room for the sparkline and category breakdown. */
        private val TALL_THRESHOLD = 140.dp

        private const val SPARK_BUCKETS = 10
        private const val SPARK_HEIGHT_DP = 34

        /**
         * Resource-backed rather than literal colours: Glance 1.1 has no day/night ColorProvider,
         * so the light/dark pair lives in `values/` and `values-night/` and the platform resolves
         * it at render time.
         */
        private val PRIMARY = ColorProvider(R.color.widget_text)
        private val MUTED = ColorProvider(R.color.widget_muted)
        private val ACCENT = ColorProvider(R.color.widget_accent)
        private val POSITIVE = ColorProvider(R.color.widget_positive)
        private val BAR = ColorProvider(R.color.widget_bar)

        /**
         * A distinct action per target.
         *
         * Two PendingIntents differing only in their extras compare as equal, so the system reuses
         * the first — which would silently send every tap to whichever tab was registered first.
         */
        fun openTab(context: Context, tab: String?): Intent =
            Intent(context, MainActivity::class.java)
                .setAction("com.deepak.umber.OPEN_" + (tab ?: "HOME"))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                .apply { if (tab != null) putExtra(EXTRA_TAB, tab) }

        /** Collapses the daily series into [SPARK_BUCKETS] equal-width sums, oldest first. */
        fun bucketSeries(daily: List<Long>): List<Long> {
            if (daily.isEmpty()) return List(SPARK_BUCKETS) { 0L }
            val perBucket = (daily.size + SPARK_BUCKETS - 1) / SPARK_BUCKETS
            return daily.chunked(perBucket) { chunk -> chunk.sum() }
        }
    }
}
