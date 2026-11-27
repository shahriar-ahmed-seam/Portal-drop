package app.portaldrop

import android.content.ContentResolver
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageDecoder
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.CancellationSignal
import android.os.Handler
import android.os.Looper
import android.util.LruCache
import android.util.Size
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView
import java.io.File
import java.util.Locale
import java.util.concurrent.Future
import java.util.concurrent.ScheduledThreadPoolExecutor
import java.util.concurrent.TimeUnit
import kotlin.math.max

internal enum class VisualMedia { PHOTO, VIDEO }

internal fun ChatItem.visualMedia(): VisualMedia? {
    if (item.kind != "file") return null
    val mime = resolveMimeType(item.name)
    return when {
        mime.startsWith("image/") -> VisualMedia.PHOTO
        mime.startsWith("video/") -> VisualMedia.VIDEO
        else -> null
    }
}

internal fun ChatItem.localFileUri(): Uri? = if (incoming) downloadUri.takeIf { delivered } else sourceUri

internal class MediaPreviews(context: Context, private val store: PortalStore) : AutoCloseable {
    data class Preview(val bitmap: Bitmap?, val durationMs: Long?)
    private data class Source(val uri: Uri?, val file: File?)

    private val app = context.applicationContext
    private val handler = Handler(Looper.getMainLooper())
    // A video decoder can retain full-size frames internally while generating a small thumbnail.
    private val worker = ScheduledThreadPoolExecutor(1).apply { removeOnCancelPolicy = true }
    private val cache = object : LruCache<String, Preview>(12 * 1024 * 1024) {
        override fun sizeOf(key: String, value: Preview): Int = value.bitmap?.allocationByteCount ?: 64
    }

    fun load(row: ChatItem, kind: VisualMedia, signal: CancellationSignal, done: (Preview?) -> Unit): Future<*>? {
        if (worker.isShutdown) return null
        val key = "${row.peerFingerprint}:${row.incoming}:${row.item.id}"
        cache.get(key)?.let {
            done(it)
            return null
        }
        val uri = row.localFileUri()
        val canUseStagedFile = !row.incoming && !row.delivered && uri == null
        if (uri == null && !canUseStagedFile) {
            done(null)
            return null
        }
        return worker.schedule({
            val preview = try {
                signal.throwIfCanceled()
                val staged = if (canUseStagedFile) store.stagedFile(row.item.id) else null
                val source = Source(uri, staged)
                if (kind == VisualMedia.VIDEO) video(source, signal) else Preview(photo(source, signal), null)
            } catch (_: Exception) {
                null
            }
            if (!signal.isCanceled && preview != null && (preview.bitmap != null || preview.durationMs != null)) {
                cache.put(key, preview)
            }
            if (!signal.isCanceled) handler.post { if (!signal.isCanceled) done(preview) }
        }, PREVIEW_DELAY_MS, TimeUnit.MILLISECONDS)
    }

    private fun thumbnail(uri: Uri, signal: CancellationSignal): Bitmap? {
        if (uri.scheme != ContentResolver.SCHEME_CONTENT) return null
        return runCatching { app.contentResolver.loadThumbnail(uri, Size(PREVIEW_SIZE, PREVIEW_SIZE), signal) }.getOrNull()
    }

    private fun photo(source: Source, signal: CancellationSignal): Bitmap? {
        source.uri?.let { uri ->
            thumbnail(uri, signal)?.let { return it }
            signal.throwIfCanceled()
            return ImageDecoder.decodeBitmap(ImageDecoder.createSource(app.contentResolver, uri)) { decoder, info, _ ->
                signal.throwIfCanceled()
                val size = info.size
                val sample = ((max(size.width, size.height) + PREVIEW_SIZE - 1) / PREVIEW_SIZE).coerceAtLeast(1)
                decoder.setTargetSampleSize(sample)
            }
        }
        val path = source.file?.absolutePath ?: return null
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(path, bounds)
        signal.throwIfCanceled()
        val sample = ((max(bounds.outWidth, bounds.outHeight) + PREVIEW_SIZE - 1) / PREVIEW_SIZE).coerceAtLeast(1)
        return BitmapFactory.decodeFile(path, BitmapFactory.Options().apply { inSampleSize = sample })
    }

    private fun video(source: Source, signal: CancellationSignal): Preview {
        val thumb = source.uri?.let { thumbnail(it, signal) }
        signal.throwIfCanceled()
        val retriever = MediaMetadataRetriever()
        return try {
            val uri = source.uri
            if (uri != null) retriever.setDataSource(app, uri)
            else retriever.setDataSource(source.file?.absolutePath ?: return Preview(thumb, null))
            signal.throwIfCanceled()
            val duration = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull()
            val bitmap = thumb ?: runCatching {
                val width = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toIntOrNull()
                val height = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toIntOrNull()
                if (width == null || height == null || width <= 0 || height <= 0) return@runCatching null
                val rotation = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)?.toIntOrNull()
                val targetWidth = if (rotation == 90 || rotation == 270) height else width
                val targetHeight = if (rotation == 90 || rotation == 270) width else height
                val scale = minOf(1.0, PREVIEW_SIZE.toDouble() / max(targetWidth, targetHeight))
                retriever.getScaledFrameAtTime(
                    -1L, MediaMetadataRetriever.OPTION_CLOSEST_SYNC,
                    (targetWidth * scale).toInt().coerceAtLeast(1), (targetHeight * scale).toInt().coerceAtLeast(1),
                )
            }.getOrNull()
            Preview(bitmap, duration)
        } catch (_: Exception) {
            Preview(thumb, null)
        } finally {
            runCatching { retriever.release() }
        }
    }

    override fun close() {
        worker.shutdownNow()
        cache.evictAll()
    }

    private companion object {
        const val PREVIEW_SIZE = 384
        const val PREVIEW_DELAY_MS = 80L
    }
}

internal class MediaThumbnail(
    context: Context, private val ui: PortalViews, private val previews: MediaPreviews, private val square: Boolean,
) : FrameLayout(context) {
    private val image = ImageView(context).apply {
        scaleType = ImageView.ScaleType.CENTER_CROP
        importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
    }
    private val play = ui.symbol(R.drawable.ic_media_play, -1).apply {
        background = ui.shape(0x960B2018.toInt(), 28)
        setPadding(ui.dp(8), ui.dp(8), ui.dp(8), ui.dp(8))
    }
    private val placeholder = ui.symbol(R.drawable.ic_media_photo, ui.muted)
    private val duration = ui.label("", 11, -1, bold = true).apply {
        setPadding(ui.dp(6), ui.dp(2), ui.dp(6), ui.dp(2))
        background = ui.shape(0xC013231B.toInt(), 8)
        visibility = GONE
    }
    private var row: ChatItem? = null
    private var kind: VisualMedia? = null
    private var task: Future<*>? = null
    private var signal: CancellationSignal? = null

    init {
        background = ui.shape(ui.replyColor, 10)
        clipToOutline = true
        addView(image, LayoutParams(MATCH, MATCH))
        addView(placeholder, LayoutParams(ui.dp(28), ui.dp(28), Gravity.CENTER))
        addView(play, LayoutParams(ui.dp(42), ui.dp(42), Gravity.CENTER))
        addView(duration, LayoutParams(WRAP, WRAP, Gravity.BOTTOM or Gravity.END).apply {
            bottomMargin = ui.dp(5)
            marginEnd = ui.dp(5)
        })
    }

    fun bind(message: ChatItem, media: VisualMedia) {
        if (row == message && kind == media) return
        stopLoading()
        row = message
        kind = media
        image.setImageDrawable(null)
        showDuration(null)
        play.visibility = if (media == VisualMedia.VIDEO) VISIBLE else GONE
        placeholder.visibility = if (media == VisualMedia.PHOTO) VISIBLE else GONE
        describe(message, media, null)
        if (isAttachedToWindow) startLoading()
    }

    fun recycle() {
        stopLoading()
        row = null
        kind = null
        image.setImageDrawable(null)
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val height = if (square) MeasureSpec.makeMeasureSpec(MeasureSpec.getSize(widthMeasureSpec), MeasureSpec.EXACTLY)
        else heightMeasureSpec
        super.onMeasure(widthMeasureSpec, height)
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        startLoading()
    }

    override fun onDetachedFromWindow() {
        stopLoading()
        super.onDetachedFromWindow()
    }

    private fun startLoading() {
        val message = row ?: return
        val media = kind ?: return
        val request = CancellationSignal()
        signal = request
        task = previews.load(message, media, request) { preview ->
            if (signal !== request) return@load
            val bitmap = preview?.bitmap
            image.setImageBitmap(bitmap)
            placeholder.visibility = if (media == VisualMedia.PHOTO && bitmap == null) VISIBLE else GONE
            showDuration(preview?.durationMs)
            describe(message, media, preview?.durationMs)
        }
    }

    private fun stopLoading() {
        signal?.cancel()
        task?.cancel(false)
        signal = null
        task = null
    }

    private fun showDuration(millis: Long?) {
        duration.text = millis?.let(::mediaDuration).orEmpty()
        duration.visibility = if (millis == null) GONE else VISIBLE
    }

    private fun describe(message: ChatItem, media: VisualMedia, millis: Long?) {
        val type = if (media == VisualMedia.VIDEO) "Video" else "Photo"
        val name = message.item.name.orEmpty()
        contentDescription = listOfNotNull(type, name, millis?.let(::mediaDuration)).joinToString(", ")
    }
}

internal fun mediaDuration(millis: Long): String {
    val seconds = millis.coerceAtLeast(0L) / 1000
    val hours = seconds / 3600
    return if (hours > 0) String.format(Locale.ROOT, "%d:%02d:%02d", hours, seconds / 60 % 60, seconds % 60)
    else String.format(Locale.ROOT, "%d:%02d", seconds / 60, seconds % 60)
}
