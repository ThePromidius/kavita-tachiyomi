package eu.kanade.tachiyomi.data.track.kavita

import kotlinx.serialization.Serializable

@Serializable
data class SeriesDto(
    val id: Int,
    val name: String,
    val originalName: String = "",
    val thumbnail_url: String? = "",
    val localizedName: String? = "",
    val sortName: String? = "",
    val pages: Int,
    val coverImageLocked: Boolean = true,
    val pagesRead: Int,
    val userRating: Int,
    val userReview: String? = "",
    val format: Int,
    val created: String? = "",
    val libraryId: Int,
    val libraryName: String? = ""
)

@Serializable
data class VolumeDto(
    val id: Int,
    val number: Int,
    val name: String,
    val pages: Int,
    val pagesRead: Int,
    val lastModified: String,
    val created: String,
    val seriesId: Int,
    val chapters: List<ChapterDto> = emptyList()
)

@Serializable
data class ChapterDto(
    val id: Int,
    val range: String,
    val number: String,
    val pages: Int,
    val isSpecial: Boolean,
    val title: String,
    val pagesRead: Int,
    val coverImageLocked: Boolean,
    val volumeId: Int,
    val created: String
)
data class getProgress(
    val volumeId: Int,
    val chapterId: Int,
    val pageNum: Int,
    val seriesId: Int,
    val bookScrollId: String
)

@Serializable
data class AuthenticationDto(
    val username: String,
    val token: String,
    val apiKey: String
)
