package eu.kanade.tachiyomi.data.track.kavita

import android.content.Context
import android.graphics.Color
import android.util.Log
import androidx.annotation.StringRes
import eu.kanade.tachiyomi.R
import eu.kanade.tachiyomi.data.database.models.Manga
import eu.kanade.tachiyomi.data.database.models.Track
import eu.kanade.tachiyomi.data.track.EnhancedTrackService
import eu.kanade.tachiyomi.data.track.NoLoginTrackService
import eu.kanade.tachiyomi.data.track.TrackService
import eu.kanade.tachiyomi.data.track.model.TrackSearch
import eu.kanade.tachiyomi.source.Source
import okhttp3.Dns
import okhttp3.OkHttpClient

class Kavita(private val context: Context, id: Int) : TrackService(id), EnhancedTrackService, NoLoginTrackService {

    companion object {
        const val UNREAD = 1
        const val READING = 2
        const val COMPLETED = 3
    }
    override val client: OkHttpClient =
        networkService.client.newBuilder()
            .dns(Dns.SYSTEM) // don't use DNS over HTTPS as it breaks IP addressing
            .build()

    val api by lazy { KavitaApi(client) }

    @StringRes
    override fun nameRes() = R.string.tracker_kavita

    override fun getLogo(): Int = R.drawable.ic_tracker_kavita

    override fun getLogoColor() = Color.rgb(74, 198, 148)

    override fun getStatusList() = listOf(UNREAD, READING, COMPLETED)

    override fun getStatus(status: Int): String = with(context) {
        when (status) {
            Kavita.UNREAD -> getString(R.string.unread)
            Kavita.READING -> getString(R.string.reading)
            Kavita.COMPLETED -> getString(R.string.completed)
            else -> ""
        }
    }

    override fun getReadingStatus(): Int = Kavita.READING

    override fun getRereadingStatus(): Int = -1

    override fun getCompletionStatus(): Int = Kavita.COMPLETED

    override fun getScoreList(): List<String> = emptyList()

    override fun displayScore(track: Track): String = ""

    override suspend fun update(track: Track, didReadChapter: Boolean): Track {
        Log.d("tachiyomi-[kavita][tracking]", "Inside update")
        Log.d("tachiyomi-[kavita][tracking]", track.toString())
        if (track.status != COMPLETED) {
            if (didReadChapter) {
                if (track.last_chapter_read.toInt() == track.total_chapters && track.total_chapters > 0) {
                    track.status = COMPLETED
                } else {
                    track.status = READING
                }
            }
        }
        return api.updateProgress(track)
    }

    override suspend fun bind(track: Track, hasReadChapters: Boolean): Track {
        Log.d("tachiyomi-[kavita][tracking]", "Inside bind")
        return track
    }

    override suspend fun search(query: String): List<TrackSearch> {
        TODO("Not yet implemented: search")
    }

    override suspend fun refresh(track: Track): Track {
        Log.d("tachiyomi-[kavita][tracking]", "Inside refresh")
        val remoteTrack = api.getTrackSearch(track.tracking_url)
        track.copyPersonalFrom(remoteTrack)
        track.total_chapters = remoteTrack.total_chapters
        return track
    }

    override suspend fun login(username: String, password: String) {
        saveCredentials("user", "pass")
    }

    // TrackService.isLogged works by checking that credentials are saved.
    // By saving dummy, unused credentials, we can activate the tracker simply by login/logout
    override fun loginNoop() {
        saveCredentials("user", "pass")
    }

    override fun getAcceptedSources() = listOf("eu.kanade.tachiyomi.extension.all.kavita.Kavita")

    override suspend fun match(manga: Manga): TrackSearch? =
        try {
            api.getTrackSearch(manga.url)
        } catch (e: Exception) {
            Log.e("tachiyomi-[kavita][tracking]", "Exception finding match", e)
            null
        }

    override fun isTrackFrom(track: Track, manga: Manga, source: Source?): Boolean =
        track.tracking_url == manga.url && source?.let { accept(it) } == true

    override fun migrateTrack(track: Track, manga: Manga, newSource: Source): Track? =
        if (accept(newSource)) {
            track.also { track.tracking_url = manga.url }
        } else {
            null
        }
}
