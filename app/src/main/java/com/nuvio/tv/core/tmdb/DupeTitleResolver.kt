package com.nuvio.tv.core.tmdb

import android.util.Log
import com.nuvio.tv.core.tvdb.TvdbArt
import com.nuvio.tv.core.tvdb.TvdbMetadataService
import com.nuvio.tv.data.mapper.toDomain
import com.nuvio.tv.data.remote.api.AddonApi
import com.nuvio.tv.domain.model.Meta
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Bridges Cinemeta's broken "#DUPE#" entries to authoritative TheTVDB metadata.
 *
 * Cinemeta de-duplicates shows that have multiple regional IMDb entries (e.g. Mayday / Air Crash
 * Investigation / Air Disasters): it renames the duplicate's `name` to the literal "#DUPE#" and
 * sets slug "<type>/dupe-<canonicalId>", but leaves the entry — with correct art/episodes/id — in
 * the catalog. The dupe's own IMDb id is dead/merged at TMDB & IMDb, so those can't name it. BUT the
 * dupe's Cinemeta meta still carries a `tvdb_id`, which maps straight to the correct regional entry
 * on TheTVDB (e.g. 281388 = "Air Disasters"). [TvdbMetadataService] resolves it KEYLESSLY.
 *
 * Two entry points:
 *  - [resolveTileArt]: catalog tiles only have an id, so we fetch the dupe's Cinemeta meta for its
 *    tvdb_id, then ask TheTVDB for the rightful name + current poster.
 *  - [cinemetaDupeMeta]: detail screens need the whole authoritative entry. Because the detail meta
 *    can be served by a *different* meta addon (which masks the dupe — no #DUPE# marker, no tvdb_id,
 *    canonical "Mayday" data), we re-fetch the entry straight from Cinemeta and hand back the real
 *    dupe meta (correct art + episodes + tvdb_id) so the caller can overlay the TheTVDB identity.
 */
@Singleton
class DupeTitleResolver @Inject constructor(
    private val tvdbMetadataService: TvdbMetadataService,
    private val addonApi: AddonApi
) {
    private val ioDispatcher = Dispatchers.IO
    private val tvdbIdByMetaUrl = ConcurrentHashMap<String, Int>()   // meta url -> tvdb_id (or NONE)
    private val dupeMetaById = ConcurrentHashMap<String, Meta>()     // id -> authoritative dupe meta
    private val notDupeIds = ConcurrentHashMap<String, Boolean>()    // id -> confirmed NOT a dupe

    companion object {
        private const val TAG = "DupeTitleResolver"
        private const val NONE = -1

        /** Whether a catalog/meta entry is one of Cinemeta's broken dedup markers. */
        fun isDupeMarker(name: String?, slug: String?): Boolean {
            if (name?.trim() == "#DUPE#") return true
            val s = slug?.lowercase()?.trim().orEmpty()
            return s.startsWith("series/dupe-") || s.startsWith("movie/dupe-") || s.startsWith("dupe-")
        }
    }

    private fun metaUrl(addonBaseUrl: String, type: String, id: String): String =
        "${addonBaseUrl.trimEnd('/')}/meta/$type/$id.json"

    /**
     * Catalog-tile path: id -> dupe's tvdb_id (via Cinemeta meta) -> TheTVDB name + current poster
     * (Cinemeta's dupe poster is often the stale pre-2019 set). Returns null when not resolvable.
     */
    suspend fun resolveTileArt(addonBaseUrl: String, type: String, dupeId: String): TvdbArt? {
        val url = metaUrl(addonBaseUrl, type, dupeId)
        val cached = tvdbIdByMetaUrl[url]
        if (cached != null) return tvdbMetadataService.nameAndPoster(cached.takeIf { it != NONE })
        val tvdbId = withContext(ioDispatcher) {
            runCatching { addonApi.getMeta(url).body()?.meta?.tvdbId }
                .onFailure { Log.d(TAG, "dupe meta fetch failed ($url): ${it.message}") }
                .getOrNull()
        }
        tvdbIdByMetaUrl[url] = tvdbId ?: NONE
        return tvdbMetadataService.nameAndPoster(tvdbId)
    }

    /**
     * Detail path: fetch the entry straight from [addonBaseUrl] (a Cinemeta base) and, if it really
     * is a dupe with a tvdb_id, return that authoritative Cinemeta meta (correct art + episodes).
     * Returns null when it isn't a dupe — so non-dupe details pay only one cheap, cached GET.
     */
    suspend fun cinemetaDupeMeta(addonBaseUrl: String, type: String, id: String): Meta? {
        dupeMetaById[id]?.let { return it }
        if (notDupeIds[id] == true) return null
        val url = metaUrl(addonBaseUrl, type, id)
        val meta = withContext(ioDispatcher) {
            runCatching { addonApi.getMeta(url).body()?.meta }
                .onFailure { Log.d(TAG, "cinemeta meta fetch failed ($url): ${it.message}") }
                .getOrNull()
        }
        if (meta == null) return null  // transient error — don't poison the negative cache
        tvdbIdByMetaUrl[url] = meta.tvdbId ?: NONE
        val isDupe = isDupeMarker(meta.name, meta.slug) && meta.tvdbId != null
        if (!isDupe) {
            notDupeIds[id] = true
            return null
        }
        return meta.toDomain().also { dupeMetaById[id] = it }
    }
}
