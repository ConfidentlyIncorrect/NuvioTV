package com.nuvio.tv.core.tvdb

import android.util.Log
import com.nuvio.tv.data.remote.api.TvdbWebApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

/** A single TheTVDB episode, scraped from the keyless "all seasons (official)" page. */
data class TvdbEpisode(
    val season: Int,
    val episode: Int,
    val title: String?,
    val airDateIso: String?,   // "yyyy-MM-dd"
    val thumbnail: String?,
    val tvdbEpisodeId: String?
)

/** Lightweight identity (name + primary poster + year) from the series page alone — used by tiles. */
data class TvdbArt(
    val name: String?,
    val poster: String?,
    val year: Int?
)

/** Authoritative TheTVDB series metadata for a numeric series id. */
data class TvdbSeries(
    val tvdbId: Int,
    val name: String?,
    val year: Int?,
    val poster: String?,       // current primary poster (portrait)
    val background: String?,   // current hero background (16:9)
    val country: String?,      // "Original Country", e.g. "United States of America"
    val language: String?,     // "Original Language", e.g. "English"
    val status: String?,       // e.g. "Continuing" / "Ended"
    val genres: List<String>,
    val runtimeMinutes: Int?,  // "Average Runtime"
    val episodes: List<TvdbEpisode>
)

/**
 * Keyless TheTVDB metadata provider — grabs series identity + the full season/episode tree the same
 * way [com.nuvio.tv.core.tmdb.TmdbMetadataService] grabs from TMDB, but with no API key (it scrapes
 * the public website). Used to reconstruct Cinemeta "#DUPE#" entries that TMDB/IMDb can no longer
 * name because the dupe's own IMDb id was merged away. The dupe's Cinemeta meta still carries a
 * tvdb_id, which maps straight to the correct regional series here (e.g. 281388 -> "Air Disasters").
 *
 * Results are cached per tvdb id (positive + negative) for the process lifetime.
 */
@Singleton
class TvdbMetadataService @Inject constructor(
    private val api: TvdbWebApi
) {
    private val cache = ConcurrentHashMap<Int, TvdbSeries>()
    private val artCache = ConcurrentHashMap<Int, TvdbArt>()
    private val misses = ConcurrentHashMap<Int, Boolean>()

    companion object {
        private const val TAG = "TvdbMetadataService"

        private val TITLE_RE =
            Regex("<title>\\s*(.*?)\\s*-\\s*TheTVDB\\.com\\s*</title>", RegexOption.IGNORE_CASE)
        // Resolved slug from the final (post-redirect) URL path: /series/<slug>
        private val SLUG_RE = Regex("/series/([^/?#]+)")
        // "First Aired" row on the series page -> the date text inside the following <a>.
        private val FIRST_AIRED_RE = Regex(
            "First Aired\\s*</strong>\\s*<span>\\s*<a[^>]*>\\s*([^<]+?)\\s*</a>",
            RegexOption.IGNORE_CASE
        )
        // Artwork gallery links: <a href="<url>" class="lightbox" rel="artwork_<category>">. The
        // first entry in each category is the current/primary artwork.
        private val POSTER_RE = Regex(
            "<a href=\"(https://artworks\\.thetvdb\\.com/[^\"]+)\"\\s+class=\"lightbox\"\\s+rel=\"artwork_posters\""
        )
        private val BACKGROUND_RE = Regex(
            "<a href=\"(https://artworks\\.thetvdb\\.com/[^\"]+)\"\\s+class=\"lightbox\"\\s+rel=\"artwork_backgrounds\""
        )
        // Fallback primary poster: the header column's first responsive image.
        private val HEADER_POSTER_RE = Regex(
            "<img src=\"(https://artworks\\.thetvdb\\.com/[^\"]+)\"[^>]*class=\"[^\"]*img-responsive"
        )
        // One episode row: SxxExx label, /episodes/<id> link, the link's visible title, and the
        // (optional, best-effort) air date — the first <li> in the per-episode meta list, e.g.
        // "May 15, 2011". The air-date group is optional so a long episode description never drops
        // the whole row.
        private val EPISODE_RE = Regex(
            "episode-label\">\\s*S(\\d+)E(\\d+)\\s*</span>\\s*" +
                "<a href=\"[^\"]*?/episodes/(\\d+)\">\\s*([\\s\\S]*?)\\s*</a>" +
                "(?:[\\s\\S]{0,300}?<li>\\s*([A-Za-z]+ \\d{1,2}, \\d{4}))?"
        )
        private val YEAR_RE = Regex("(\\d{4})")
        // Sidebar info rows: <strong>Label</strong><span>value (maybe with <a>/<li> markup)</span>.
        private val INFO_FIELD_RE = Regex(
            "<strong>([^<]+)</strong>\\s*<span>([\\s\\S]*?)</span>",
            RegexOption.IGNORE_CASE
        )
        // Anchor text inside a field's <span> (genres list each genre in its own <a>).
        private val ANCHOR_TEXT_RE = Regex("<a[^>]*>\\s*([^<]+?)\\s*</a>")
        // Episode screenshot: /episodes/<id>"> <img ... data-src="<artwork url>">  (id -> thumb).
        private val EPISODE_THUMB_RE = Regex(
            "/episodes/(\\d+)\">\\s*<img[^>]+data-src=\"(https://artworks\\.thetvdb\\.com/[^\"]+)\""
        )

        private val MONTHS = mapOf(
            "january" to "01", "february" to "02", "march" to "03", "april" to "04",
            "may" to "05", "june" to "06", "july" to "07", "august" to "08",
            "september" to "09", "october" to "10", "november" to "11", "december" to "12"
        )

        /** "May 15, 2011" -> "2011-05-15" (null if unparseable). */
        internal fun toIsoDate(text: String?): String? {
            val t = text?.trim()?.takeIf { it.isNotBlank() } ?: return null
            val m = Regex("([A-Za-z]+)\\s+(\\d{1,2}),\\s*(\\d{4})").find(t) ?: return null
            val month = MONTHS[m.groupValues[1].lowercase()] ?: return null
            val day = m.groupValues[2].padStart(2, '0')
            return "${m.groupValues[3]}-$month-$day"
        }

        /** Map the series-page sidebar rows (label -> raw inner HTML of the value span). */
        private fun parseInfoFields(html: String): Map<String, String> =
            INFO_FIELD_RE.findAll(html).associate { it.groupValues[1].trim() to it.groupValues[2] }

        private fun stripTags(html: String): String =
            html.replace(Regex("<[^>]+>"), " ").replace(Regex("\\s+"), " ").trim()

        /** "53 minutes" -> 53. */
        private fun parseRuntimeMinutes(value: String?): Int? =
            value?.let { Regex("(\\d+)").find(it)?.groupValues?.getOrNull(1)?.toIntOrNull() }
    }

    /**
     * Lightweight name + primary-poster lookup (single small series page; no episode tree). Used by
     * catalog tiles, which need to replace the "#DUPE#" label and refresh the stale poster. Reuses
     * the full-series cache when present.
     */
    suspend fun nameAndPoster(tvdbId: Int?): TvdbArt? {
        if (tvdbId == null || tvdbId <= 0) return null
        cache[tvdbId]?.let { return TvdbArt(it.name, it.poster, it.year) }
        artCache[tvdbId]?.let { return it }
        return withContext(Dispatchers.IO) {
            runCatching {
                val html = api.seriesPage(tvdbId).body()?.string().orEmpty()
                if (html.isBlank()) return@runCatching null
                val name = TITLE_RE.find(html)?.groupValues?.getOrNull(1)?.trim()?.takeIf { it.isNotBlank() }
                val poster = (POSTER_RE.find(html) ?: HEADER_POSTER_RE.find(html))
                    ?.groupValues?.getOrNull(1)?.takeIf { it.isNotBlank() }
                val year = FIRST_AIRED_RE.find(html)?.groupValues?.getOrNull(1)
                    ?.let { YEAR_RE.find(it)?.value?.toIntOrNull() }
                if (name == null && poster == null) null else TvdbArt(name, poster, year)
            }.onFailure { Log.d(TAG, "nameAndPoster($tvdbId) failed: ${it.message}") }
                .getOrNull()?.also { artCache[tvdbId] = it }
        }
    }

    /** Fetch (and cache) the full TheTVDB metadata for a numeric series id. */
    suspend fun fetchSeries(tvdbId: Int?): TvdbSeries? {
        if (tvdbId == null || tvdbId <= 0) return null
        cache[tvdbId]?.let { return it }
        if (misses[tvdbId] == true) return null
        return withContext(Dispatchers.IO) {
            val result = runCatching {
                val resp = api.seriesPage(tvdbId)
                val html = resp.body()?.string().orEmpty()
                if (html.isBlank()) return@runCatching null
                val name = TITLE_RE.find(html)?.groupValues?.getOrNull(1)?.trim()?.takeIf { it.isNotBlank() }
                val slug = SLUG_RE.find(resp.raw().request.url.encodedPath)?.groupValues?.getOrNull(1)
                val seriesYear = FIRST_AIRED_RE.find(html)?.groupValues?.getOrNull(1)
                    ?.let { YEAR_RE.find(it)?.value?.toIntOrNull() }
                val poster = (POSTER_RE.find(html) ?: HEADER_POSTER_RE.find(html))
                    ?.groupValues?.getOrNull(1)?.takeIf { it.isNotBlank() }
                val background = BACKGROUND_RE.find(html)?.groupValues?.getOrNull(1)?.takeIf { it.isNotBlank() }

                val fields = parseInfoFields(html)
                val country = fields["Original Country"]?.let { stripTags(it) }?.takeIf { it.isNotBlank() }
                val language = fields["Original Language"]?.let { stripTags(it) }?.takeIf { it.isNotBlank() }
                val status = fields["Status"]?.let { stripTags(it) }?.takeIf { it.isNotBlank() }
                val runtimeMinutes = parseRuntimeMinutes(fields["Average Runtime"]?.let { stripTags(it) })
                val genres = fields["Genres"]?.let { span ->
                    ANCHOR_TEXT_RE.findAll(span).map { it.groupValues[1].trim() }.filter { it.isNotBlank() }.toList()
                        .ifEmpty { stripTags(span).split(",").map { it.trim() }.filter { it.isNotBlank() } }
                } ?: emptyList()

                val episodes = slug?.let { parseEpisodes(api.allSeasons(it).body()?.string().orEmpty()) } ?: emptyList()
                val year = seriesYear
                    ?: episodes.mapNotNull { it.airDateIso?.take(4)?.toIntOrNull() }.minOrNull()

                if (name == null && episodes.isEmpty()) null
                else TvdbSeries(
                    tvdbId = tvdbId,
                    name = name,
                    year = year,
                    poster = poster,
                    background = background,
                    country = country,
                    language = language,
                    status = status,
                    genres = genres,
                    runtimeMinutes = runtimeMinutes,
                    episodes = episodes
                )
            }.onFailure { Log.d(TAG, "fetchSeries($tvdbId) failed: ${it.message}") }.getOrNull()

            if (result != null) cache[tvdbId] = result else misses[tvdbId] = true
            result
        }
    }

    private fun parseEpisodes(html: String): List<TvdbEpisode> {
        if (html.isBlank()) return emptyList()
        // Episode id -> screenshot thumbnail (only ~90% of episodes have one).
        val thumbs = EPISODE_THUMB_RE.findAll(html).associate { it.groupValues[1] to it.groupValues[2] }
        val out = ArrayList<TvdbEpisode>(300)
        for (m in EPISODE_RE.findAll(html)) {
            val season = m.groupValues[1].toIntOrNull() ?: continue
            val episode = m.groupValues[2].toIntOrNull() ?: continue
            val id = m.groupValues[3].takeIf { it.isNotBlank() }
            val title = m.groupValues[4].replace(Regex("\\s+"), " ").trim().takeIf { it.isNotBlank() }
            val air = toIsoDate(m.groupValues.getOrNull(5))
            out += TvdbEpisode(
                season = season,
                episode = episode,
                title = title,
                airDateIso = air,
                thumbnail = id?.let { thumbs[it] },
                tvdbEpisodeId = id
            )
        }
        return out
    }
}
