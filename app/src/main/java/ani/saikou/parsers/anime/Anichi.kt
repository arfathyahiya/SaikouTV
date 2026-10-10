package ani.saikou.parsers.anime

import ani.saikou.FileUrl
import ani.saikou.Mapper
import ani.saikou.client
import ani.saikou.parsers.AnimeParser
import ani.saikou.parsers.Episode
import ani.saikou.parsers.ShowResponse
import ani.saikou.parsers.Subtitle
import ani.saikou.parsers.SubtitleType
import ani.saikou.parsers.Video
import ani.saikou.parsers.VideoContainer
import ani.saikou.parsers.VideoExtractor
import ani.saikou.parsers.VideoServer
import ani.saikou.parsers.VideoType
import ani.saikou.tryWithSuspend
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.jsoup.Jsoup
import java.time.Instant
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

class Anichi : AnimeParser() {

    override val name = "Anichi"
    override val saveName = "anichi"
    override val hostUrl = "https://anichi.to"
    override val isDubAvailableSeparately = false

    private companion object {
        const val AJAX = "https://anichi.to/ajax"
        // Every /ajax/* endpoint on the site needs this XHR header; the referer is set per-call.
        val ajaxHeaders = mapOf(
            "X-Requested-With" to "XMLHttpRequest",
            "Accept" to "application/json, text/javascript, */*; q=0.01",
        )
    }

    override suspend fun search(query: String): List<ShowResponse> = tryWithSuspend {
        val html = client.get(
            "$AJAX/anime/search?keyword=${encode(query)}",
            headers = ajaxHeaders,
            referer = "$hostUrl/home",
        ).parsed<SearchResult>()?.result?.html ?: return@tryWithSuspend emptyList()

        Jsoup.parse(html).select("a.aitem").mapNotNull { a ->
            val name = a.selectFirst(".title")?.text() ?: return@mapNotNull null
            val animeId = a.attr("data-tip").ifEmpty { return@mapNotNull null }
            val link = a.attr("href").let { if (it.startsWith("/")) "$hostUrl$it" else it }
            val cover = a.selectFirst("img")?.attr("src").orEmpty()
            val jp = a.selectFirst(".d-title")?.attr("data-jp")
            val en = a.selectFirst(".d-title")?.attr("data-en")
            ShowResponse(
                name = name,
                link = link,
                coverUrl = FileUrl(cover),
                otherNames = listOfNotNull(jp, en).filter { it != name }.distinct(),
                extra = mapOf("animeId" to animeId),
            )
        }
    }.orEmpty()

    override suspend fun loadEpisodes(animeLink: String, extra: Map<String, String>?): List<Episode> {
        val animeId = extra?.get("animeId") ?: return emptyList()
        val slug = animeLink.substringAfterLast("/anime/", animeLink)

        val html = tryWithSuspend {
            client.get(
                "$AJAX/episode/list/$animeId?style=&vrf=$animeId",
                headers = ajaxHeaders,
                referer = animeLink,
            ).parsed<HtmlResult>()?.result
        } ?: return emptyList()

        return Jsoup.parse(html).select("div.episodes a[data-ids]").mapNotNull { a ->
            val num = a.attr("data-num").ifEmpty { return@mapNotNull null }
            val dataIds = a.attr("data-ids").ifEmpty { return@mapNotNull null }
            val title = a.selectFirst(".d-title")?.text()
            Episode(
                number = num,
                link = "$hostUrl/watch/$slug/ep-$num",
                title = title,
                extra = EpData(
                    animeId = animeId,
                    dataIds = dataIds,
                    sub = a.attr("data-sub").toIntOrNull() ?: 0,
                    dub = a.attr("data-dub").toIntOrNull() ?: 0,
                ),
            )
        }
    }

    override suspend fun loadVideoServers(episodeLink: String, extra: Any?): List<VideoServer> {
        val ep = extra as? EpData ?: return emptyList()
        val html = tryWithSuspend {
            client.get(
                "$AJAX/server/list?servers=${encode(ep.dataIds)}",
                headers = ajaxHeaders,
                referer = episodeLink,
            ).parsed<HtmlResult>()?.result
        } ?: return emptyList()

        val doc = Jsoup.parse(html)
        return doc.select("[data-type] li[data-link-id]").mapNotNull { li ->
            val name = li.text().ifEmpty { return@mapNotNull null }
            val linkId = li.attr("data-link-id").ifEmpty { return@mapNotNull null }
            val groupType = li.parents().select("[data-type]").firstOrNull()?.attr("data-type") ?: "sub"
            VideoServer(
                name = "$name (${groupType.uppercase()})",
                embed = FileUrl(""),
                extraData = ServerData(
                    linkId = linkId,
                    sub = groupType == "sub",
                    dub = groupType == "dub",
                ),
            )
        }
    }

    override suspend fun getVideoExtractor(server: VideoServer): VideoExtractor? {
        return AnichiExtractor(server)
    }
}

@Serializable
data class EpData(
    val animeId: String,
    val dataIds: String,
    val sub: Int = 0,
    val dub: Int = 0,
)

@Serializable
data class ServerData(
    val linkId: String,
    val sub: Boolean = true,
    val dub: Boolean = false,
)

@Serializable
private data class SearchResult(val result: Result = Result()) {
    @Serializable
    data class Result(val html: String? = null)
}

@Serializable
private data class HtmlResult(val result: String? = null)

@Serializable
private data class ServerResolve(val result: Resolved? = null) {
    @Serializable
    data class Resolved(val url: String? = null)
}

/**
 * Resolves an Anichi server's encrypted link id to a MegaPlay embed, then extracts the
 * HLS stream (AES-CBC + HMAC-signed token) and subtitles.
 */
class AnichiExtractor(override val server: VideoServer) : VideoExtractor() {

    private companion object {
        const val HOST = "https://anichi.to"
        const val MEGAPLAY = "https://megaplay.buzz"
        const val AES_KEY_MAT = "i?LMTAx0Q6,:}50U"
        const val AES_IV_MAT = "W0;27ToaUpl_P%'c"
        const val HMAC_KEY = "MpCdnT0k3n!9f2K#xQ7vL5mR8wN1pY4s"
        const val TOKEN_TTL_SECONDS = 90L

        val ajaxHeaders = mapOf("X-Requested-With" to "XMLHttpRequest")

        fun padBytes(src: ByteArray, size: Int): ByteArray {
            val out = ByteArray(size)
            System.arraycopy(src, 0, out, 0, minOf(size, src.size))
            return out
        }

        fun decodeUrlBase64(s: String): ByteArray {
            val normalized = s.replace('-', '+').replace('_', '/')
            return Base64.getDecoder().decode(normalized.padEnd(normalized.length + (4 - normalized.length % 4) % 4, '='))
        }

        fun encodeUrlBase64(b: ByteArray): String =
            Base64.getUrlEncoder().withoutPadding().encodeToString(b)

        fun urlEncode(s: String): String =
            java.net.URLEncoder.encode(s, "utf-8").replace("+", "%20")
    }

    override suspend fun extract(): VideoContainer {
        val sd = server.extraData as? ServerData ?: return VideoContainer(emptyList())
        val resolved = tryWithSuspend {
            client.get(
                "$HOST/ajax/server?get=${urlEncode(sd.linkId)}",
                headers = ajaxHeaders,
                referer = HOST,
            ).parsed<ServerResolve>()?.result?.url
        } ?: return VideoContainer(emptyList())

        val embedHtml = tryWithSuspend { client.get(resolved, referer = "$HOST/").text }
            ?: return VideoContainer(emptyList())
        val fileId = Jsoup.parse(embedHtml).selectFirst("#megaplay-player")?.attr("data-id")
            ?: return VideoContainer(emptyList())
        if (fileId.isBlank()) return VideoContainer(emptyList())

        val sources = tryWithSuspend {
            client.get(
                "$MEGAPLAY/stream/getSources?id=$fileId&platform=OTHER",
                referer = resolved,
            ).parsed<Sources>()
        } ?: return VideoContainer(emptyList())

        val subtitles = sources.tracks.mapNotNull { t ->
            val file = t.file ?: return@mapNotNull null
            val label = t.label ?: "English"
            try {
                Subtitle(label, FileUrl(file, mapOf("Referer" to "$MEGAPLAY/")), SubtitleType.VTT)
            } catch (_: Exception) {
                null
            }
        }

        val m3u8 = decryptStreamUrl(sources.enc) ?: return VideoContainer(emptyList(), subtitles)
        val signed = signStreamUrl(m3u8)

        return VideoContainer(
            videos = listOf(
                Video(
                    quality = null,
                    format = VideoType.M3U8,
                    url = FileUrl(signed, mapOf("Referer" to "$MEGAPLAY/")),
                ),
            ),
            subtitles = subtitles,
        )
    }

    private fun decryptStreamUrl(enc: String?): String? {
        if (enc.isNullOrEmpty()) return null
        return try {
            val key = SecretKeySpec(padBytes(AES_KEY_MAT.toByteArray(), 32), "AES")
            val iv = IvParameterSpec(padBytes(AES_IV_MAT.toByteArray(), 16))
            val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
            cipher.init(Cipher.DECRYPT_MODE, key, iv)
            val plain = String(cipher.doFinal(decodeUrlBase64(enc)))
            val json = Mapper.json.parseToJsonElement(plain)
            (json as? JsonObject)?.get("file")?.jsonPrimitive?.content ?: return null
        } catch (_: Exception) {
            null
        }
    }

    private fun signStreamUrl(url: String): String {
        val match = Regex("/([a-f0-9]{32})/([a-f0-9]{32})/", RegexOption.IGNORE_CASE)
            .find(url)
            ?: return url
        val pathKey = match.groupValues[1].lowercase() + "/" + match.groupValues[2].lowercase()
        val exp = Instant.now().epochSecond + TOKEN_TTL_SECONDS
        val payload = "$exp|$pathKey".toByteArray()

        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(HMAC_KEY.toByteArray(), "HmacSHA256"))
        val sig = mac.doFinal(payload)

        val token = encodeUrlBase64(payload) + "." + encodeUrlBase64(sig)
        val sep = if (url.contains("?")) "&" else "?"
        return "$url${sep}token=$token"
    }
}

@Serializable
private data class Sources(
    val tracks: List<Track> = emptyList(),
    val enc: String? = null,
) {
    @Serializable
    data class Track(
        val file: String? = null,
        val label: String? = null,
        val kind: String? = null,
    )
}
