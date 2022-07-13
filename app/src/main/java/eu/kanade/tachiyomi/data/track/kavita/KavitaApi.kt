package eu.kanade.tachiyomi.data.track.kavita

import android.app.Application
import android.content.SharedPreferences
import eu.kanade.tachiyomi.BuildConfig
import eu.kanade.tachiyomi.data.database.models.Track
import eu.kanade.tachiyomi.data.track.TrackManager
import eu.kanade.tachiyomi.data.track.model.TrackSearch
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.POST
import eu.kanade.tachiyomi.network.await
import eu.kanade.tachiyomi.network.parseAs
import eu.kanade.tachiyomi.util.lang.withIOContext
import eu.kanade.tachiyomi.util.system.logcat
import logcat.LogPriority
import okhttp3.Headers
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.OkHttpClient
import okhttp3.RequestBody.Companion.toRequestBody
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.security.MessageDigest

class KavitaApi(private val client: OkHttpClient) {
    var jwtToken = ""
    private fun headersBuilder(): Headers.Builder {
        return Headers.Builder()
            .add("User-Agent", "Tachiyomi Kavita v${BuildConfig.VERSION_NAME}")
            .add("Content-Type", "application/json")
            .add("Authorization", "Bearer $jwtToken")
    }

    private fun getKavitaPreferencesApiKey(apiUrl: String): String {
        var prefApiKey = ""
        for (sourceId in 1..3) {
            val sourceSuffixID by lazy {
                val key = "${"kavita_$sourceId"}/all/1" // Hardcoded versionID to 1
                val bytes = MessageDigest.getInstance("MD5").digest(key.toByteArray())
                (0..7).map { bytes[it].toLong() and 0xff shl 8 * (7 - it) }
                    .reduce(Long::or) and Long.MAX_VALUE
            }
            val preferences: SharedPreferences by lazy {
                Injekt.get<Application>().getSharedPreferences("source_$sourceSuffixID", 0x0000)
            }
            val prefApiUrl = preferences.getString("APIURL", "")!!

            if (prefApiUrl.isNotEmpty()) {
                if (prefApiUrl == getCleanedApiUrl(apiUrl)) {
                    prefApiKey = preferences.getString("APIKEY", "")!!
                    break
                }
            }
        }
        return prefApiKey
    }

    var apiUrl = ""
    private fun getCleanedApiUrl(url: String): String {
        /*Returns "<kavita_IP-Domain>/api/" from given url,*/
        apiUrl = "${url.split("/api/").first()}/api"
        return apiUrl
    }
    
    private fun getToken(url: String) {
        /*
         * Uses url to compare against each source APIURL's to get the correct custom source preference.
         * Now having source preference we can do getString("APIKEY")
         * Authenticates to get the token
         * Saves the token in the var jwtToken
         */
        val cleanedApiUrl = apiUrl
        val apiKey = getKavitaPreferencesApiKey(url)
        if (apiKey.isEmpty()) {
            logcat(LogPriority.WARN) { "Could not get api key" }
            throw Exception("Could not load Api key")
        }
        val request = POST(
            "$cleanedApiUrl/Plugin/authenticate?apiKey=$apiKey&pluginName=Tachiyomi-Kavita",
            headersBuilder().build(),
            "{}".toRequestBody("application/json; charset=utf-8".toMediaTypeOrNull()),
        )
        client.newCall(request).execute().use {
            if (it.code == 200) {
                jwtToken = it.parseAs<AuthenticationDto>().token
            }
            if (it.code == 401) {
                logcat(LogPriority.WARN) { "Unauthorized / api key not valid:\nCleaned api URL:${cleanedApiUrl}\nApi key is empty:${apiKey.isEmpty()}\n" }
                throw Exception("Unauthorized / api key not valid")
            }
            if (it.code == 500) {
                logcat(LogPriority.WARN) { "Error fetching jwd token:\nCleaned api URL:${cleanedApiUrl}\nApi key is empty:${apiKey.isEmpty()}\n" }
                throw Exception("Error fetching jwd token")
            }
        }
    }
    
    private fun getApiVolumesUrl(url: String): String {
        return "${getCleanedApiUrl(url)}/Series/volumes?seriesId=${getIdFromUrl(url)}"
    }
    
    private fun getIdFromUrl(url: String): Int {
        /*Strips serie id from Url*/
        return url.substringAfterLast("/").toInt()
    }
    
    private fun SeriesDto.toTrack(): TrackSearch = TrackSearch.create(TrackManager.KAVITA).also {
        it.title = name
        it.summary = ""
    }
    
    private fun getTotalChapters(url: String): Int {
        /*Returns total chapters in the series.
         * Ignores volumes.
         * Volumes consisting of 1 file treated as chapter
         */
        val requestUrl = getApiVolumesUrl(url)
        try {
            val listVolumeDto = client.newCall(GET(requestUrl, headersBuilder().build()))
                .execute()
                .parseAs<List<VolumeDto>>()
            var volumeNumber = 0
            var maxChapterNumber = 0
            for (volume in listVolumeDto) {
                if (volume.chapters.maxOf { it.number!!.toFloat() } == 0f) {
                    volumeNumber++
                } else if (maxChapterNumber < volume.chapters.maxOf { it.number!!.toFloat() }) {
                    maxChapterNumber = volume.chapters.maxOf { it.number!!.toFloat().toInt() }
                }
            }

            return if (maxChapterNumber > volumeNumber) maxChapterNumber else volumeNumber
        } catch (e: Exception) {
            logcat(LogPriority.WARN, e) { "Exception fetching Total Chapters\nRequest:$requestUrl" }
            throw e
        }
    }

    private fun getLatestChapterRead(url: String): Float {
        val serieId = getIdFromUrl(url)
        val requestUrl = "$apiUrl/Tachiyomi/latest-chapter?seriesId=$serieId"
        try {
            client.newCall(GET(requestUrl, headersBuilder().build()))
                .execute().use {
                    if (it.code == 200) {
                        return it.parseAs<ChapterDto>().number!!.replace(",", ".").toFloat()
                    }
                    if (it.code == 204) {
                        return 0F
                    }
                }
        } catch (e: Exception) {
            logcat(LogPriority.WARN, e,) { "exception in latest-chapter\nCould not get item\nRequest:$requestUrl" }
            throw e
        }
        return 0F
    }

    suspend fun getTrackSearch(url: String): TrackSearch =
        withIOContext {
            getCleanedApiUrl(url)
            getToken(url)
            try {
                val serieDto: SeriesDto =
                    client.newCall(GET(url, headersBuilder().build()))
                        .await()
                        .parseAs<SeriesDto>()

                val track = serieDto.toTrack()

                track.apply {
                    cover_url = serieDto.thumbnail_url.toString()
                    tracking_url = url
                    total_chapters = getTotalChapters(url)

                    title = serieDto.name
                    status = when (serieDto.pagesRead) {
                        serieDto.pages -> Kavita.COMPLETED
                        0 -> Kavita.UNREAD
                        else -> Kavita.READING
                    }
                    last_chapter_read = getLatestChapterRead(url)
                }
            } catch (e: Exception) {
                logcat(LogPriority.WARN, e) { "Could not get item: $url" }
                throw e
            }
        }

    suspend fun updateProgress(track: Track): Track {
        val requestUrl = "$apiUrl/Tachiyomi/mark-chapter-until-as-read?seriesId=${getIdFromUrl(track.tracking_url)}&chapterNumber=${track.last_chapter_read}"
        client.newCall(POST(requestUrl, headersBuilder().build(), "{}".toRequestBody("application/json; charset=utf-8".toMediaTypeOrNull())))
            .await()
        return getTrackSearch(track.tracking_url)
    }
}
