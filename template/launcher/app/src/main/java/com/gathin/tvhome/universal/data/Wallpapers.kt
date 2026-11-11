package com.gathin.tvhome.universal.data

import android.content.Context
import android.graphics.BitmapFactory
import android.util.LruCache
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap

/** Одна обоина в памяти занимает ~8 МБ (1920x1080x4), поэтому держим не больше двух. */
object WallpaperCache {
    private val cache = LruCache<Int, ImageBitmap>(2)

    fun get(index: Int): ImageBitmap? = cache.get(index)

    fun load(context: Context, resId: Int, index: Int): ImageBitmap {
        cache.get(index)?.let { return it }
        val bmp = BitmapFactory.decodeResource(context.resources, resId).asImageBitmap()
        cache.put(index, bmp)
        return bmp
    }

    fun clear() = cache.evictAll()
}
