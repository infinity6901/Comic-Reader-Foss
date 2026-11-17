package com.infinity.comicreader.ui

import android.content.Context
import android.content.Intent
import android.text.InputType
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import com.infinity.comicreader.R
import com.infinity.comicreader.data.Library

/**
 * Shared Reading Statistics dialog used by both [ListActivity] and [SettingsFragment]
 * so daily reading goals, 7-day trend, sharing, and resetting are consistent everywhere.
 */
object StatsDialog {
    fun show(context: Context, library: Library) {
        val st = library.stats()
        val goalLines = ArrayList<String>()
        if (st.dailyPageGoal > 0) {
            val percent = ((st.todayPages * 100) / st.dailyPageGoal).coerceAtMost(100).toInt()
            goalLines += context.getString(R.string.daily_goal_pages_progress, st.todayPages.toInt(), st.dailyPageGoal, percent)
        }
        if (st.dailyMinuteGoal > 0) {
            val minutes = (st.todaySeconds / 60).toInt()
            val percent = ((minutes.toLong() * 100) / st.dailyMinuteGoal).coerceAtMost(100).toInt()
            goalLines += context.getString(R.string.daily_goal_minutes_progress, minutes, st.dailyMinuteGoal, percent)
        }
        val goalsText = goalLines.takeIf { it.isNotEmpty() }?.joinToString("\n")
            ?: context.getString(R.string.daily_goal_disabled)
        val weeklyLines = library.dailyActivity(7).map { day ->
            val minutes = day.seconds / 60
            context.getString(R.string.weekly_activity_line, day.date, day.pages, minutes)
        }
        val weeklyText = context.getString(R.string.weekly_activity_title) + "\n" + weeklyLines.joinToString("\n")
        val message = context.getString(
            R.string.stats_body, st.chapters, st.pages, st.seconds / 3600,
            (st.seconds % 3600) / 60, st.daysRead, st.streak, st.bestStreak
        ) + "\n\n" + goalsText + "\n\n" + weeklyText

        AlertDialog.Builder(context)
            .setTitle(R.string.menu_stats)
            .setMessage(message)
            .setPositiveButton(R.string.daily_goals_title) { _, _ ->
                configureDailyGoals(context, library, st.dailyPageGoal, st.dailyMinuteGoal)
            }
            .setNeutralButton(R.string.reset_stats) { _, _ ->
                library.clearStats()
                Toast.makeText(context, R.string.stats_reset, Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton(R.string.ok, null)
            .show()
    }

    fun configureDailyGoals(context: Context, library: Library, currentPages: Int, currentMinutes: Int) {
        val container = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(48, 12, 48, 0)
        }
        val pages = EditText(context).apply {
            hint = context.getString(R.string.daily_goal_pages_hint)
            inputType = InputType.TYPE_CLASS_NUMBER
            setText(if (currentPages == 0) "" else currentPages.toString())
        }
        val minutes = EditText(context).apply {
            hint = context.getString(R.string.daily_goal_minutes_hint)
            inputType = InputType.TYPE_CLASS_NUMBER
            setText(if (currentMinutes == 0) "" else currentMinutes.toString())
        }
        container.addView(pages, LinearLayout.LayoutParams(-1, -2))
        container.addView(minutes, LinearLayout.LayoutParams(-1, -2))
        AlertDialog.Builder(context)
            .setTitle(R.string.daily_goals_title)
            .setView(container)
            .setPositiveButton(R.string.ok) { _, _ ->
                val p = pages.text.toString().toIntOrNull()?.coerceIn(0, 5000) ?: 0
                val m = minutes.text.toString().toIntOrNull()?.coerceIn(0, 1440) ?: 0
                library.setDailyGoals(p, m)
                Toast.makeText(context, R.string.goal_configured, Toast.LENGTH_SHORT).show()
                show(context, library)
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    fun shareReport(context: Context, library: Library) {
        val st = library.stats()
        val activity = library.dailyActivity(7)
        val report = buildString {
            appendLine(context.getString(R.string.app_name) + " · " + context.getString(R.string.menu_stats))
            appendLine(
                context.getString(
                    R.string.stats_body, st.chapters, st.pages, st.seconds / 3600,
                    (st.seconds % 3600) / 60, st.daysRead, st.streak, st.bestStreak
                )
            )
            appendLine()
            appendLine(context.getString(R.string.weekly_activity_title))
            activity.forEach { day ->
                appendLine(context.getString(R.string.weekly_activity_line, day.date, day.pages, day.seconds / 60))
            }
            val goalLines = mutableListOf<String>()
            if (st.dailyPageGoal > 0) {
                goalLines += context.getString(
                    R.string.daily_goal_pages_progress,
                    st.todayPages.toInt(),
                    st.dailyPageGoal,
                    ((st.todayPages * 100) / st.dailyPageGoal).coerceAtMost(100).toInt()
                )
            }
            if (st.dailyMinuteGoal > 0) {
                goalLines += context.getString(
                    R.string.daily_goal_minutes_progress,
                    (st.todaySeconds / 60).toInt(),
                    st.dailyMinuteGoal,
                    (((st.todaySeconds / 60) * 100) / st.dailyMinuteGoal).coerceAtMost(100).toInt()
                )
            }
            if (goalLines.isNotEmpty()) {
                appendLine()
                appendLine(context.getString(R.string.daily_goals_title))
                goalLines.forEach { appendLine(it) }
            }
        }
        val send = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_SUBJECT, context.getString(R.string.menu_stats))
            putExtra(Intent.EXTRA_TEXT, report)
        }
        context.startActivity(Intent.createChooser(send, context.getString(R.string.menu_share_stats)))
    }
}
