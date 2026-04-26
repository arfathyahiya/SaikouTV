package ani.saikou.parsers.anime.extractors

import ani.saikou.FileUrl
import ani.saikou.Mapper
import ani.saikou.client
import ani.saikou.parsers.Subtitle
import ani.saikou.parsers.SubtitleType
import ani.saikou.parsers.Video
import ani.saikou.parsers.VideoContainer
import ani.saikou.parsers.VideoExtractor
import ani.saikou.parsers.VideoServer
import ani.saikou.parsers.VideoType
import ani.saikou.tryWithSuspend
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.intOrNull
import org.jsoup.Jsoup

class KickAssVidStreaming(override val server: VideoServer) : VideoExtractor() {
    override suspend fun extract() = KickAssExtractorCore.extract(server)
}

/**
 * Extracts HLS stream + subtitles from KickassAnime embed pages.
 *
 * The embed page (krussdomi.com) renders an <astro-island> tag whose
 * `props` attribute carries all data in a simple "devalue" encoding:
 *
 *   [0, <value>]         → literal  (String / Number / Boolean / null / Object)
 *   [1, [item, ...]]     → array of devalue-encoded items
 *
 * Real props JSON (after Jsoup HTML-entity decoding):
 * {
 *   "manifest"  : [0, "https://hls.krussdomi.com/manifest/<id>/master.m3u8"],
 *   "subtitles" : [1, [[0, {"language":[0,"eng"],"name":[0,"English"],"src":[0,"https://…/en.vtt"]}]]],
 *   ...
 * }
 *
 * We parse with JsonElement (never Map<String, Any?>) to avoid the
 * "Serializer for class 'Any' is not found" crash from kotlinx.serialization.
 */
object KickAssExtractorCore {

    private const val UA =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
                "(KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36"

    // ── Devalue decoder ──────────────────────────────────────────────────────

    /**
     * Decode a single devalue node (a JsonArray of [type, payload]).
     *
     *   [0, value]       → literal; if value is a JsonObject decode its entries too
     *   [1, [...items]]  → List of decoded items
     *
     * Returns plain Kotlin types: String, Double, Boolean, null,
     * Map<String, Any?>, or List<Any?>.
     */
    private fun decodeDevalue(node: JsonArray): Any? {
        if (node.size < 2) return null
        val type = (node[0] as? JsonPrimitive)?.intOrNull ?: return null
        return when (type) {
            0 -> when (val payload = node[1]) {
                is JsonObject    -> payload.entries.associate { (k, v) ->
                    k to ((v as? JsonArray)?.let(::decodeDevalue))
                }
                is JsonPrimitive -> when {
                    payload is JsonNull  -> null
                    payload.isString     -> payload.content
                    else                 -> payload.content.toBooleanStrictOrNull()
                        ?: payload.content.toDoubleOrNull()
                        ?: payload.content
                }
                is JsonNull      -> null
                else             -> null
            }
            1 -> (node[1] as? JsonArray)?.map { item ->
                (item as? JsonArray)?.let(::decodeDevalue)
            }
            else -> null
        }
    }

    // ── Helpers ──────────────────────────────────────────────────────────────

    private fun baseUrl(url: String) =
        java.net.URI(url).let { "${it.scheme}://${it.host}" }

    private fun fixUrl(url: String, host: String) = when {
        url.startsWith("//")   -> "https:$url"
        url.startsWith("/")    -> "$host$url"
        url.startsWith("http") -> url
        else                   -> "$host/$url"
    }

    private fun cdnHeaders(host: String) = mapOf(
        "Referer" to host,
        "Origin"  to host,
    )

    // ── Extraction ───────────────────────────────────────────────────────────

    suspend fun extract(server: VideoServer): VideoContainer {
        val videos    = mutableListOf<Video>()
        val subtitles = mutableListOf<Subtitle>()

        val embedUrl = server.embed.url
        val host     = baseUrl(embedUrl)

        val html = tryWithSuspend {
            client.get(embedUrl, headers = mapOf(
                "User-Agent" to UA,
                "Referer"    to host,
                "Origin"     to host,
            )).text
        } ?: return VideoContainer(videos, subtitles)

        // ── Find <astro-island> with props containing "manifest" ─────────────
        // Jsoup.attr() already decodes HTML entities (&quot; → "), so the
        // returned string is ready JSON — no manual replacement needed.
        val propsJson = Jsoup.parse(html)
            .select("astro-island")
            .firstOrNull { it.attr("props").contains("\"manifest\"") }
            ?.attr("props")
            ?.takeIf { it.isNotBlank() }
            ?: return VideoContainer(videos, subtitles)

        // ── Parse props as JsonObject, then devalue-decode each top-level key ─
        // Mapper.json is the app's configured Json instance.
        // We use JsonElement types throughout — never Map<String, Any?> —
        // to avoid kotlinx.serialization's "Serializer for 'Any' not found" crash.
        val propsObj = tryWithSuspend {
            Mapper.json.parseToJsonElement(propsJson) as? JsonObject
        } ?: return VideoContainer(videos, subtitles)

        // Each top-level value is itself a devalue node (a JsonArray).
        val props: Map<String, Any?> = propsObj.entries.associate { (k, v) ->
            k to ((v as? JsonArray)?.let(::decodeDevalue))
        }

        // ── manifest → HLS URL (decoded to String) ───────────────────────────
        (props["manifest"] as? String)?.takeIf { it.isNotBlank() }?.let { raw ->
            videos.add(
                Video(
                    null,
                    VideoType.M3U8,
                    FileUrl(fixUrl(raw, host), cdnHeaders(host)),
                )
            )
        }

        // ── subtitles → List<Map<String, Any?>> ──────────────────────────────
        // Decoded shape: List of Maps with keys "language", "name", "src"
        @Suppress("UNCHECKED_CAST")
        (props["subtitles"] as? List<*>)?.forEach { item ->
            val sub  = item as? Map<String, Any?> ?: return@forEach
            val src  = (sub["src"]      as? String)?.takeIf { it.isNotBlank() } ?: return@forEach
            val name = (sub["name"]     as? String)?.takeIf { it.isNotBlank() }
                ?: (sub["language"] as? String)?.takeIf { it.isNotBlank() }
                ?: return@forEach
            subtitles.add(
                Subtitle(
                    language = name,
                    url      = FileUrl(fixUrl(src, host), cdnHeaders(host)),
                    type = SubtitleType.VTT
                )
            )
        }

        return VideoContainer(videos, subtitles)
    }
}