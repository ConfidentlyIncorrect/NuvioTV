package com.nuvio.tv.domain.repository

import com.nuvio.tv.core.network.NetworkResult
import com.nuvio.tv.domain.model.AddonStreams
import com.nuvio.tv.domain.model.Stream
import kotlinx.coroutines.flow.Flow

interface StreamRepository {
    /** Suspends local plugin work while playback owns the device, then resumes the same search. */
    fun setLocalPluginSearchPaused(paused: Boolean)

    /**
     * Fetches streams from all installed addons for a given video ID
     * @param type The content type (movie, series, etc.)
     * @param videoId The video ID (for movies: IMDB ID, for series: IMDB_ID:season:episode)
     * @param season Optional season number for TV shows (used by local plugins)
     * @param episode Optional episode number for TV shows (used by local plugins)
     * @return Flow of AddonStreams grouped by addon
     */
    fun getStreamsFromAllAddons(
        type: String,
        videoId: String,
        season: Int? = null,
        episode: Int? = null,
        scope: String? = null,
        forceRefresh: Boolean = false
    ): Flow<NetworkResult<List<AddonStreams>>>

    /**
     * Fetches streams from a specific addon
     * @param baseUrl The addon base URL
     * @param type The content type
     * @param videoId The video ID
     * @param poll When true, adds ?poll=1 so a progressive addon (Comet) returns its current
     *   results without kicking a new scrape. Used to live-refresh an open stream list.
     * @param scope Optional search scope ("season"/"series") sent as ?scope= so a progressive addon
     *   (Comet) returns whole-season / whole-series packs instead of just the requested episode.
     * @return NetworkResult containing list of streams
     */
    suspend fun getStreamsFromAddon(
        baseUrl: String,
        type: String,
        videoId: String,
        poll: Boolean = false,
        scope: String? = null
    ): NetworkResult<List<Stream>>
}
