package com.nuvio.tv.data.remote.api

import okhttp3.ResponseBody
import retrofit2.Response
import retrofit2.http.GET
import retrofit2.http.Headers
import retrofit2.http.Path

/**
 * Minimal, KEYLESS TheTVDB website client. TheTVDB's public v4 API requires an API key + JWT login;
 * we don't need that just for metadata recovery. Instead we scrape two stable, unauthenticated
 * pages:
 *
 *  - [seriesPage]: the by-numeric-id "dereferrer" which 302-redirects to the canonical series page
 *    (OkHttp follows it). The page <title> is always "<Series Name> - TheTVDB.com", and
 *    `response.raw().request.url` exposes the resolved slug (e.g. "air-disasters-2011"). It also
 *    carries the "First Aired" date for the year.
 *  - [allSeasons]: the "all seasons (official)" view — a single page listing every episode with its
 *    SxxExx label, title and air date — enough to rebuild a correct season/episode tree.
 *
 * The dupe's Cinemeta meta carries the tvdb_id, so this maps straight to the correct regional entry
 * (e.g. tvdb_id 281388 -> "Air Disasters").
 */
interface TvdbWebApi {
    @GET("dereferrer/series/{id}")
    @Headers(
        "User-Agent: Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"
    )
    suspend fun seriesPage(@Path("id") tvdbId: Int): Response<ResponseBody>

    @GET("series/{slug}/allseasons/official")
    @Headers(
        "User-Agent: Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"
    )
    suspend fun allSeasons(@Path("slug") slug: String): Response<ResponseBody>
}
