package com.nuvio.tv.data.remote.api

import com.squareup.moshi.Json
import com.squareup.moshi.JsonClass
import retrofit2.http.GET
import retrofit2.http.Query

/**
 * Keyless, name-based torrent search across two public JSON indexers. Used to recover torrents for
 * Cinemeta "#DUPE#" series whose dead IMDb id returns nothing from id-based addons (Torrentio) or
 * the wrong show from others (Jackettio resolving tt2091498 -> "Dune: Prophecy"). A pure title
 * search can't be poisoned by the bad id. (1337x — which has the nicest packs — is Cloudflare-walled,
 * so it isn't usable from the device.)
 */

// --- apibay (The Pirate Bay public API): root is a JSON array; "No results returned" sentinel. ---
@JsonClass(generateAdapter = true)
data class ApibayItemDto(
    @Json(name = "name") val name: String? = null,
    @Json(name = "info_hash") val infoHash: String? = null,
    @Json(name = "seeders") val seeders: String? = null,
    @Json(name = "leechers") val leechers: String? = null,
    @Json(name = "size") val size: String? = null,
    @Json(name = "num_files") val numFiles: String? = null
)

interface ApibayApi {
    @GET("q.php")
    suspend fun search(
        @Query("q") query: String,
        @Query("cat") cat: Int = 0
    ): List<ApibayItemDto>
}

// --- torrents-csv (community torrent database). ---
@JsonClass(generateAdapter = true)
data class TorrentsCsvResponseDto(
    @Json(name = "torrents") val torrents: List<TorrentsCsvItemDto>? = null
)

@JsonClass(generateAdapter = true)
data class TorrentsCsvItemDto(
    @Json(name = "name") val name: String? = null,
    @Json(name = "infohash") val infoHash: String? = null,
    @Json(name = "seeders") val seeders: Int? = null,
    @Json(name = "leechers") val leechers: Int? = null,
    @Json(name = "size_bytes") val sizeBytes: Long? = null
)

interface TorrentsCsvApi {
    @GET("service/search")
    suspend fun search(
        @Query("q") query: String,
        @Query("size") size: Int = 50
    ): TorrentsCsvResponseDto
}
