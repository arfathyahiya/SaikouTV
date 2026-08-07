package ani.saikou.parsers.anime

import ani.saikou.FileUrl
import ani.saikou.Mapper
import ani.saikou.client
import ani.saikou.parsers.AnimeParser
import ani.saikou.parsers.Episode
import ani.saikou.parsers.ShowResponse
import ani.saikou.parsers.Subtitle
import com.lagradost.nicehttp.NiceResponse
import kotlinx.coroutines.delay
import ani.saikou.parsers.Video
import ani.saikou.parsers.VideoContainer
import ani.saikou.parsers.VideoExtractor
import ani.saikou.parsers.VideoServer
import ani.saikou.parsers.VideoType
import ani.saikou.tryWithSuspend
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.JsonElement
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody


class Animex : AnimeParser() {

    override val name = "AnimeX"
    override val saveName = "animex"
    override val hostUrl = "https://animex.one"
    override val isDubAvailableSeparately = false

    companion object {
        const val GRAPHQL_HOST = "https://graphql.animex.one/graphql"
        const val API_HOST = "https://pp.animex.one/rest/api"
        const val ORIGIN = "https://animex.one"
        const val BROWSER_UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/148.0.0.0 Safari/537.36"
        const val REFERER = "https://animex.one/"

        // The _amx_id cookie is bound to the Browser UA + the requester IP.
        // We capture it from the first pp.animex.one response and replay it on every
        // later call so the API doesn't keep treating us as a fresh anonymous client
        // (which is what trips the 429/403 bot filter). Shared by Animex + AnimexExtractor.
        @Volatile
        var amxIdCookie: String? = null
            private set

        // Merge our static headers with the latest captured cookie (injected + guaranteed UA match).
        fun animexHeaders(): Map<String, String> {
            val h = headers.toMutableMap()
            val cookie = amxIdCookie
            if (cookie != null) h["Cookie"] = "_amx_id=$cookie"
            return h
        }

        // Capture _amx_id from any pp.animex.one response (including 403/429 ones).
        fun captureCookie(res: NiceResponse) {
            res.cookies["_amx_id"]?.let { amxIdCookie = it }
        }

        // GET with _amx_id replay + a couple of retries on the 429/retry-after throttle.
        suspend fun getAnimex(url: String): NiceResponse {
            var res = client.get(url, headers = animexHeaders())
            captureCookie(res)

            var attempts = 0
            while (res.code == 429 && attempts < 3) {
                val retryAfter = res.headers["retry-after"]?.toLongOrNull() ?: 3L
                delay(retryAfter * 1000L)
                res = client.get(url, headers = animexHeaders())
                captureCookie(res)
                attempts++
            }
            return res
        }

        val headers = mapOf(
            "User-Agent" to BROWSER_UA,
            "Origin" to ORIGIN,
            "Referer" to REFERER,
            "Accept" to "application/json, text/plain, */*",
            "Sec-Fetch-Site" to "same-site",
            "Sec-Fetch-Mode" to "cors",
            "Sec-Fetch-Dest" to "empty",
            "sec-ch-ua" to "\"Not(A:Brand\";v=\"99\", \"Chromium\";v=\"148\"",
            "sec-ch-ua-mobile" to "?0",
            "sec-ch-ua-platform" to "\"Windows\"",
        )

        // Returns null when the response is an error/block page instead of the expected JSON list.
        fun parseIfNotError(text: String): List<EpisodesResponse>? {
            if (!text.startsWith("[") || text.contains("bot_detected") || text.contains("\"error\"")) {
                return null
            }
            return try {
                Mapper.json.decodeFromString<List<EpisodesResponse>>(text)
            } catch (_: Exception) {
                null
            }
        }
    }

    // --- GraphQL response models ---

    @Serializable
    data class SearchResult(
        val id: String,
        val anilistId: Int?,
        val titleRomaji: String?,
        val titleEnglish: String?,
        val coverImage: JsonElement?,
        val episodeCount: Int?,
        val subCount: Int?,
        val dubCount: Int?,
        val averageScore: Int?,
    )

    @Serializable
    data class SearchPayload(
        val data: PayloadData?,
    )

    @Serializable
    data class PayloadData(
        val catalogAnime: CatalogResults?,
    )

    @Serializable
    data class CatalogResults(
        val items: List<SearchResult> = emptyList(),
    )

    // --- Episode models ---

    @Serializable
    data class EpisodesResponse(
        val number: Int,
        val titles: EpisodeTitles?,
        val img: String? = null,
        val isFiller: Boolean = false,
        val description: String? = null,
        val hasDub: Boolean = false,
        val hasSub: Boolean = false,
    )

    @Serializable
    data class EpisodeTitles(
        val en: String?,
        val ja: String?,
    )

    // --- Server/Provider models ---

    @Serializable
    data class ProviderInfo(
        val id: String,
        val default: Boolean = false,
        val tip: String? = null,
    )

    @Serializable
    data class ServersResponse(
        val subProviders: List<ProviderInfo> = emptyList(),
        val dubProviders: List<ProviderInfo> = emptyList(),
    )

    // --- Video source models ---

    @Serializable
    data class SourceItem(
        val url: String,
        val quality: String? = null,
        val type: String?,
    )

    @Serializable
    data class SourcesResponse(
        val sources: List<SourceItem> = emptyList(),
        val tracks: JsonElement? = null,
        val headers: Map<String, String>? = null,
    )

    // --- GraphQL request bodies ---

    @Serializable
    data class SearchRequestBody(
        val query: String,
        val variables: SearchVariables,
    )

    @Serializable
    data class SearchVariables(
        val q: String,
        val limit: Int = 10,
        val includeAdult: Boolean = false,
    )

    // --- Extra data passed through the pipeline ---

    @Serializable
    data class AnimexExtra(
        val anilistId: String,
        val episodeNumber: Int = 1,
        val providerId: String? = null,
        val hasDub: Boolean = false,
        val hasSub: Boolean = true,
        val isDefault: Boolean = false,
    )

    // --- GraphQL search query (single-line to avoid trimIndent compiler bug) ---

    private val SEARCH_QUERY = "query(" + '$' + "q: String, " + '$' + "limit: Int, " + '$' + "includeAdult: Boolean) { catalogAnime(filter: { query: " + '$' + "q, includeAdult: " + '$' + "includeAdult }, limit: " + '$' + "limit) { items { id anilistId malId titleRomaji titleEnglish coverImage format status episodeCount seasonYear season color genres bannerImage } } }"

    // --- Parser overrides ---

    override suspend fun search(query: String): List<ShowResponse> = tryWithSuspend {
        val body = Mapper.json.encodeToString(
            SearchRequestBody(SEARCH_QUERY, SearchVariables(query)),
        )
        client.post(
            GRAPHQL_HOST,
            requestBody = body.toByteArray().toRequestBody("application/json".toMediaType()),
            headers = headers,
        ).parsed<SearchPayload>()?.data?.catalogAnime?.items.orEmpty()
    }.orEmpty().mapNotNull { item ->
        val coverLarge: String? = (item.coverImage as? kotlinx.serialization.json.JsonObject)?.get("large")?.let { it as? kotlinx.serialization.json.JsonPrimitive }?.content?.trim('"')
        ShowResponse(
            name = item.titleEnglish ?: item.titleRomaji ?: "",
            link = "$hostUrl/anime/${item.id}",
            coverUrl = FileUrl(coverLarge ?: ""),
            otherNames = listOfNotNull(item.titleRomaji, item.titleEnglish),
            total = if (item.subCount != null) item.subCount else item.episodeCount,
            extra = mapOf("anilistId" to item.id),
        )
    }

    override suspend fun loadEpisodes(animeLink: String, extra: Map<String, String>?): List<Episode> {
        val anilistId = extra?.get("anilistId") ?: return emptyList()
        val episodes = tryWithSuspend {
            getAnimex("$API_HOST/episodes?id=$anilistId").text.let { parseIfNotError(it) }
        }.orEmpty()
        return episodes.map { ep ->
            Episode(
                number = ep.number.toString(),
                link = animeLink,
                title = ep.titles?.en ?: "",
                thumbnail = FileUrl(ep.img ?: ""),
                description = ep.description,
                isFiller = ep.isFiller,
                extra = AnimexExtra(
                    anilistId = anilistId,
                    episodeNumber = ep.number,
                    hasDub = ep.hasDub,
                    hasSub = ep.hasSub,
                ),
            )
        }
    }

    override suspend fun loadVideoServers(episodeLink: String, extra: Any?): List<VideoServer> {
        val animexExtra = (extra as? AnimexExtra) ?: return emptyList()
        val serversRes = tryWithSuspend {
            getAnimex("$API_HOST/servers?id=${animexExtra.anilistId}&epNum=${animexExtra.episodeNumber}").parsed<ServersResponse>()
        } ?: return emptyList()

        val allProviders = (if (animexExtra.hasSub) serversRes.subProviders else emptyList()) +
            (if (animexExtra.hasDub) serversRes.dubProviders else emptyList())

        return allProviders.map { p ->
            VideoServer(
                name = "${p.id} (${if (serversRes.dubProviders.any { it.id == p.id }) "dub" else "sub"})".trim(),
                embed = FileUrl(""), // not used for direct streaming
                extraData = AnimexExtra(
                    anilistId = animexExtra.anilistId,
                    episodeNumber = animexExtra.episodeNumber,
                    providerId = p.id,
                    hasDub = serversRes.dubProviders.any { it.id == p.id },
                    isDefault = p.default,
                ),
            )
        }
    }

    override suspend fun getVideoExtractor(server: VideoServer): VideoExtractor? {
        return AnimexExtractor(server)
    }
}

// --- Video extractor for Animex - fetches m3u8 URLs from the sources API ---

class AnimexExtractor(override val server: VideoServer) : VideoExtractor() {
    private companion object {
        const val API_HOST = "https://pp.animex.one/rest/api"
    }

    override suspend fun extract(): VideoContainer {
        val extra = (server.extraData as? Animex.AnimexExtra) ?: return VideoContainer(emptyList(), emptyList())
        val typeParam = if (extra.hasDub) "dub" else "sub"
        val providerId = extra.providerId ?: "uwu"

        val sourcesRes = tryWithSuspend {
            Animex.getAnimex(
                "$API_HOST/sources?id=${extra.anilistId}&epNum=${extra.episodeNumber}&type=$typeParam&providerId=$providerId"
            ).parsed<Animex.SourcesResponse>()
        } ?: return VideoContainer(emptyList(), emptyList())

        val videos = sourcesRes.sources.mapNotNull { src ->
            try {
                val quality = when (src.quality?.lowercase()) {
                    "1080p" -> 1080
                    "720p" -> 720
                    "480p" -> 480
                    "360p" -> 360
                    else -> null
                }
                Video(
                    quality = quality,
                    format = VideoType.M3U8,
                    url = FileUrl(src.url, sourcesRes.headers ?: mapOf()),
                    extraNote = src.quality,
                )
            } catch (_: Exception) {
                null
            }
        }.sortedByDescending { it.quality ?: 0 }

        return VideoContainer(videos, emptyList())
    }
}
