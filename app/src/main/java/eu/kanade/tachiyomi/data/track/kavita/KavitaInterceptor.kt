package eu.kanade.tachiyomi.data.track.kavita


import eu.kanade.tachiyomi.data.track.shikimori.ShikimoriApi
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import okhttp3.Interceptor
import okhttp3.Response
import uy.kohesive.injekt.injectLazy

class KavitaInterceptor(val kavita: Kavita) : Interceptor {

    private val json: Json by injectLazy()

    /**
     * OAuth object used to store all sources token
     */
    private var oauth: OAuth? = kavita.restoreToken()


    override fun intercept(chain: Interceptor.Chain): Response {
        val originalRequest = chain.request()

        val currAuth = oauth ?: throw Exception("Not authenticated with Kavita")

        val refreshToken = currAuth.refresh_token!!

        // Refresh access token if expired.
        if (currAuth.isExpired()) {
            val response = chain.proceed(ShikimoriApi.refreshTokenRequest(refreshToken))
            if (response.isSuccessful) {
                newAuth(json.decodeFromString<eu.kanade.tachiyomi.data.track.shikimori.OAuth>(response.body!!.string()))
            } else {
                response.close()
            }
        }
        // Add the authorization header to the original request.
        val authRequest = originalRequest.newBuilder()
            .addHeader("Authorization", "Bearer ${oauth!!.access_token}")
            .header("User-Agent", "Tachiyomi")
            .build()

        return chain.proceed(authRequest)
    }
    fun newAuth(oauth: eu.kanade.tachiyomi.data.track.shikimori.OAuth?) {
        this.oauth = oauth
        kavita.saveToken(oauth)
    }

}

