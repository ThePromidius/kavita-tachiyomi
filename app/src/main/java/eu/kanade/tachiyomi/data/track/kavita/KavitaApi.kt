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
import okhttp3.Headers
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.OkHttpClient
import okhttp3.RequestBody.Companion.toRequestBody
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.security.MessageDigest

class KavitaApi(private val client: OkHttpClient) {
    var jwtToken = ""
    fun headersBuilder(): Headers.Builder {
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
                if (prefApiUrl == apiUrl) {
                    prefApiKey = Injekt.get<Application>()
                        .getSharedPreferences("source_$sourceSuffixID", 0x0000)
                        .getString("APIKEY", "")!!
                    break
                }
            }
        }
        return prefApiKey
    }
    fun getCleanedApiUrl(url: String) = "${url.split("/api/").first()}/api"
    fun getToken(url: String) {
        val cleanedApiUrl = getCleanedApiUrl(url)
        val apiKey = getKavitaPreferencesApiKey(cleanedApiUrl)
        val request = POST(
            "$cleanedApiUrl/Plugin/authenticate?apiKey=$apiKey&pluginName=Tachiyomi-Kavita",
            headersBuilder().build(),
            "{}".toRequestBody("application/json; charset=utf-8".toMediaTypeOrNull())
        )
        client.newCall(request).execute().use {
            if (it.code == 200) {
                jwtToken = it.parseAs<AuthenticationDto>().token
            }
        }
    }

    suspend fun getTrackSearch(url: String): TrackSearch =
        withIOContext {
            if (jwtToken.isEmpty()) {
                getToken(url)
            }
            println("after token")
            val track: TrackSearch = try {
                client.newCall(GET(url, headersBuilder().build()))
                    .await()
                    .parseAs<SeriesDto>()
                    .toTrack()
            } catch (e: Exception) {
                println("exception")
                throw Exception()
            }
            println("after_trackObtained")
            val CurrentChapterId: ChapterDto = try {
                client.newCall(GET("${getCleanedApiUrl(url)}/Reader/continue-point?seriesID=${getIdFromUrl(url)}", headersBuilder().build()))
                    .await().parseAs<ChapterDto>()
            } catch (e: Exception) {
                println("exception in currentchapterID")
                throw Exception()
            }

            println("CurrentChapterId")
            val progress = try {
                client.newCall(
                    GET(getApiVolumesUrl(url), headersBuilder().build())
                )
                    .await()
                    .parseAs<List<VolumeDto>>()
            } catch (e: Exception) {
                println("Exception")
                throw Exception()
            }
            println("progress")
            var totalChapters = 0
            for (volume in progress) for (chapter in volume.chapters) {
                totalChapters += 1
            }
            val prevChapterId: Int = try {
                client.newCall(GET("${getCleanedApiUrl(url)}/Reader/prev-chapter?seriesId=${getIdFromUrl(url)}&volumeId=${CurrentChapterId.volumeId}&currentChapterId=${CurrentChapterId.id}", headersBuilder().build()))
                    .await().parseAs<Int>()
            } catch (e: Exception) {
                println("exception")
                throw Exception()
            }
            val request = GET("${getCleanedApiUrl(url)}/Series/chapter?chapterId=$prevChapterId", headersBuilder().build())

            val prevChapterDto: ChapterDto = // try {
                client.newCall(request)
                    .await().parseAs<ChapterDto>()
//            } catch (e: Exception) {
//                println("exception in currentchapterID")
//                throw Exception()
//            }
            var lastChapterReadRemote = prevChapterDto.number.toFloat()
            println("prevChapter")
            if (lastChapterReadRemote < 0) lastChapterReadRemote = 0.toFloat()
            track.apply {
                cover_url = ""
                tracking_url = url
                total_chapters = totalChapters
                last_chapter_read = lastChapterReadRemote
            }
        }

    suspend fun updateProgress(track: Track): Track {
        if (jwtToken.isEmpty()) {
            getToken(track.tracking_url)
        }

        val CurrentChapterId: ChapterDto = try {
            client.newCall(GET("${getCleanedApiUrl(track.tracking_url)}/Reader/continue-point?seriesID=${getIdFromUrl(track.tracking_url)}", headersBuilder().build()))
                .await()
                .parseAs<ChapterDto>()
        } catch (e: Exception) {
            println("exception")
            throw Exception()
        }
        val prevChapter: Float = try {
            client.newCall(GET("${getCleanedApiUrl(track.tracking_url)}/Reader/prev-chapter?seriesId=${getIdFromUrl(track.tracking_url)}&volumeId=${CurrentChapterId.volumeId}&currentChapterId=${CurrentChapterId.id}", headersBuilder().build()))
                .await().parseAs<Float>()
        } catch (e: Exception) {
            println("exception")
            throw Exception()
        }

        println(prevChapter)
        println(track.last_chapter_read)
        TODO("Send new last chapter")
//        val payload = if (track.tracking_url.contains("/api/v1/series/")) {
//            json.encodeToString(ReadProgressUpdateV2Dto(track.last_chapter_read))
//        } else {
//            json.encodeToString(ReadProgressUpdateDto(track.last_chapter_read.toInt()))
//        }
//        client.newCall(
//            Request.Builder()
//                .url("${track.tracking_url.replace("/api/v1/series/", "/api/v2/series/")}/read-progress/tachiyomi")
//                .put(payload.toRequestBody("application/json".toMediaType()))
//                .build()
//        )
//            .await()
        return getTrackSearch(track.tracking_url)
    }

    private fun SeriesDto.toTrack(): TrackSearch = TrackSearch.create(TrackManager.KAVITA).also {
        it.title = name
        it.summary = "this"
        it.publishing_status = "Currently reading"
    }
    private fun getApiVolumesUrl(url: String): String {
        return "${url.split("/api/").first()}/api/Series/volumes?seriesId=${getIdFromUrl(url)}"
    }
    private fun getIdFromUrl(url: String): Int {
        return url.split("/").last().toInt()
    }
}
