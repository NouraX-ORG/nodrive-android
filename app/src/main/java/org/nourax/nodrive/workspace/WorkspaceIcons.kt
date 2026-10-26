/*
 * NoDrive by Nourax
 *
 * SPDX-FileCopyrightText: 2026 Nourax
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package org.nourax.nodrive.workspace

import android.content.Context
import android.graphics.Canvas
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.graphics.drawable.PictureDrawable
import androidx.activity.ComponentActivity
import androidx.core.graphics.createBitmap
import androidx.core.graphics.drawable.toDrawable
import androidx.core.graphics.scale
import com.bumptech.glide.Glide
import com.bumptech.glide.load.model.GlideUrl
import com.bumptech.glide.load.model.LazyHeaders
import com.bumptech.glide.request.target.CustomTarget
import com.bumptech.glide.request.transition.Transition

/**
 * Icons of the Workspace apps menu. They are public static files of the server, so they are requested WITHOUT
 * the account's credential: Glide follows redirects itself and would hand the same headers to any host.
 */
object WorkspaceIcons {
    private const val USER_AGENT = "Mozilla/5.0 (Android) NoDrive"

    /** Everything an icon request carries. Never an Authorization header (checked by WorkspaceIconsTest). */
    fun headers(): Map<String, String> = mapOf("User-Agent" to USER_AGENT)

    fun isSvg(url: String): Boolean = WorkspaceOrigin.parse(url)?.rawPath?.endsWith(".svg", ignoreCase = true) == true

    fun load(activity: ComponentActivity, url: String, onReady: (Drawable) -> Unit) {
        val builder = LazyHeaders.Builder()
        headers().forEach { (name, value) -> builder.addHeader(name, value) }
        val model = GlideUrl(url, builder.build())
        if (isSvg(url)) {
            Glide.with(activity).`as`(PictureDrawable::class.java).load(model).into(target(onReady))
        } else {
            Glide.with(activity).asDrawable().load(model).into(target(onReady))
        }
    }

    /** A square bitmap drawable; a vector picture is scaled to the size first (a bare draw only clips it). */
    fun rasterize(context: Context, drawable: Drawable, sizePx: Int): Drawable {
        if (drawable is BitmapDrawable) return drawable.bitmap.scale(sizePx, sizePx).toDrawable(context.resources)
        val bitmap = createBitmap(sizePx, sizePx)
        val canvas = Canvas(bitmap)
        val width = drawable.intrinsicWidth
        val height = drawable.intrinsicHeight
        if (width > 0 && height > 0) {
            canvas.scale(sizePx / width.toFloat(), sizePx / height.toFloat())
            drawable.setBounds(0, 0, width, height)
        } else {
            drawable.setBounds(0, 0, sizePx, sizePx)
        }
        drawable.draw(canvas)
        return bitmap.toDrawable(context.resources)
    }

    private fun <T : Drawable> target(onReady: (Drawable) -> Unit) = object : CustomTarget<T>() {
        override fun onResourceReady(resource: T, transition: Transition<in T>?) = onReady(resource)

        override fun onLoadCleared(placeholder: Drawable?) = Unit
    }
}
