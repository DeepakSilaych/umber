package com.deepak.umber.widget

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.ImageProvider
import androidx.glance.LocalSize
// The reified <Activity> overload lives in glance core; the appwidget package only offers the
// Intent / ComponentName forms.
import androidx.glance.action.actionStartActivity
import androidx.glance.action.clickable
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.SizeMode
import androidx.glance.appwidget.provideContent
import androidx.glance.background
import androidx.glance.layout.Alignment
import androidx.glance.layout.Box
import androidx.glance.layout.Column
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
import com.deepak.umber.UmberApp
import com.deepak.umber.R
import com.deepak.umber.MainActivity
import com.deepak.umber.data.db.CategoryTotal
import com.deepak.umber.data.model.Money
import com.deepak.umber.data.repo.SpendWindow
import com.deepak.umber.data.repo.WindowSummary

/**
 * Home-screen spend widget: rolling 24h / 7d / 30d totals.
 *
 * The whole point of this app is ambient awareness, so the numbers have to be visible without
 * opening anything. Windows are *rolling*, which means they go stale on their own — `RollupWorker`
 * exists purely to roll the boundaries forward, and ingest pushes an update the moment a
 * transaction lands.
 */
class SpendWidget : GlanceAppWidget() {

    override val sizeMode = SizeMode.Responsive(setOf(SMALL, MEDIUM, LARGE))

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val container = (context.applicationContext as UmberApp).container
        val repo = container.repository

        // Loaded before provideContent so the first frame is already correct — a widget that
        // renders empty and then fills in reads as broken.
        val summaries = repo.allSummaries().associateBy { it.window }
        val buckets = bucketSeries(repo.dailySeries(SPARK_DAYS))
        val top = repo.topCategories(SpendWindow.LAST_30D, limit = 3)

        provideContent { WidgetBody(summaries, buckets, top) }
    }

    @Composable
    private fun WidgetBody(
        summaries: Map<SpendWindow, WindowSummary>,
        buckets: List<Long>,
        top: List<CategoryTotal>,
    ) {
        val size = LocalSize.current
        val compact = size.width < MEDIUM.width
        val tall = size.height >= LARGE.height

        Column(
            modifier = GlanceModifier
                .fillMaxSize()
                .background(ImageProvider(com.deepak.umber.R.drawable.widget_background))
                .padding(12.dp)
                .clickable(actionStartActivity<MainActivity>()),
        ) {
            if (compact) {
                // Only room for one number: the 24h figure, which is the one that can still
                // change today's behaviour.
                AmountBlock(
                    label = "Last 24h",
                    summary = summaries[SpendWindow.LAST_24H],
                    emphasis = true,
                )
            } else {
                Row(modifier = GlanceModifier.fillMaxWidth()) {
                    AmountBlock("24h", summaries[SpendWindow.LAST_24H], emphasis = true, modifier = GlanceModifier.defaultWeight())
                    AmountBlock("7d", summaries[SpendWindow.LAST_7D], emphasis = false, modifier = GlanceModifier.defaultWeight())
                    AmountBlock("30d", summaries[SpendWindow.LAST_30D], emphasis = false, modifier = GlanceModifier.defaultWeight())
                }
            }

            if (tall) {
                Spacer(GlanceModifier.height(12.dp))
                Sparkline(buckets)
                Spacer(GlanceModifier.height(10.dp))
                TopCategories(top)
            }
        }
    }

    @Composable
    private fun AmountBlock(
        label: String,
        summary: WindowSummary?,
        emphasis: Boolean,
        modifier: GlanceModifier = GlanceModifier,
    ) {
        Column(modifier = modifier) {
            Text(
                text = label,
                style = TextStyle(color = MUTED, fontSize = 11.sp, fontWeight = FontWeight.Medium),
            )
            Spacer(GlanceModifier.height(2.dp))
            Text(
                text = Money.compact(summary?.spentPaise ?: 0L),
                style = TextStyle(
                    color = if (emphasis) ACCENT else PRIMARY,
                    fontSize = if (emphasis) 22.sp else 17.sp,
                    fontWeight = FontWeight.Bold,
                ),
            )
            Text(
                text = "${summary?.txnCount ?: 0} txn",
                style = TextStyle(color = MUTED, fontSize = 10.sp),
            )
        }
    }

    /**
     * A bar per 3-day bucket over the last 30 days.
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
            buckets.forEach { value ->
                val ratio = value.toFloat() / peak.toFloat()
                // A floor of 2dp keeps zero-spend buckets visible as a baseline rather than
                // vanishing, so the axis stays legible.
                val barHeight = (2f + ratio * (SPARK_HEIGHT_DP - 2f)).toInt().coerceAtLeast(2)

                Box(modifier = GlanceModifier.defaultWeight().padding(horizontal = 1.dp)) {
                    Box(
                        modifier = GlanceModifier
                            .fillMaxWidth()
                            .height(barHeight.dp)
                            .background(if (ratio > 0.66f) ACCENT else BAR),
                    ) {}
                }
            }
        }
    }

    @Composable
    private fun TopCategories(top: List<CategoryTotal>) {
        Column(modifier = GlanceModifier.fillMaxWidth()) {
            top.take(3).forEach { entry ->
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
        val SMALL = DpSize(120.dp, 90.dp)
        val MEDIUM = DpSize(250.dp, 90.dp)
        val LARGE = DpSize(250.dp, 190.dp)

        private const val SPARK_DAYS = 30
        private const val SPARK_BUCKETS = 10
        private const val SPARK_HEIGHT_DP = 34

        /**
         * Resource-backed rather than literal colours: Glance 1.1 offers no day/night
         * ColorProvider, so the light/dark pair lives in `values/` and `values-night/` and the
         * platform picks the right one at render time.
         */
        private val PRIMARY = ColorProvider(R.color.widget_text)
        private val MUTED = ColorProvider(R.color.widget_muted)
        private val ACCENT = ColorProvider(R.color.widget_accent)
        private val BAR = ColorProvider(R.color.widget_bar)

        /** Collapses the daily series into [SPARK_BUCKETS] equal-width sums, oldest first. */
        fun bucketSeries(daily: List<Long>): List<Long> {
            if (daily.isEmpty()) return List(SPARK_BUCKETS) { 0L }
            val perBucket = (daily.size + SPARK_BUCKETS - 1) / SPARK_BUCKETS
            return daily.chunked(perBucket) { chunk -> chunk.sum() }
        }
    }
}
