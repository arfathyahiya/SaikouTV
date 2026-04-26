package ani.saikou.parsers.anime

import ani.saikou.asyncMap
import ani.saikou.client
import ani.saikou.FileUrl
import ani.saikou.loadData
import ani.saikou.others.JsUnpacker
import ani.saikou.parsers.*
import ani.saikou.tryWithSuspend
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlin.math.absoluteValue

class AnimePahe : AnimeParser() {

    companion object {
        private val cookieHeader = "Cookie" to "__ddg2_=1234567890"
    }

    override val hostUrl = "https://animepahe.pw"
    override val name = "AnimePahe"
    override val saveName = "animepahe_pw"
    override val isDubAvailableSeparately = false

    override suspend fun search(query: String): List<ShowResponse> {
        return tryWithSuspend {
            val resp = client.get(
                "$hostUrl/api?m=search&l=8&q=${encode(query)}",
                mapOf("referer" to hostUrl, cookieHeader)
            ).parsed<SearchQuery>()
            resp.data.map {
                ShowResponse(
                    it.title,
                    "$hostUrl/api?m=release&id=${it.session}&sort=episode_asc",
                    FileUrl(it.poster),
                    extra = mapOf("s" to it.session, "t" to System.currentTimeMillis().toString(), "n" to it.title)
                )
            }
        } ?: emptyList()
    }

    override suspend fun loadEpisodes(animeLink: String, extra: Map<String, String>?): List<Episode> {
        return tryWithSuspend {
            val session = extra?.get("s") ?: animeLink.substringAfter("&id=").substringBefore("&sort")
            val resp = client.get(
                "$hostUrl/api?m=release&id=$session&sort=episode_asc&page=1",
                mapOf(cookieHeader)
            ).parsed<ReleaseRouteResponse>()
            val lastPage = resp.lastPage
            val allData = mutableListOf<ReleaseRouteResponse.AnimeData>()
            allData.addAll(resp.data)
            if (lastPage > 1) {
                val pages = (2..lastPage).toList()
                pages.asyncMap { page ->
                    val pageResp = client.get(
                        "$hostUrl/api?m=release&id=$session&sort=episode_asc&page=$page",
                        mapOf(cookieHeader)
                    ).parsed<ReleaseRouteResponse>()
                    pageResp.data
                }.forEach { allData.addAll(it) }
            }
            allData.map { ep ->
                val epLink = "$hostUrl/play/$session/${ep.session}"
                Episode(
                    ep.episode.toString(),
                    epLink,
                    ep.title.ifEmpty { "Episode ${ep.episode}" },
                    FileUrl(ep.snapshot)
                )
            }
        } ?: emptyList()
    }

    private val qualityRegex = Regex("""(.+?)\s+·\s+(\d{3,4}p)""")

    override suspend fun loadVideoServers(episodeLink: String, extra: Any?): List<VideoServer> {
        return tryWithSuspend {
            val doc = client.get(episodeLink, mapOf(cookieHeader)).document
            val servers = mutableListOf<VideoServer>()

            doc.select("#resolutionMenu button").forEach { button ->
                val dubText = button.select("span").text().lowercase()
                val type = if ("eng" in dubText) "DUB" else "SUB"
                val text = button.text()
                val match = qualityRegex.find(text)
                val source = match?.groupValues?.getOrNull(1)?.trim() ?: "Unknown"
                val quality = match?.groupValues?.getOrNull(2)?.substringBefore("p")?.toIntOrNull() ?: 480
                val href = button.attr("data-src")
                if ("kwik" in href) {
                    servers.add(VideoServer(
                        name = "AnimePahe $source [$quality] [$type]",
                        embedUrl = href,
                        extraData = mapOf("quality" to quality, "referer" to hostUrl, "type" to type)
                    ))
                }
            }

            servers
        } ?: emptyList()
    }

    override suspend fun loadSavedShowResponse(mediaId: Int): ShowResponse? {
        return tryWithSuspend {
            val loaded = loadData<ShowResponse>("${saveName}_$mediaId")
            val timestamp = loaded?.extra?.get("t")?.toLongOrNull() ?: 0
            if ((timestamp - System.currentTimeMillis()).absoluteValue <= 600000) loaded else null
        }
    }

    override suspend fun getVideoExtractor(server: VideoServer): VideoExtractor = AnimePaheExtractor(server)

    class AnimePaheExtractor(override val server: VideoServer) : VideoExtractor() {

        private val data = server.extraData as Map<*, *>
        private val quality = data["quality"] as Int
        private val ref = data["referer"] as String

        private val redirectRegex = Regex("""\$\("a\.redirect"\)\.attr\("href","(https?://[^"]+)"""")
        private val paramRegex = Regex("""\("(\w+)",\d+,"(\w+)",(\d+),(\d+),\d+\)""")
        private val urlRegex = Regex("action=\"([^\"]+)\"")
        private val tokenRegex = Regex("value=\"([^\"]+)\"")

        private fun decrypt(fullString: String, key: String, v1: Int, v2: Int): String {
            val keyIndexMap = key.withIndex().associate { it.value to it.index }
            val sb = StringBuilder()
            var i = 0
            val toFind = key[v2]
            while (i < fullString.length) {
                val nextIndex = fullString.indexOf(toFind, i)
                val decodedCharStr = buildString {
                    for (j in i until nextIndex) append(keyIndexMap[fullString[j]] ?: -1)
                }
                i = nextIndex + 1
                sb.append((decodedCharStr.toInt(v2) - v1).toChar())
            }
            return sb.toString()
        }

        private fun getAndUnpack(string: String): String {
            val packed = Regex("""eval\(function\(p,a,c,k,e,.*\)\)""").find(string)?.value
            return JsUnpacker(packed).unpack() ?: string
        }

        override suspend fun extract(): VideoContainer {
            return tryWithSuspend {
                val embedUrl = server.embed.url

                if ("kwik" in embedUrl) {
                    // Kwik: deobfuscate JS script + regex for m3u8 (primary)
                    val doc = client.get(embedUrl, referer = ref).document
                    val script = doc.selectFirst("script:containsData(function(p,a,c,k,e,d))")?.data()
                    if (script != null) {
                        val unpacked = getAndUnpack(script)
                        val m3u8 = Regex("source\\s*=\\s*'([^']*m3u8[^']*)'").find(unpacked)?.groupValues?.getOrNull(1)
                        if (m3u8 != null) {
                            return@tryWithSuspend VideoContainer(listOf(Video(quality, VideoType.M3U8, FileUrl(m3u8, mapOf("referer" to "https://kwik.cx/")))))
                        }
                    }
                    // Fallback: old decrypt path
                    val resp = client.get(embedUrl, referer = ref).text
                    val kwikLink = redirectRegex.find(resp)?.groupValues?.getOrNull(1) ?: return@tryWithSuspend VideoContainer(emptyList())
                    val kwikRes = client.get(kwikLink)
                    val cookiesList = kwikRes.headers.toMultimap()["set-cookie"] ?: return@tryWithSuspend VideoContainer(emptyList())
                    val cookies = cookiesList.firstOrNull() ?: return@tryWithSuspend VideoContainer(emptyList())
                    val paramMatch = paramRegex.find(kwikRes.text) ?: return@tryWithSuspend VideoContainer(emptyList())
                    val fullKey = paramMatch.groupValues[1]
                    val key = paramMatch.groupValues[2]
                    val v1 = paramMatch.groupValues[3].toInt()
                    val v2 = paramMatch.groupValues[4].toInt()
                    val decrypted = decrypt(fullKey, key, v1, v2)
                    val postUrl = urlRegex.find(decrypted)?.groupValues?.getOrNull(1) ?: return@tryWithSuspend VideoContainer(emptyList())
                    val token = tokenRegex.find(decrypted)?.groupValues?.getOrNull(1) ?: return@tryWithSuspend VideoContainer(emptyList())
                    val mp4Url = client.post(
                        postUrl,
                        mapOf("referer" to kwikLink, "cookie" to cookies),
                        data = mapOf("_token" to token),
                        allowRedirects = false
                    ).headers["location"] ?: return@tryWithSuspend VideoContainer(emptyList())
                    VideoContainer(
                        listOf(Video(quality, VideoType.CONTAINER, FileUrl(mp4Url)))
                    )
                } else {
                    // Pahe direct: follow redirect to kwik → extract params → decrypt → POST with retry
                    val kwikRes = client.get("$embedUrl/i", referer = "https://pahe.win/")
                    val kwikUrl = kwikRes.headers["location"]?.split("https://")?.lastOrNull() ?: return@tryWithSuspend VideoContainer(emptyList())
                    val kwikPage = client.get(kwikUrl, referer = "https://kwik.cx/")
                    val kwikText = kwikPage.text
                    val cookiesList = kwikPage.headers.toMultimap()["set-cookie"] ?: return@tryWithSuspend VideoContainer(emptyList())
                    val cookies = cookiesList.firstOrNull() ?: return@tryWithSuspend VideoContainer(emptyList())
                    val paramMatch = paramRegex.find(kwikText) ?: return@tryWithSuspend VideoContainer(emptyList())
                    val fullKey = paramMatch.groupValues[1]
                    val key = paramMatch.groupValues[2]
                    val v1 = paramMatch.groupValues[3].toInt()
                    val v2 = paramMatch.groupValues[4].toInt()
                    val decrypted = decrypt(fullKey, key, v1, v2)
                    val postUrl = urlRegex.find(decrypted)?.groupValues?.getOrNull(1) ?: return@tryWithSuspend VideoContainer(emptyList())
                    val token = tokenRegex.find(decrypted)?.groupValues?.getOrNull(1) ?: return@tryWithSuspend VideoContainer(emptyList())
                    // Retry loop for 419 CSRF
                    var finalUrl: String? = null
                    repeat(20) {
                        finalUrl = client.post(
                            postUrl,
                            mapOf("referer" to kwikUrl, "cookie" to cookies, "user-agent" to "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0.0.0 Safari/537.36"),
                            data = mapOf("_token" to token),
                            allowRedirects = false
                        ).headers["location"]
                        if (finalUrl != null) return@repeat
                    }
                    if (finalUrl != null) {
                        VideoContainer(listOf(Video(quality, VideoType.M3U8, FileUrl(finalUrl, mapOf("referer" to "https://kwik.cx/")))))
                    } else {
                        VideoContainer(emptyList())
                    }
                }
            } ?: VideoContainer(emptyList())
        }
    }

    @Serializable
    private data class SearchQuery(
        @SerialName("data") val data: List<SearchQueryData>
    ) {
        @Serializable
        data class SearchQueryData(
            @SerialName("title") val title: String,
            @SerialName("poster") val poster: String,
            @SerialName("session") val session: String,
        )
    }

    @Serializable
    private data class ReleaseRouteResponse(
        @SerialName("last_page") val lastPage: Int,
        @SerialName("data") val data: List<AnimeData>
    ) {
        @Serializable
        data class AnimeData(
            @SerialName("episode") val episode: Int,
            @SerialName("anime_id") val anime_id: Int,
            @SerialName("title") val title: String,
            @SerialName("snapshot") val snapshot: String,
            @SerialName("session") val session: String,
            @SerialName("filler") val filler: Int,
        )
    }
}
