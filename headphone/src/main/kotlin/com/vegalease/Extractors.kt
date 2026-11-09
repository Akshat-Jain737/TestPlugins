package com.vegalease

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import java.net.URI
import com.lagradost.api.Log
import org.jsoup.Jsoup
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.selects.onTimeout
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap

fun getBaseUrl(url: String): String {
    try {
        return URI(url).let {
            "${it.scheme}://${it.host}"
        }
    } catch (e: Exception) {
        return url
    }
}

fun getIndexQuality(str: String?): Int {
    if (str.isNullOrBlank()) return Qualities.Unknown.value

    Regex("""(\d{3,4})[pP]""").find(str)?.groupValues?.getOrNull(1)?.toIntOrNull()?.let {
        return it
    }

    val lowerStr = str.lowercase()
    return when {
        lowerStr.contains("8k") -> 4320
        lowerStr.contains("4k") -> 2160
        lowerStr.contains("2k") -> 1440
        else -> Qualities.Unknown.value
    }
}

private const val DYNAMIC_URLS_JSON =
    "https://raw.githubusercontent.com/SaurabhKaperwan/Utils/refs/heads/main/urls.json"

private var dynamicUrlsCache: Map<String, String>? = null

suspend fun getLatestBaseUrl(baseUrl: String, source: String): String {
    val urls = dynamicUrlsCache ?: run {
        val fetched = try {
            app.get(DYNAMIC_URLS_JSON, timeout = 10L).parsedSafe<Map<String, String>>().orEmpty()
        } catch (e: Exception) {
            emptyMap()
        }
        if (fetched.isNotEmpty()) dynamicUrlsCache = fetched
        fetched
    }
    return urls[source]?.takeIf { it.isNotBlank() } ?: baseUrl
}

private const val RESOLVED_LINK_TTL_MS = 5 * 60 * 1000L
private val resolvedLinks = ConcurrentHashMap<String, Pair<Long, List<ExtractorLink>>>()
private val prefetchScope = CoroutineScope(Dispatchers.IO + SupervisorJob())

// Resolution is the slow part of pressing Play, so start it while the user is
// still looking at the movie page. Results land in resolvedLinks.
fun prefetchVCloudLinks(sources: List<String>) {
    val now = System.currentTimeMillis()
    sources.forEach { source ->
        val cached = resolvedLinks[source]
        if (cached != null && now - cached.first < RESOLVED_LINK_TTL_MS) return@forEach
        prefetchScope.launch {
            try {
                VCloud().getUrl(source, "", {}, {})
            } catch (e: Exception) {
                Log.d("Vega", "prefetch failed: ${e.message}")
            }
        }
    }
}

// vcloud responds anywhere from 0.5s to 30s for identical content, so a request
// that has gone quiet is usually bad luck rather than a slow server. Send a
// second one and keep whichever arrives first.
private suspend fun hedgedGet(url: String, hedgeAfterMs: Long = 3000L): String = coroutineScope {
    val first = async { app.get(url, timeout = 25L).text }
    select {
        first.onAwait { it }
        onTimeout(hedgeAfterMs) {
            val second = async { app.get(url, timeout = 25L).text }
            try {
                select {
                    first.onAwait { it }
                    second.onAwait { it }
                }
            } finally {
                first.cancel()
                second.cancel()
            }
        }
    }
}

open class VCloud : ExtractorApi() {
    override val name: String = "V-Cloud"
    override val mainUrl: String = "https://vcloud.*"
    override val requiresReferer = false

    fun extractPxlUrl(html: String): String? {
        val regex = Regex("""var\s+pxl\s*=\s*["']([^"']+)["']""")
        return regex.find(html)?.groupValues?.get(1)
    }

    fun extractDoubleAtob(html: String): String? {
        val regex = Regex("""var\s+url\s*=\s*atob\s*\(\s*atob\s*\(\s*['"]([^'"]+)['"]\s*\)\s*\)""")
        return regex.find(html)?.groupValues?.get(1)?.let { base64Decode(base64Decode(it)) }
    }

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        resolvedLinks[url]?.let { (resolvedAt, links) ->
            if (System.currentTimeMillis() - resolvedAt < RESOLVED_LINK_TTL_MS) {
                links.forEach(callback)
                return
            }
        }

        val collected = Collections.synchronizedList(mutableListOf<ExtractorLink>())
        val emit: (ExtractorLink) -> Unit = { collected.add(it); callback(it) }

        var baseUrl = getBaseUrl(url)

        val latestBaseUrl = if(url.contains("hubcloud")) {
            getLatestBaseUrl(baseUrl, "hubcloud")
        } else {
            getLatestBaseUrl(baseUrl, "vcloud")
        }

        var newUrl = url

        if(baseUrl != latestBaseUrl) {
            newUrl = url.replace(baseUrl, latestBaseUrl)
            baseUrl = latestBaseUrl
        }

        val doc = Jsoup.parse(hedgedGet(newUrl), baseUrl)

        var link = if(newUrl.contains("/video/")) {
            doc.selectFirst("div.vd > center > a") ?. attr("href") ?: ""
        }
        else {
            val scriptTag = doc.selectFirst("script:containsData(url)")?.toString() ?: ""

            if(newUrl.contains("vcloud")) {
                extractDoubleAtob(scriptTag) ?: ""
            } else {
                Regex("var url = '([^']*)'").find(scriptTag) ?. groupValues ?. get(1) ?: ""
            }
        }

        if(!link.startsWith("https://")) link = baseUrl + link

        val document = Jsoup.parse(hedgedGet(link), baseUrl)
        val header = document.select("div.card-header").text()
        val size = document.select("i#size").text()
        val quality = getIndexQuality(header)

        suspend fun myCallback( link: String, server: String = "") {
            emit(
                newExtractorLink(
                    "${name}${server}",
                    "${name}${server} ${header}[${size}]",
                    link,
                    ExtractorLinkType.VIDEO
                ) {
                    this.quality = quality
                }
            )
        }

        document.select("h2 a.btn").amap {
            val link = it.attr("href")
            val text = it.text()

            if (text.contains("FSL Server")) myCallback(link, "[FSL Server]")
            else if (text.contains("FSLv2")) myCallback(link, "[FSLv2 Server]")
            else if (text.contains("Mega Server")) myCallback(link, "[Mega Server]")
            else if (text.contains("Download File")) myCallback(link)
            else if (text.contains("BuzzServer")) {
                val dlink = app.get("$link/download", referer = link, allowRedirects = false).headers["hx-redirect"] ?: ""
                val baseUrl = getBaseUrl(link)
                if(dlink != "") myCallback( baseUrl + dlink, "[BuzzServer]")
            }
            else if (link.contains("pixeldra")) {
                val pixelLink = extractPxlUrl(document.toString()) ?: return@amap
                val baseUrlLink = getBaseUrl(pixelLink)
                val finalURL = if (pixelLink.contains("download", true)) pixelLink
                else "$baseUrlLink/api/file/${pixelLink.substringAfterLast("/")}?download"
                myCallback(finalURL, "[Pixeldrain]")
            }
            else if (text.contains("Gofile")) loadExtractor(link, "", subtitleCallback, emit)
            else { Log.d("Error", "No Server matched") }
        }

        if (collected.isNotEmpty()) {
            resolvedLinks[url] = System.currentTimeMillis() to collected.toList()
        }
    }
}
