package com.lladlam.melox.core.provider.kuwo

import coil3.intercept.Interceptor
import coil3.request.ErrorResult
import coil3.request.ImageResult
import coil3.size.Dimension

/** Requests larger CDN covers for large views while keeping the catalog's small thumbnails. */
internal object KuwoArtworkInterceptor : Interceptor {
    override suspend fun intercept(chain: Interceptor.Chain): ImageResult {
        val original = chain.request.data as? String ?: return chain.proceed()
        val cover = KuwoAlbumCover.matchEntire(original) ?: return chain.proceed()
        val width = (chain.size.width as? Dimension.Pixels)?.px ?: return chain.proceed()
        val height = (chain.size.height as? Dimension.Pixels)?.px ?: return chain.proceed()
        val target = when (minOf(width, height)) {
            in 0..191 -> return chain.proceed()
            in 192..359 -> 300
            else -> 500
        }
        if (cover.groupValues[2].toInt() >= target) return chain.proceed()

        val largerUrl = "${cover.groupValues[1]}$target/${cover.groupValues[3]}"
        val result = chain.withRequest(chain.request.newBuilder().data(largerUrl).build()).proceed()
        return if (result is ErrorResult) chain.proceed() else result
    }

    private val KuwoAlbumCover = Regex("""(https://img\d*\.kuwo\.cn/star/albumcover/)(\d{2,4})/(.+)""")
}
