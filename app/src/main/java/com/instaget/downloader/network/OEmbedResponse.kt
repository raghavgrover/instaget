package com.instaget.downloader.network

import com.google.gson.annotations.SerializedName

data class OEmbedResponse(
    @SerializedName("title") val title: String?,
    @SerializedName("author_name") val authorName: String?,
    @SerializedName("provider_name") val providerName: String?,
    @SerializedName("thumbnail_url") val thumbnailUrl: String?,
    @SerializedName("thumbnail_width") val thumbnailWidth: Int?,
    @SerializedName("thumbnail_height") val thumbnailHeight: Int?,
    @SerializedName("html") val html: String?,
    @SerializedName("type") val type: String?
)
