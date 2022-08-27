package eu.kanade.tachiyomi.data.track.kavita

import kotlinx.serialization.Serializable



@Serializable
data class OAuth(
    val sources: List<SourceOAuth> = emptyList(),
) {
    fun getSourceAuth(apiurl: String): SourceOAuth? {
        return try {
            sources.filter { it.apiurl == apiurl }.single()
        } catch (e:NoSuchElementException) {
            null
        }
    }
}

@Serializable
data class SourceOAuth(
    val token: String,
    val apikey: String,
    val apiurl: String,
    val created_at: Long,
    val expires_in: Long
) {
    // Access token lives 7 days
    //Todo: math to calculate token expiration
    fun isExpired() = (System.currentTimeMillis() / 1000) > (created_at + expires_in - 3600)

    fun isModified(newApiKey:String) = apikey != newApiKey
}
