package com.nuvio.tv.data.repository

import android.content.Context
import android.util.Log
import com.nuvio.tv.core.network.NetworkResult
import com.nuvio.tv.core.network.safeApiCall
import com.nuvio.tv.core.tmdb.DupeTitleResolver
import com.nuvio.tv.data.mapper.toDomain
import com.nuvio.tv.data.mapper.toDomainOrNull
import com.nuvio.tv.data.remote.api.AddonApi
import com.nuvio.tv.domain.model.CatalogRow
import com.nuvio.tv.domain.model.ContentType
import com.nuvio.tv.domain.model.MetaPreview
import com.nuvio.tv.domain.repository.CatalogRepository
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import java.net.URLEncoder
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class CatalogRepositoryImpl @Inject constructor(
    @ApplicationContext private val context: Context,
    private val api: AddonApi,
    private val dupeTitleResolver: DupeTitleResolver
) : CatalogRepository {
    companion object {
        private const val TAG = "CatalogRepository"
    }

    override fun getCatalog(
        addonBaseUrl: String,
        addonId: String,
        addonName: String,
        catalogId: String,
        catalogName: String,
        type: String,
        skip: Int,
        skipStep: Int,
        extraArgs: Map<String, String>,
        supportsSkip: Boolean
    ): Flow<NetworkResult<CatalogRow>> = flow {
        emit(NetworkResult.Loading)

        val url = buildCatalogUrl(addonBaseUrl, type, catalogId, skip, extraArgs)
        Log.d(
            TAG,
            "Fetching catalog addonId=$addonId addonName=$addonName type=$type catalogId=$catalogId skip=$skip skipStep=$skipStep supportsSkip=$supportsSkip url=$url"
        )

        when (val result = safeApiCall(context) { api.getCatalog(url) }) {
            is NetworkResult.Success -> {
                val rawItemCount = result.data.metas.size
                val items = repairDupeNames(
                    result.data.metas
                        .mapNotNull { it?.toDomainOrNull(type, addonBaseUrl) }
                        .distinctBy { it.id },
                    addonBaseUrl,
                    type
                )
                Log.d(
                    TAG,
                    "Catalog fetch success addonId=$addonId type=$type catalogId=$catalogId items=${items.size}"
                )

                val catalogRow = CatalogRow(
                    addonId = addonId,
                    addonName = addonName,
                    addonBaseUrl = addonBaseUrl,
                    catalogId = catalogId,
                    catalogName = catalogName,
                    type = ContentType.fromString(type),
                    rawType = type,
                    items = items,
                    isLoading = false,
                    hasMore = supportsSkip && rawItemCount > 0,
                    currentPage = if (skipStep > 0) skip / skipStep else 0,
                    supportsSkip = supportsSkip,
                    skipStep = skipStep,
                    nextSkip = if (supportsSkip && rawItemCount > 0) skip + rawItemCount else skip,
                    extraArgs = extraArgs
                )
                emit(NetworkResult.Success(catalogRow))
            }
            is NetworkResult.Error -> {
                Log.w(
                    TAG,
                    "Catalog fetch failed addonId=$addonId type=$type catalogId=$catalogId code=${result.code} message=${result.message} url=$url"
                )
                emit(result)
            }
            NetworkResult.Loading -> { /* Already emitted */ }
        }
    }

    // Cinemeta leaves half-deduplicated entries in catalog/search results with name "#DUPE#"
    // (slug "<type>/dupe-<id>"). Their episodes/id are intact, so rather than hiding them we restore
    // the rightful title AND current poster from TheTVDB (keyless): fetch the dupe's Cinemeta meta to
    // read its tvdb_id, then resolve name + poster from TheTVDB (its art is current; Cinemeta's dupe
    // art is often the stale pre-2019 set). Fast path: no-op unless a dupe is actually present (the
    // common case), and resolution is cached by meta url + tvdb id.
    private suspend fun repairDupeNames(
        items: List<MetaPreview>,
        addonBaseUrl: String,
        type: String
    ): List<MetaPreview> {
        if (items.none { DupeTitleResolver.isDupeMarker(it.name, it.slug) }) return items
        val out = ArrayList<MetaPreview>(items.size)
        for (item in items) {
            if (DupeTitleResolver.isDupeMarker(item.name, item.slug)) {
                val art = dupeTitleResolver.resolveTileArt(addonBaseUrl, type, item.id)
                out += if (art != null) {
                    item.copy(
                        name = art.name?.takeIf { it.isNotBlank() && it != "#DUPE#" } ?: item.name,
                        poster = art.poster ?: item.poster,
                        rawPosterUrl = art.poster ?: item.rawPosterUrl,
                        // Cinemeta's dupe meta leaks the canonical year (e.g. "2003–"); use TheTVDB's.
                        releaseInfo = art.year?.toString() ?: item.releaseInfo
                    )
                } else item
            } else {
                out += item
            }
        }
        return out
    }

    private fun buildCatalogUrl(
        baseUrl: String,
        type: String,
        catalogId: String,
        skip: Int,
        extraArgs: Map<String, String>
    ): String {
        val trimmedBase = baseUrl.trimEnd('/')
        val queryStart = trimmedBase.indexOf('?')
        val basePath = if (queryStart >= 0) trimmedBase.substring(0, queryStart).trimEnd('/') else trimmedBase
        val baseQuery = if (queryStart >= 0) trimmedBase.substring(queryStart) else ""

        val catalogPath = if (extraArgs.isEmpty()) {
            if (skip > 0) {
                "$basePath/catalog/$type/$catalogId/skip=$skip.json"
            } else {
                "$basePath/catalog/$type/$catalogId.json"
            }
        } else {
            val allArgs = LinkedHashMap<String, String>()
            allArgs.putAll(extraArgs)

            if (!allArgs.containsKey("skip") && skip > 0) {
                allArgs["skip"] = skip.toString()
            }

            val encodedArgs = allArgs.entries.joinToString("&") { (key, value) ->
                "${encodeArg(key)}=${encodeArg(value)}"
            }

            "$basePath/catalog/$type/$catalogId/$encodedArgs.json"
        }

        return catalogPath + baseQuery
    }

    private fun encodeArg(value: String): String {
        return URLEncoder.encode(value, "UTF-8").replace("+", "%20")
    }
}
