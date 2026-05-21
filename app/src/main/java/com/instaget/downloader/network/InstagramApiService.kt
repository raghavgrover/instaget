package com.instaget.downloader.network

import com.google.gson.GsonBuilder
import retrofit2.Response
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import retrofit2.http.GET
import retrofit2.http.Query

interface InstagramApiService {

    @GET("oembed/")
    suspend fun getOEmbed(
        @Query("url") url: String,
        @Query("format") format: String = "json"
    ): Response<OEmbedResponse>

    companion object {
        private const val BASE_URL = "https://www.instagram.com/"
        private const val MOBILE_USER_AGENT =
            "Mozilla/5.0 (iPhone; CPU iPhone OS 14_0 like Mac OS X) AppleWebKit/605.1.15"

        fun create(): InstagramApiService {
            val client = okhttp3.OkHttpClient.Builder()
                .addInterceptor { chain ->
                    val request = chain.request().newBuilder()
                        .header("User-Agent", MOBILE_USER_AGENT)
                        .header("Accept", "application/json")
                        .build()
                    chain.proceed(request)
                }
                .addInterceptor(okhttp3.logging.HttpLoggingInterceptor().apply {
                    level = okhttp3.logging.HttpLoggingInterceptor.Level.BASIC
                })
                .build()

            val lenientGson = GsonBuilder().setLenient().create()

            return Retrofit.Builder()
                .baseUrl(BASE_URL)
                .client(client)
                .addConverterFactory(GsonConverterFactory.create(lenientGson))
                .build()
                .create(InstagramApiService::class.java)
        }
    }
}
