package ani.saikou.parsers.anime

import ani.saikou.FileUrl
import ani.saikou.Mapper
import ani.saikou.client
import ani.saikou.parsers.AnimeParser
import ani.saikou.parsers.Episode
import ani.saikou.parsers.ShowResponse
import ani.saikou.parsers.VideoExtractor
import ani.saikou.parsers.VideoServer
import android.util.Log
import ani.saikou.parsers.anime.extractors.KickAssVidStreaming
import ani.saikou.parsers.anime.extractors.VidStreaming
import ani.saikou.tryWithSuspend
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody

class KickAssAnime : AnimeParser() {

    companion object {
        const val TAG = "KickassAnime"
        const val DEBUG = true
    }

    override val name = "KickassAnime"
    override val saveName = "kickassanime"
    override val hostUrl = "https://kaa.lt"
    override val isDubAvailableSeparately = false

    private fun getImageUrl(link: String?): String? {
        if (link == null) return null
        return if (link.startsWith(hostUrl)) link else "$hostUrl/image/poster/$link.webp"
    }

    private fun getThumbnailUrl(link: String?): String? {
        if (link == null) return null
        return if (link.startsWith(hostUrl)) link else "$hostUrl/image/thumbnail/$link.webp"
    }


    override suspend fun search(query: String): List<ShowResponse> {
        val jsonBody = Mapper.json.encodeToString(SearchQuery(1, query))
        val mediaType = "application/json".toMediaType()
        val requestBody = jsonBody.toRequestBody(mediaType)
        val headers = mapOf(
            "Accept" to "*/*",
            "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:150.0) Gecko/20100101 Firefox/150.0",
            "Content-Type" to "application/json",
            "X-Origin" to hostUrl,
            "Origin" to hostUrl
        )
        val res = tryWithSuspend {
            client.post("$hostUrl/api/fsearch", requestBody = requestBody, headers = headers).text
        } ?: return emptyList()

        val results = tryWithSuspend {
            Mapper.parse<SearchResponse>(res).result
        } ?: return emptyList()

        return results.map { sr ->
            val title = (sr.titleEn ?: sr.title).replace("\"", "")
            val coverUrl = sr.poster.hq?.let(::getImageUrl) ?: sr.slug
            val link = "$hostUrl/${sr.slug}"
            ShowResponse(title, link, coverUrl, listOf(sr.title))
        }
    }

    override suspend fun loadEpisodes(animeLink: String, extra: Map<String, String>?): List<Episode> {
        val showName = animeLink.substringAfter(hostUrl).trimStart('/')

        // GET show detail (slug needs suffix for API)
        val slug = showName.substringBefore('/').let { name ->
            val slugRegex = Regex("""-[a-f0-9]{4}""")
            if (slugRegex.containsMatchIn(name)) name
            else name + "-068f"
        }
        val loadJson = tryWithSuspend {
            client.get("$hostUrl/api/show/$slug").parsed<loadres>()
        } ?: return emptyList()

        // GET episodes
        val headers = mapOf(
            "Referer" to animeLink,
            "X-Origin" to "kaa.lt",
            "Origin" to "https://kaa.lt"
        )
        val episodesJson = tryWithSuspend {
            client.get("$hostUrl/api/show/$slug/episodes?ep=1&lang=ja-JP", headers = headers).text
        } ?: return emptyList()

        val episodes = parseEpisodes(episodesJson)

        return episodes.mapNotNull { ep ->
            val epNum = ep.episodeNumber.toString().substringBefore(".").toIntOrNull()
                ?: return@mapNotNull null
            val epSlug = ep.slug
            val epLink = "$hostUrl/$slug/ep-$epNum-${epSlug}"
            val thumbnail = ep.thumbnail.hq?.let(::getThumbnailUrl)?.let { FileUrl(it) }
            Episode(
                epNum.toString(),
                epLink,
                ep.title,
                thumbnail,
                loadJson.synopsis
            )
        }.reversed()
    }

    @Serializable
    private data class EpisodeResponse(
        @SerialName("current_page") val currentPage: Int?,
        @SerialName("pages") val pages: List<PageInfo>?,
        @SerialName("result") val result: List<EpisodeData>?
    )

    @Serializable
    private data class PageInfo(
        val number: Int,
        @SerialName("from") val from: String,
        @SerialName("to") val to: String,
        val eps: List<Int>
    )

    private suspend fun parseEpisodes(json: String): List<EpisodeData> {
        return tryWithSuspend {
            Mapper.parse<EpisodeResponse>(json).result
        } ?: emptyList()
    }

    override suspend fun loadVideoServers(episodeLink: String, extra: Any?): List<VideoServer> {
        if (DEBUG) Log.d(TAG, "loadVideoServers: $episodeLink")
        val path = episodeLink.substringAfter(hostUrl).trimStart('/')
        val apiPath = path.substringAfter("/ep-")
        val slug = path.substringBefore("/ep-")
        val apiUrl = "$hostUrl/api/show/$slug/episode/ep-$apiPath"
        if (DEBUG) Log.d(TAG, "loadVideoServers: apiUrl=$apiUrl")
        val headers = mapOf(
            "Referer" to episodeLink,
            "X-Origin" to "kaa.lt",
            "Origin" to "https://kaa.lt"
        )
        val serversRes = tryWithSuspend {
            client.get(apiUrl, headers = headers).parsed<ServersRes>()
        } ?: run {
            if (DEBUG) Log.e(TAG, "loadVideoServers: failed to parse servers response")
            return emptyList()
        }

        val servers = serversRes.servers?.map { s ->
            if (DEBUG) Log.d(TAG, "loadVideoServers: server name='${s.name}' src=${s.src}")
            VideoServer(s.name, s.src)
        } ?: run {
            if (DEBUG) Log.w(TAG, "loadVideoServers: servers list is null")
            emptyList()
        }
        if (DEBUG) Log.d(TAG, "loadVideoServers: returned ${servers.size} servers")
        return servers
    }

    override suspend fun getVideoExtractor(server: VideoServer): VideoExtractor? {
        if (DEBUG) Log.d(TAG, "getVideoExtractor: server.name='${server.name}' embed=${server.embed.url}")
        val extractor: VideoExtractor = KickAssVidStreaming(server)
        return extractor
    }

    @Serializable
    private data class SearchResponse(
        @SerialName("result") val result: List<SearchResult>?
    )

    @Serializable
    private data class SearchQuery(
        val page: Int,
        @SerialName("query") val query: String
    )

    @Serializable
    private data class SearchResult(
        val rating: String?,
        val slug: String,
        val status: String?,
        val title: String,
        @SerialName("title_en") val titleEn: String?,
        val type: String?,
        val poster: SearchPoster,
        @SerialName("start_date") val startDate: String?,
        val year: Long?
    )

    @Serializable
    private data class SearchPoster(
        val formats: List<String>,
        val sm: String,
        val aspectRatio: Double,
        @SerialName("hq") val hq: String
    )

    @Serializable
    private data class loadres(
        val episodeDuration: Long?,
        val genres: List<String>?,
        val locales: List<String>?,
        val season: String?,
        val slug: String?,
        val startDate: String?,
        val status: String?,
        val synopsis: String?,
        val title: String?,
        @SerialName("title_en") val titleEn: String?,
        val titleOriginal: String?,
        val type: String?,
        val year: Long?,
        val poster: LoadPoster?,
        val banner: Banner?,
        val endDate: String?,
        val rating: String?,
        val watchUri: String?
    )

    @Serializable
    private data class LoadPoster(
        val formats: List<String>,
        val sm: String,
        val aspectRatio: Double,
        @SerialName("hq") val hq: String
    )

    @Serializable
    private data class Banner(
        val formats: List<String>,
        val sm: String,
        val aspectRatio: Double,
        @SerialName("hq") val hq: String
    )

    @Serializable
    private data class EpisodeData(
        val slug: String,
        val title: String?,
        @SerialName("duration_ms") val durationMs: Long?,
        @SerialName("episode_number") val episodeNumber: Long,
        @SerialName("episode_string") val episodeString: String,
        val thumbnail: Thumbnail
    )

    @Serializable
    private data class Thumbnail(
        val formats: List<String>,
        val sm: String,
        val aspectRatio: Double,
        @SerialName("hq") val hq: String
    )

    @Serializable
    private data class ServersRes(
        @SerialName("servers") val servers: List<Server>?
    )

    @Serializable
    private data class Server(
        val name: String,
        @SerialName("short_name") val shortName: String?,
        val src: String
    )
}
