package com.nuvio.tv.core.torrentsearch

import android.util.Log
import com.nuvio.tv.core.tmdb.DupeTitleResolver
import com.nuvio.tv.core.tvdb.TvdbMetadataService
import com.nuvio.tv.data.remote.api.ApibayApi
import com.nuvio.tv.data.remote.api.TorrentsCsvApi
import com.nuvio.tv.domain.model.Stream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Name-based torrent search for Cinemeta "#DUPE#" series. Their dead IMDb id makes id-based addons
 * (Torrentio) return ~nothing and confuses others (Jackettio matched tt2091498 -> "Dune: Prophecy").
 * We instead resolve the show's real title + aliases from TheTVDB (e.g. "Air Disasters" / "Mayday" /
 * "Air Crash Investigation") and search public title indexers — a pure name match can't be poisoned
 * by the bad id. Results are returned as standard infoHash streams; the user's debrid service
 * (AllDebrid/TorBox) resolves them and the existing per-pack file selector picks the right episode.
 *
 * Scoped to dupes only: for normal shows the id-based addons are correct, and a bare title search on
 * a common name ("Lost") would surface the wrong show.
 */
@Singleton
class TorrentSearchSource @Inject constructor(
    private val apibayApi: ApibayApi,
    private val torrentsCsvApi: TorrentsCsvApi,
    private val dupeTitleResolver: DupeTitleResolver,
    private val tvdbMetadataService: TvdbMetadataService
) {
    private data class Hit(val name: String, val hash: String, val seeders: Int, val sizeBytes: Long, val source: String)

    companion object {
        private const val TAG = "TorrentSearchSource"
        private const val CINEMETA_BASE = "https://v3-cinemeta.strem.io"
        const val SOURCE_LABEL = "🔎 Name search"

        private val HASH_RE = Regex("^[a-f0-9]{40}$|^[a-f0-9]{32}$")
        private val EP_RE = Regex("s\\d{1,2}e\\d{1,2}|\\b\\d{1,2}x\\d{1,2}\\b")
        private val TRACKERS = listOf(
            "udp://tracker.opentrackr.org:1337/announce",
            "udp://open.demonii.com:1337/announce",
            "udp://tracker.openbittorrent.com:6969/announce",
            "udp://exodus.desync.com:6969/announce",
            "udp://tracker.torrent.eu.org:451/announce",
            "udp://open.stealth.si:80/announce",
            "udp://explodie.org:6969/announce"
        )
    }

    /** Resolve a dupe's title + aliases (cached), or null when [imdbId] isn't a dupe. */
    private suspend fun resolveTitles(imdbId: String): List<String>? {
        val dupeMeta = dupeTitleResolver.cinemetaDupeMeta(CINEMETA_BASE, "series", imdbId) ?: return null
        val tvdb = tvdbMetadataService.fetchSeries(dupeMeta.tvdbId) ?: return null
        val primary = tvdb.name?.takeIf { it.isNotBlank() && it != "#DUPE#" } ?: return null
        return (listOf(primary) + tvdb.aliases)
            .map { it.trim() }
            .filter { it.length >= 3 }
            .distinctBy { it.lowercase() }
            .take(4)  // primary + up to 3 aliases — keeps the request fan-out bounded
    }

    /**
     * Search for an episode of a dupe series. Returns infoHash streams (season packs, multi-season
     * packs, and exact-episode torrents) ranked by seeders; empty if not a dupe or nothing found.
     */
    suspend fun searchEpisode(imdbId: String, season: Int?, episode: Int?): List<Stream> {
        if (season == null || episode == null) return emptyList()
        val titles = resolveTitles(imdbId) ?: return emptyList()
        val primary = titles.first()  // the show's own title — same season numbering as our entry
        val hits = withContext(Dispatchers.IO) {
            coroutineScope {
                titles.flatMap { t -> listOf(async { apibay(t) }, async { torrentsCsv(t) }) }
                    .awaitAll().flatten()
            }
        }

        // Dedupe by infoHash, keeping the highest-seeded copy.
        val byHash = LinkedHashMap<String, Hit>()
        for (h in hits) {
            if (!HASH_RE.matches(h.hash)) continue
            val existing = byHash[h.hash]
            if (existing == null || h.seeders > existing.seeders) byHash[h.hash] = h
        }

        return byHash.values
            .mapNotNull { hit -> classify(hit.name, season, episode)?.let { hit to it } }
            // Rank torrents named with the show's OWN title first: aliases (e.g. "Mayday") use a
            // different season numbering, so an auto-picked SxxExx from them can be the wrong episode.
            .sortedWith(
                compareByDescending<Pair<Hit, String>> { it.first.name.contains(primary, ignoreCase = true) }
                    .thenByDescending { it.first.seeders }
            )
            .take(40)
            .map { (hit, kind) -> toStream(hit, kind, primaryMatch = hit.name.contains(primary, ignoreCase = true)) }
    }

    private suspend fun apibay(title: String): List<Hit> = runCatching {
        apibayApi.search(title)
            .filterNot { it.name.equals("No results returned", ignoreCase = true) }
            .mapNotNull { dto ->
                val hash = dto.infoHash?.lowercase()?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
                Hit(dto.name.orEmpty(), hash, dto.seeders?.toIntOrNull() ?: 0, dto.size?.toLongOrNull() ?: 0L, "TPB")
            }
    }.onFailure { Log.d(TAG, "apibay('$title') failed: ${it.message}") }.getOrDefault(emptyList())

    private suspend fun torrentsCsv(title: String): List<Hit> = runCatching {
        torrentsCsvApi.search(title).torrents.orEmpty().mapNotNull { dto ->
            val hash = dto.infoHash?.lowercase()?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
            Hit(dto.name.orEmpty(), hash, dto.seeders ?: 0, dto.sizeBytes ?: 0L, "tcsv")
        }
    }.onFailure { Log.d(TAG, "torrents-csv('$title') failed: ${it.message}") }.getOrDefault(emptyList())

    /** Whether a torrent name plausibly contains the requested episode. */
    private fun classify(name: String, season: Int, episode: Int): String? {
        val n = name.lowercase()
        val s2 = season.toString().padStart(2, '0')
        val e2 = episode.toString().padStart(2, '0')
        if (n.contains("s${s2}e$e2") || n.contains("${season}x$e2") || n.contains("${season}x$episode")) return "episode"
        val hasAnyEpisode = EP_RE.containsMatchIn(n)
        if (hasAnyEpisode) return null  // an episode torrent for a DIFFERENT episode
        val packSeason = Regex("(s$s2|s$season\\b|season[ ._]*$season\\b|series[ ._]*$season\\b)").containsMatchIn(n)
        if (packSeason) return "season"
        if (Regex("(complete|all seasons|s\\d{2}\\s*-\\s*s\\d{2}|seasons)").containsMatchIn(n)) return "complete"
        return null
    }

    private fun toStream(hit: Hit, kind: String, primaryMatch: Boolean): Stream {
        val (quality, qualityValue) = parseQuality(hit.name)
        val sizeGb = if (hit.sizeBytes > 0) String.format("%.2f GB", hit.sizeBytes / 1_000_000_000.0) else "?"
        val kindTag = when (kind) {
            "episode" -> "Episode"
            "season" -> "Season pack"
            "complete" -> "Complete pack"
            else -> kind
        }
        // Aliases ("Mayday"/"Air Crash Investigation") number their seasons differently, so the file
        // picked from them may be a neighbouring episode — flag it so the choice is informed.
        val numberingTag = if (primaryMatch) "" else " ⚠ alt numbering"
        return Stream(
            name = "$SOURCE_LABEL\n${quality ?: kindTag}",
            title = "${hit.name}\n👤 ${hit.seeders} 💾 $sizeGb • $kindTag • ${hit.source}$numberingTag",
            description = null,
            url = null,
            ytId = null,
            infoHash = hit.hash,
            fileIdx = null,  // debrid file selector matches the episode by SxxExx in filenames
            externalUrl = null,
            behaviorHints = null,
            addonName = SOURCE_LABEL,
            addonLogo = null,
            sources = TRACKERS.map { "tracker:$it" } + "dht:${hit.hash}",
            quality = quality,
            qualityValue = qualityValue
        )
    }

    private fun parseQuality(name: String): Pair<String?, Int> {
        val n = name.lowercase()
        return when {
            n.contains("2160") || n.contains("4k") || n.contains("uhd") -> "2160p" to 2160
            n.contains("1080") -> "1080p" to 1080
            n.contains("720") -> "720p" to 720
            n.contains("480") -> "480p" to 480
            else -> null to 0
        }
    }
}
