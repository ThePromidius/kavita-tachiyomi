package eu.kanade.tachiyomi.data.track.kavita

import android.app.Application
import android.content.SharedPreferences
import android.util.Log
import eu.kanade.tachiyomi.BuildConfig
import eu.kanade.tachiyomi.data.database.models.Track
import eu.kanade.tachiyomi.data.track.TrackManager
import eu.kanade.tachiyomi.data.track.model.TrackSearch
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.POST
import eu.kanade.tachiyomi.network.await
import eu.kanade.tachiyomi.network.parseAs
import eu.kanade.tachiyomi.util.lang.withIOContext
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

//    var LOG_TAG = "tachiyomi "
    private fun getKavitaPreferencesApiKey(apiUrl: String): String {
        var prefApiKey = ""

//        var myUri = Uri.parse(apiUrl)
//        var sourceId = myUri.getQueryParameter("sourceID")

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
            val LOG_TAG = "[Tachiyomi][Tracking][Kavita]_${preferences.getString("customSourceName",sourceSuffixID.toString())!!.replace(' ','_')}"

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
        apiUrl = "${url.split("/api/").first()}/api"
        return apiUrl
    }
    private fun getToken(url: String) {
        val cleanedApiUrl = apiUrl
        val apiKey = getKavitaPreferencesApiKey(url)
        if (apiKey.isEmpty()) {
            Log.e(LOG_TAG, "Could not get api key")
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
                Log.e(LOG_TAG, "Unauthorized / api key not valid:\nCleaned api URL:${cleanedApiUrl}\nApi key is empty:${apiKey.isEmpty()}\n")
                throw Exception("Unauthorized / api key not valid")
            }
            if (it.code == 500) {
                Log.e(LOG_TAG, "Error fetching jwd token:\nCleaned api URL:${cleanedApiUrl}\nApi key is empty:${apiKey.isEmpty()}\n")
                throw Exception("Error fetching jwd token")
            }
        }
    }
    private fun getApiVolumesUrl(url: String): String {
        return "${url.split("/api/").first()}/api/Series/volumes?seriesId=${getIdFromUrl(url)}"
    }
    private fun getIdFromUrl(url: String): Int {
        return url.split("/").last().toInt()
    }
    private fun SeriesDto.toTrack(): TrackSearch = TrackSearch.create(TrackManager.KAVITA).also {
        it.title = name
        it.summary = "this is the summary"
//        it.publishing_status = "Reading"
    }
    private fun getTotalChapters(url: String): Int {
        val requestUrl = getApiVolumesUrl(url)
        try {
            val listVolumeDto = client.newCall(GET(requestUrl, headersBuilder().build()))
                .execute()
                .parseAs<List<VolumeDto>>()
            var chapterCount = 0
            for (volume in listVolumeDto) for (chapter in volume.chapters) chapterCount += 1
            return chapterCount
        } catch (e: Exception) {
            Log.e(LOG_TAG, "exception in getTotalChapters\nRequest:$requestUrl", e)
            throw e
        }
    }

//    private fun isValidKavitaVersion(): String {
//        val requestUrl = "$apiUrl/Server/server-info"
//        try {
//            val serverInfoDto = client.newCall(GET(requestUrl, headersBuilder().build()))
//                .execute()
//                .parseAs<ServerInfoDto>()
//
//            println(serverInfoDto.kavitaVersion)
//
//            return serverInfoDto.kavitaVersion
//
//
//        } catch (e: Exception) {
//            Log.e(LOG_TAG, "exception in getTotalChapters\nRequest:$requestUrl", e)
//            throw e
//        }
//    }
    private fun getLatestChapterRead(url: String): Float {
        var requestUrl = "$apiUrl/Reader/continue-point?seriesID=${getIdFromUrl(url)}"
        val currentChapterDto: ChapterDto = try {
            client.newCall(GET(requestUrl, headersBuilder().build()))
                .execute().parseAs<ChapterDto>()
        } catch (e: Exception) {
            Log.e(LOG_TAG, "exception in currentChapterDto\nRequest:$requestUrl", e)
            throw e
        }
        requestUrl = "$apiUrl/Reader/prev-chapter?seriesId=${getIdFromUrl(url)}&volumeId=${currentChapterDto.volumeId}&currentChapterId=${currentChapterDto.id}"
        val prevChapterId: Int = try {
            client.newCall(GET(requestUrl, headersBuilder().build()))
                .execute().parseAs<Int>()
        } catch (e: Exception) {
//            println("exception in prevChapterId")
            Log.e(LOG_TAG, "[tachiyomi][Kavita]exception in prevChapterId\nRequest:$requestUrl", e)
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
//            println("exception in prevChapterDto")
            Log.e(LOG_TAG, "exception in prevChapterDto\nCould not get item\nRequest:$requestUrl", e)
            throw e
        }
        return prevChapterDto.number.toFloat()
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
                    status = when (serieDto.pagesRead) {
                        serieDto.pages -> Kavita.COMPLETED
                        0 -> Kavita.UNREAD
                        else -> Kavita.READING
                    }
                    last_chapter_read = getLatestChapterRead(url)
                }
            } catch (e: Exception) {
                Log.e(LOG_TAG, "Could not get item: $url", e)
                throw e
            }
        }

    suspend fun updateProgress(track: Track): Track {
        getCleanedApiUrl(track.tracking_url)
        getToken(track.tracking_url)
        val requestUrl = "$apiUrl/Reader/mark-chapter-until-as-read?seriesId=${getIdFromUrl(track.tracking_url)}&chapterNumber=${track.last_chapter_read}"
        client.newCall(POST(requestUrl, headersBuilder().build(), "{}".toRequestBody("application/json; charset=utf-8".toMediaTypeOrNull())))
            .await()
        return getTrackSearch(track.tracking_url)
    }
}
