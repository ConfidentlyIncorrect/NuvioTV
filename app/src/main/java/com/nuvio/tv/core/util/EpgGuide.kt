package com.nuvio.tv.core.util

import com.nuvio.tv.domain.model.EpgEntry
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * usa-tv-next live EPG rendering — the SINGLE source of truth for turning the addon's
 * absolute-time guide window (`epgSchedule`, ISO-8601 UTC) into display text.
 *
 * Both the stream-selection panel ([buildNowNext]) and the channel detail screen ([buildDetail])
 * compute from this on a ticking clock, so the guide is (a) live/self-correcting regardless of
 * response caching, and (b) formatted IDENTICALLY no matter which EPG source (Schedules Direct /
 * epg.pw / epgshare) the channel matched. Uses SimpleDateFormat (no java.time) so it is safe on
 * every Android TV API level; times render in the device's local time zone.
 */
object EpgGuide {
    // The addon emits times as "yyyy-MM-ddTHH:mm:ss.SSSZ" (always UTC, with millis + literal Z).
    private val ISO = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US)
        .apply { timeZone = TimeZone.getTimeZone("UTC") }
    private val TIME = SimpleDateFormat("h:mm a", Locale.US) // formats in the device-local zone

    private data class Prog(val start: Long, val stop: Long?, val title: String, val desc: String?)

    private fun parse(iso: String?): Long? {
        if (iso.isNullOrBlank()) return null
        return try { synchronized(ISO) { ISO.parse(iso)?.time } } catch (e: Exception) { null }
    }

    private fun fmt(ms: Long): String = synchronized(TIME) { TIME.format(Date(ms)) }

    private fun normalize(schedule: List<EpgEntry>?): List<Prog> {
        if (schedule.isNullOrEmpty()) return emptyList()
        return schedule.mapNotNull { e ->
            val s = parse(e.s) ?: return@mapNotNull null
            Prog(s, parse(e.e), e.t?.trim().orEmpty(), e.d?.trim()?.takeIf { it.isNotEmpty() })
        }.sortedBy { it.start }
    }

    // Current programme = the one with the GREATEST start <= now, validated to actually cover now
    // (explicit stop -> next programme's start -> +6h cap), so a duration-less/stale entry can't
    // masquerade as "now".
    private fun nowProg(items: List<Prog>, nowMs: Long): Prog? {
        var idx = -1
        for (i in items.indices) { if (items[i].start <= nowMs) idx = i else break }
        if (idx < 0) return null
        val p = items[idx]
        val eff = p.stop ?: items.getOrNull(idx + 1)?.start ?: (p.start + 6 * 3600_000L)
        return if (eff > nowMs) p else null
    }

    private fun nextProg(items: List<Prog>, nowMs: Long): Prog? = items.firstOrNull { it.start > nowMs }

    /** Compact NOW/NEXT block for the stream-selection left panel. Null if nothing to show. */
    fun buildNowNext(schedule: List<EpgEntry>?, nowMs: Long): String? {
        val items = normalize(schedule)
        if (items.isEmpty()) return null
        val now = nowProg(items, nowMs)
        val next = nextProg(items, nowMs)
        val sb = StringBuilder()
        if (now != null) {
            val range = if (now.stop != null) "${fmt(now.start)} - ${fmt(now.stop)}" else fmt(now.start)
            sb.append("▶ NOW · ").append(range)
            if (now.title.isNotBlank()) sb.append('\n').append(now.title)
        }
        if (next != null) {
            if (sb.isNotEmpty()) sb.append("\n\n")
            sb.append("⏭ NEXT · ").append(fmt(next.start))
            if (next.title.isNotBlank()) sb.append('\n').append(next.title)
        }
        return sb.toString().ifEmpty { null }
    }

    /**
     * Full guide block for the channel detail screen: NOW PLAYING (+ synopsis) / UP NEXT
     * (+ synopsis) / today's remaining schedule. Same data, one consistent layout for every source.
     */
    fun buildDetail(schedule: List<EpgEntry>?, nowMs: Long, maxSchedule: Int = 10): String? {
        val items = normalize(schedule)
        if (items.isEmpty()) return null
        val now = nowProg(items, nowMs)
        val next = nextProg(items, nowMs)
        val lines = ArrayList<String>()
        if (now != null) {
            lines.add("▶ NOW PLAYING: ${now.title}")
            val range = if (now.stop != null) "${fmt(now.start)} - ${fmt(now.stop)}" else fmt(now.start)
            lines.add("  $range")
            now.desc?.let { lines.add("  ${clip(it, 160)}") }
            lines.add("")
        }
        if (next != null) {
            lines.add("⏭ UP NEXT: ${next.title} (${fmt(next.start)})")
            next.desc?.let { lines.add("  ${clip(it, 120)}") }
            lines.add("")
        }
        // Today's remaining schedule: programmes that start before local midnight and haven't ended.
        val endOfDay = endOfLocalDay(nowMs)
        val upcoming = items.filter { it.start <= endOfDay && (it.stop ?: Long.MAX_VALUE) > nowMs }
        if (upcoming.isNotEmpty()) {
            lines.add("📺 TODAY'S SCHEDULE:")
            lines.add("─".repeat(28))
            for (p in upcoming.take(maxSchedule)) {
                val marker = if (now != null && p.start == now.start) "▶ " else "  "
                lines.add("$marker${fmt(p.start)} ${p.title}")
            }
            if (upcoming.size > maxSchedule) lines.add("  … and ${upcoming.size - maxSchedule} more")
        }
        return lines.joinToString("\n").trim().ifEmpty { null }
    }

    private fun clip(s: String, max: Int): String = if (s.length > max) s.take(max - 3) + "..." else s

    private fun endOfLocalDay(nowMs: Long): Long {
        val cal = Calendar.getInstance()
        cal.timeInMillis = nowMs
        cal.set(Calendar.HOUR_OF_DAY, 23)
        cal.set(Calendar.MINUTE, 59)
        cal.set(Calendar.SECOND, 59)
        cal.set(Calendar.MILLISECOND, 999)
        return cal.timeInMillis
    }
}
