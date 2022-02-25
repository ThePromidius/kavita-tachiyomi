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
    var LOG_TAG = "[Tachiyomi][Tracking][Kavita]"
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
                    prefApiKey = Injekt.get<Application>()
                        .getSharedPreferences("source_$sourceSuffixID", 0x0000)
                        .getString("APIKEY", "")!!
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
            "{}".toRequestBody("application/json; charset=utf-8".toMediaTypeOrNull())
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
        return "${url.split("/api/").first()}/api/Series/volumes?seriesId=${getIdFromUrl(url)}"
    }
    private fun getIdFromUrl(url: String): Int {
        /*Strips serie id from Url*/
        return url.split("/").last().toInt()
    }
    private fun SeriesDto.toTrack(): TrackSearch = TrackSearch.create(TrackManager.KAVITA).also {
        it.title = name
        it.summary = "this is the summary"
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
            for (volume in listVolumeDto) if (volume.chapters.maxOf { it.number.toInt() } == 0) volumeNumber++ else if (maxChapterNumber <volume.chapters.maxOf { it.number.toInt() }) maxChapterNumber = volume.chapters.maxOf { it.number.toInt() }

//            if (maxChapter == 0) {
//                maxChapter = listVolumeDto.size
//            }
            val returnMaxChapter = if (maxChapterNumber > volumeNumber) maxChapterNumber else volumeNumber
            return returnMaxChapter
        } catch (e: Exception) {
            logcat(LogPriority.WARN, e) { "Exception fetching Total Chapters\nRequest:$requestUrl" }
            throw e
        }
    }

//    private fun isValidKavitaVersion(): String {
    /*Might be implemented so that people don't come to support because wrong kavita version*/
//        val requestUrl = "$apiUrl/Server/server-info"
//        try {
//            val serverInfoDto = client.newCall(GET(requestUrl, headersBuilder().build()))
//                .execute()
//                .parseAs<ServerInfoDto>()
//
//
//            return serverInfoDto.kavitaVersion
//
//
//        } catch (e: Exception) {
//        logcat(LogPriority.WARN,e) { "exception in getTotalChapters\nRequest:$requestUrl" }
//            throw e
//        }
//    }

    private fun getLatestChapterRead(url: String): Float {
        /*Gets latest chapter read from remote tracking*/
        var requestUrl = "$apiUrl/Reader/continue-point?seriesId=${getIdFromUrl(url)}"
        val currentChapterDto: ChapterDto = try {
            client.newCall(GET(requestUrl, headersBuilder().build()))
                .execute().parseAs<ChapterDto>()
        } catch (e: Exception) {
            logcat(LogPriority.WARN, e) { "exception in currentChapterDto\nRequest:$requestUrl" }
            throw e
        }
        requestUrl = "$apiUrl/Reader/prev-chapter?seriesId=${getIdFromUrl(url)}&volumeId=${currentChapterDto.volumeId}&currentChapterId=${currentChapterDto.id}"
        val prevChapterId: Int = try {
            client.newCall(GET(requestUrl, headersBuilder().build()))
                .execute().parseAs<Int>()
        } catch (e: Exception) {
            logcat(LogPriority.WARN, e) { "[tachiyomi][Kavita]exception in prevChapterId\nRequest:$requestUrl" }
            throw e
        }
        if (prevChapterId == -1) {
            return (-1).toFloat()
        }
        requestUrl = "$apiUrl/Series/chapter?chapterId=$prevChapterId"
        val prevChapterDto: ChapterDto = try {
            client.newCall(GET(requestUrl, headersBuilder().build()))
                .execute().parseAs<ChapterDto>()
        } catch (e: Exception) {
            logcat(LogPriority.WARN, e) { "exception in prevChapterDto\nCould not get item\nRequest:$requestUrl" }
            throw e
        }
        var latestChapterRead = prevChapterDto.number.toFloat()
        // If prevChapterDto.number == "0", this is a volume and not a chapter. Encoding needed
        // We need volume number
        if (latestChapterRead == 0F) {
            val requestUrl = "$apiUrl/Series/volume?volumeId=${prevChapterDto.volumeId}"
            val prevChapterVolumeDto: VolumeDto = try {
                client.newCall(GET(requestUrl, headersBuilder().build()))
                    .execute().parseAs<VolumeDto>()
            } catch (e: Exception) {
                logcat(
                    LogPriority.WARN,
                    e
                ) { "exception in prevChapterDto\nCould not get item\nRequest:$requestUrl" }
                throw e
            }
            latestChapterRead = prevChapterVolumeDto.number.toFloat() / 100
        }

        return latestChapterRead
    }

    suspend fun getTrackSearch(url: String): TrackSearch =
        withIOContext {
            getCleanedApiUrl(url)
            getToken(url)
            try {
                val serieDto: SeriesDto =
                    client.newCall(GET(url, headersBuilder().build()),)
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
        val requestUrl = "$apiUrl/Reader/mark-chapter-until-as-read?seriesId=${getIdFromUrl(track.tracking_url)}&chapterNumber=${track.last_chapter_read}"
        client.newCall(POST(requestUrl, headersBuilder().build(), "{}".toRequestBody("application/json; charset=utf-8".toMediaTypeOrNull())))
            .await()
        return getTrackSearch(track.tracking_url)
    }
}
