package dev.marc.japanesehelper

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.net.Uri
import android.provider.OpenableColumns
import androidx.exifinterface.media.ExifInterface
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.InputStream
import java.util.zip.ZipFile

/** Une suite de pages : images sélectionnées ou archive CBZ. */
interface PageSource {
    val title: String
    val pageCount: Int
    suspend fun load(index: Int): Bitmap
    fun close() {}

    companion object {
        /** Taille maximale du grand côté : assez pour l'OCR, raisonnable en mémoire. */
        const val MAX_SIDE = 2560

        suspend fun open(context: Context, uris: List<Uri>): PageSource = withContext(Dispatchers.IO) {
            val first = uris.first()
            val name = displayName(context, first)
            if (uris.size == 1 && name.lowercase().let { it.endsWith(".cbz") || it.endsWith(".zip") }) {
                CbzSource.open(context, first, name)
            } else {
                ImagesSource(context, uris.sortedWith(compareBy(NaturalOrder) { displayName(context, it) }))
            }
        }

        private fun displayName(context: Context, uri: Uri): String =
            context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use {
                if (it.moveToFirst()) it.getString(0) else null
            } ?: uri.lastPathSegment.orEmpty()
    }
}

private class ImagesSource(private val context: Context, private val uris: List<Uri>) : PageSource {
    override val title = "${uris.size} image(s)"
    override val pageCount = uris.size

    override suspend fun load(index: Int) = withContext(Dispatchers.IO) {
        val resolver = context.contentResolver
        decode({ resolver.openInputStream(uris[index])!! })
    }
}

/** Pages photographiées avec la caméra, dans l'ordre de prise. */
class CapturedPages(val files: List<File>) : PageSource {
    override val title = "Photos"
    override val pageCount = files.size

    override suspend fun load(index: Int) = withContext(Dispatchers.IO) {
        decode({ files[index].inputStream() })
    }
}

private class CbzSource(private val zip: ZipFile, override val title: String) : PageSource {
    private val entries = zip.entries().toList()
        .filter { !it.isDirectory && IMAGE_EXT.any { ext -> it.name.lowercase().endsWith(ext) } }
        .filterNot { it.name.startsWith("__MACOSX") }
        .sortedWith(compareBy(NaturalOrder) { it.name })

    override val pageCount = entries.size

    override suspend fun load(index: Int) = withContext(Dispatchers.IO) {
        decode({ zip.getInputStream(entries[index]) })
    }

    override fun close() = zip.close()

    companion object {
        private val IMAGE_EXT = listOf(".jpg", ".jpeg", ".png", ".webp", ".gif", ".bmp")

        /** ZipFile a besoin d'un accès aléatoire : on copie l'archive dans le cache. */
        fun open(context: Context, uri: Uri, name: String): CbzSource {
            val file = File(context.cacheDir, "current.cbz")
            context.contentResolver.openInputStream(uri)!!.use { input -> file.outputStream().use { input.copyTo(it) } }
            return CbzSource(ZipFile(file), name.substringBeforeLast('.'))
        }
    }
}

/** Décode avec un grand côté d'au plus MAX_SIDE, puis applique la rotation EXIF. */
private fun decode(open: () -> InputStream): Bitmap {
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    open().use { BitmapFactory.decodeStream(it, null, bounds) }
    var sample = 1
    while (maxOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= PageSource.MAX_SIDE) sample *= 2

    val options = BitmapFactory.Options().apply {
        inSampleSize = sample
        inPreferredConfig = Bitmap.Config.ARGB_8888
    }
    val bitmap = open().use { BitmapFactory.decodeStream(it, null, options) }
        ?: error("Image illisible")

    // Les photos de l'appareil sont souvent enregistrées couchées avec un tag EXIF
    val orientation = runCatching {
        open().use { ExifInterface(it).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL) }
    }.getOrDefault(ExifInterface.ORIENTATION_NORMAL)
    val matrix = Matrix()
    val scale = PageSource.MAX_SIDE.toFloat() / maxOf(bitmap.width, bitmap.height)
    if (scale < 1f) matrix.postScale(scale, scale)
    when (orientation) {
        ExifInterface.ORIENTATION_ROTATE_90 -> matrix.postRotate(90f)
        ExifInterface.ORIENTATION_ROTATE_180 -> matrix.postRotate(180f)
        ExifInterface.ORIENTATION_ROTATE_270 -> matrix.postRotate(270f)
        ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> matrix.postScale(-1f, 1f)
        ExifInterface.ORIENTATION_FLIP_VERTICAL -> matrix.postScale(1f, -1f)
        ExifInterface.ORIENTATION_TRANSPOSE -> { matrix.postRotate(90f); matrix.postScale(-1f, 1f) }
        ExifInterface.ORIENTATION_TRANSVERSE -> { matrix.postRotate(270f); matrix.postScale(-1f, 1f) }
    }
    if (matrix.isIdentity) return bitmap
    return Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
}

/** Tri "naturel" : page2 avant page10. */
private object NaturalOrder : Comparator<String> {
    private val chunks = Regex("\\d+|\\D+")

    override fun compare(a: String, b: String): Int {
        val ca = chunks.findAll(a.lowercase()).map { it.value }.toList()
        val cb = chunks.findAll(b.lowercase()).map { it.value }.toList()
        for (i in 0 until minOf(ca.size, cb.size)) {
            val x = ca[i]
            val y = cb[i]
            val cmp = if (x[0].isDigit() && y[0].isDigit()) {
                x.trimStart('0').length.compareTo(y.trimStart('0').length).takeIf { it != 0 }
                    ?: x.trimStart('0').compareTo(y.trimStart('0'))
            } else {
                x.compareTo(y)
            }
            if (cmp != 0) return cmp
        }
        return ca.size.compareTo(cb.size)
    }
}
