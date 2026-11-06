package com.vegalease

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import org.jsoup.Jsoup
import org.jsoup.nodes.Element
import com.lagradost.cloudstream3.LoadResponse.Companion.addImdbUrl
import com.lagradost.cloudstream3.LoadResponse.Companion.addActors
import com.lagradost.cloudstream3.utils.AppUtils.parseJson
import com.lagradost.cloudstream3.utils.AppUtils.tryParseJson
import com.lagradost.api.Log
import java.net.URI
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

open class VegaMoviesProvider : MainAPI() {
    override var mainUrl = "https://vegamovies.lease"
    override var name = "VegaMovies"
    override val hasMainPage = true
    override var lang = "hi"
    override val hasDownloadSupport = true
    val cinemeta_url = "https://v3-cinemeta.strem.io/meta"
    private val vcloudHrefRegex = Regex("href=\"([^\"]*vcloud[^\"]*)\"", RegexOption.IGNORE_CASE)

    override val supportedTypes = setOf(
        TvType.Movie,
        TvType.TvSeries,
        TvType.AsianDrama,
        TvType.Anime
    )

    override val mainPage = mainPageOf(
        "$mainUrl/page/%d/" to "Home",
        "$mainUrl/category/web-series/netflix/page/%d/" to "Netflix",
        "$mainUrl/category/web-series/disney-plus-hotstar/page/%d/" to "Disney Plus Hotstar",
        "$mainUrl/category/web-series/amazon-prime-video/page/%d/" to "Amazon Prime"
    )

    override suspend fun getMainPage(
        page: Int,
        request: MainPageRequest
    ): HomePageResponse {
        val url = request.data.format(page)
        val now = System.currentTimeMillis()

        homeCache[url]?.let { (cachedAt, cached) ->
            if (now - cachedAt < HOME_CACHE_TTL_MS) {
                return newHomePageResponse(request.name, cached)
            }
        }

        val html = app.get(url, cacheTime = 10, cacheUnit = TimeUnit.MINUTES).text
        val document = Jsoup.parse(extractGrid(html) ?: html, mainUrl)
        val home = document.select("div.movies-grid > a").mapNotNull { it.toSearchResult() }
        if (home.isNotEmpty()) homeCache[url] = now to home
        return newHomePageResponse(request.name, home)
    }

    private fun extractGrid(html: String): String? {
        val marker = html.indexOf("class=\"movies-grid\"")
        if (marker < 0) return null
        val start = html.lastIndexOf("<div", marker)
        if (start < 0) return null

        var depth = 0
        var index = start
        while (index < html.length) {
            val open = html.indexOf("<div", index)
            val close = html.indexOf("</div>", index)
            if (close < 0) return null
            if (open in 0 until close) {
                depth++
                index = open + 4
            } else {
                depth--
                index = close + 6
                if (depth == 0) return html.substring(start, index)
            }
        }
        return null
    }

    private fun Element.toSearchResult(): SearchResponse? {
        val img = this.selectFirst("img")
        val title = (img?.attr("alt") ?: "").replace("Download ", "")
        if (title.contains("18+")) return null
        val href = this.attr("href")
        var posterUrl = img?.attr("src") ?: ""
        if (!posterUrl.contains("https:")) posterUrl = img?.attr("data-src") ?: ""

        return newMovieSearchResponse(title, URI(href).path, TvType.Movie) {
            this.posterUrl = posterUrl
        }
    }

    override suspend fun search(query: String, page: Int): SearchResponseList? {
        val json = app.get("$mainUrl/search.php?q=$query&page=$page").text
        val response = tryParseJson<VegaSearchResponse>(json) ?: return null
        val results = response.hits.mapNotNull { hit ->
            val doc = hit.document
            val title = doc.post_title.replace("Download ", "")
            if (title.contains("18+")) return@mapNotNull null
            newMovieSearchResponse(title, doc.permalink, TvType.Movie) {
                this.posterUrl = doc.post_thumbnail
            }
        }
        return newSearchResponseList(results)
    }

    override suspend fun load(url: String): LoadResponse? = coroutineScope {
        val document = app.get(fixUrl(url), timeout = 15L).document
        var title = document.select("title").text().replace("Download ", "")
        var posterUrl = document.select("p > img").attr("src")
        val imdbUrl = document.select("a[href*=\"imdb\"]").attr("href")
        val imdbId = imdbUrl.substringAfter("title/").substringBefore("/")

        val tvtype = if (
            document.selectFirst("h3:matches((?i)Series-SYNOPSIS/PLOT)") != null ||
            document.selectFirst("h3:matches((?i)Series Info)") != null ||
            document.selectFirst("h3:matches((?i)Series synopsis/PLOT)") != null
        ) {
            "series"
        } else {
            "movie"
        }

        val metaDeferred = async {
            tryParseJson<ResponseData>(
                app.get("$cinemeta_url/$tvtype/$imdbId.json", timeout = 10L).text
            )
        }

        var description = document
            .selectFirst("h3:has(span:matches((?i)SYNOPSIS/PLOT))")
            ?.nextElementSibling()
            ?.text()

        var cast: List<String> = emptyList()
        var genre: List<String> = emptyList()
        var imdbRating: String = ""
        var year: String = ""
        var background: String = posterUrl

        suspend fun awaitMeta(): ResponseData? {
            val responseData = metaDeferred.await()
            if (responseData != null) {
                description = responseData.meta.description ?: description
                cast = responseData.meta.cast ?: emptyList()
                title = responseData.meta.name ?: title
                genre = responseData.meta.genre ?: emptyList()
                imdbRating = responseData.meta.imdbRating ?: ""
                year = responseData.meta.year ?: ""
                posterUrl = responseData.meta.poster ?: posterUrl
                background = responseData.meta.background ?: background
            }
            return responseData
        }

        if (tvtype == "series") {
            val hTags = document.select("main > h3:matches((?i)(4K|[0-9]*0p)),main > h5:matches((?i)(4K|[0-9]*0p))")
                .filter { element -> !element.text().contains("Zip", true) }

            val tvSeriesEpisodes = mutableListOf<Episode>()
            val episodesMap: MutableMap<Pair<Int, Int>, MutableList<String>> = mutableMapOf()

            val scraped = hTags.amap { tag ->
                val realSeasonRegex = Regex("""(?:Season |S)(\d+)""")
                val realSeason = realSeasonRegex.find(tag.toString())?.groupValues?.get(1)?.toIntOrNull() ?: 0

                val pTag = tag.nextElementSibling()
                val aTags: List<Element> = if (pTag != null && pTag.tagName() == "p") {
                    pTag.select("a")
                } else {
                    tag.select("a")
                }

                val unilink = aTags.find {
                    it.text().contains("V-Cloud", ignoreCase = true) ||
                    it.text().contains("Episode", ignoreCase = true) ||
                    it.text().contains("Download", ignoreCase = true)
                } ?: aTags.find {
                    it.text().contains("G-Direct", ignoreCase = true)
                }

                val eurl = unilink?.attr("href")
                    ?: return@amap emptyList<Triple<Int, Int, String>>()

                app.get(eurl, timeout = 10L).document.select("p > a")
                    .map { it.attr("href") }
                    .filter { it.contains("vcloud", true) }
                    .mapIndexed { index, vcloudlink -> Triple(realSeason, index + 1, vcloudlink) }
            }.flatten()

            for ((season, episode, vcloudlink) in scraped) {
                episodesMap.getOrPut(Pair(season, episode)) { mutableListOf() }.add(vcloudlink)
            }

            val responseData = awaitMeta()

            for ((key, value) in episodesMap) {
                val episodeInfo = responseData?.meta?.videos?.find { it.season == key.first && it.episode == key.second }
                val data = value.map { source ->
                    EpisodeLink(source)
                }
                tvSeriesEpisodes.add(
                    newEpisode(data) {
                        this.name = episodeInfo?.name ?: episodeInfo?.title
                        this.season = key.first
                        this.episode = key.second
                        this.posterUrl = episodeInfo?.thumbnail
                        this.description = episodeInfo?.overview
                    }
                )
            }

            return@coroutineScope newTvSeriesLoadResponse(title, url, TvType.TvSeries, tvSeriesEpisodes) {
                this.posterUrl = posterUrl
                this.plot = description
                this.tags = genre
                this.score = Score.from10(imdbRating)
                this.year = year.toIntOrNull() ?: year.substringBefore("–").toIntOrNull()
                this.backgroundPosterUrl = background
                addActors(cast)
                addImdbUrl(imdbUrl)
            }
        } else {
            val buttons = document.select("a:has(button.dwd-button)")
            val data = buttons.amap { button ->
                val link = fixUrl(button.attr("href"))
                val html = app.get(link, timeout = 10L).text
                vcloudHrefRegex.find(html)?.groupValues?.get(1).orEmpty()
            }.filter { it.isNotBlank() }.map { EpisodeLink(it) }

            awaitMeta()

            return@coroutineScope newMovieLoadResponse(title, url, TvType.Movie, data) {
                this.posterUrl = posterUrl
                this.plot = description
                this.tags = genre
                this.score = Score.from10(imdbRating)
                this.year = year.toIntOrNull()
                this.backgroundPosterUrl = background
                addActors(cast)
                addImdbUrl(imdbUrl)
            }
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val sources = parseJson<ArrayList<EpisodeLink>>(data)
        sources.amap {
            val source = it.source
            if (source.contains("vcloud")) VCloud().getUrl(source, "", subtitleCallback, callback)
            else loadExtractor(source, "", subtitleCallback, callback)
        }
        return true
    }

    data class Meta(
        val id: String?,
        val imdb_id: String?,
        val type: String?,
        val poster: String?,
        val background: String?,
        val moviedb_id: Int?,
        val name: String?,
        val description: String?,
        val genre: List<String>?,
        val genres: List<String>?,
        val releaseInfo: String?,
        val status: String?,
        val runtime: String?,
        val cast: List<String>?,
        val language: String?,
        val country: String?,
        val imdbRating: String?,
        val year: String?,
        val videos: List<EpisodeDetails>?,
    )

    data class EpisodeDetails(
        val id: String?,
        val name: String?,
        val title: String?,
        val season: Int,
        val episode: Int,
        val released: String?,
        val firstAired: String?,
        val overview: String?,
        val thumbnail: String?,
        val moviedb_id: Int?,
        val imdb_id: String?,
        val imdbSeason: Int?,
        val imdbEpisode: Int?,
    )

    data class ResponseData(
        val meta: Meta,
    )

    data class EpisodeLink(
        val source: String
    )

    data class VegaSearchResponse(
        val hits: List<VegaHit>
    )

    data class VegaHit(
        val document: VegaDocument
    )

    data class VegaDocument(
        val id: String,
        val imdb_id: String?,
        val post_title: String,
        val permalink: String,
        val post_thumbnail: String
    )

    companion object {
        // The sites send Cache-Control: no-store, so OkHttp can never reuse a
        // response. Rows are cached here instead, shared by all providers.
        private const val HOME_CACHE_TTL_MS = 10 * 60 * 1000L
        private val homeCache = ConcurrentHashMap<String, Pair<Long, List<SearchResponse>>>()
    }
}
