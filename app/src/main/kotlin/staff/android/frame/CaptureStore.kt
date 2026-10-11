package staff.android.frame

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageDecoder
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest
import java.util.UUID

class CaptureStore(private val directory: File) {
    init {
        directory.mkdirs()
    }

    fun complete(file: File): File {
        require(file.length() in 1..16_777_216)
        val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.path, options)
        require(options.outWidth in 1..10000 && options.outHeight in 1..10000)
        val final = File(directory, file.name.removeSuffix(".partial") + ".jpg")
        android.system.Os.rename(file.path, final.path)
        return final
    }

    fun temporary() = File(directory, UUID.randomUUID().toString() + ".partial")

    fun files() =
        directory
            .listFiles()
            ?.filter {
                it.extension == "jpg" &&
                    runCatching { UUID.fromString(it.nameWithoutExtension) }.isSuccess
            }
            ?.sortedByDescending { it.lastModified() } ?: emptyList()

    fun metadata(file: File): JSONObject {
        require(file.length() in 1..16_777_216)
        val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.path, options)
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(8192)
            while (true) {
                val n = input.read(buffer)
                if (n < 0) break
                digest.update(buffer, 0, n)
            }
        }
        return JSONObject()
            .put("assetId", file.nameWithoutExtension)
            .put("width", options.outWidth)
            .put("height", options.outHeight)
            .put("sha256", digest.digest().joinToString("") { "%02x".format(it) })
    }

    fun thumbnail(file: File): Bitmap {
        require(file.length() in 1..16_777_216)
        if (android.os.Build.VERSION.SDK_INT < 28) {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(file.path, bounds)
            require(bounds.outWidth in 1..10000 && bounds.outHeight in 1..10000)
            var sample = 1
            while (maxOf(bounds.outWidth, bounds.outHeight) / sample > 640) sample *= 2
            val bitmap =
                BitmapFactory.decodeFile(
                    file.path, BitmapFactory.Options().apply { inSampleSize = sample })
                    ?: error("JPEG could not decode")
            val exif = android.media.ExifInterface(file.path)
            val matrix = android.graphics.Matrix()
            when (exif.getAttributeInt(android.media.ExifInterface.TAG_ORIENTATION, 1)) {
                2 -> matrix.setScale(-1f, 1f)
                3 -> matrix.setRotate(180f)
                4 -> matrix.setScale(1f, -1f)
                5 -> {
                    matrix.setRotate(90f)
                    matrix.postScale(-1f, 1f)
                }
                6 -> matrix.setRotate(90f)
                7 -> {
                    matrix.setRotate(-90f)
                    matrix.postScale(-1f, 1f)
                }
                8 -> matrix.setRotate(-90f)
            }
            return Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
                .also { if (it !== bitmap) bitmap.recycle() }
        }
        return ImageDecoder.decodeBitmap(ImageDecoder.createSource(file)) { decoder, info, _ ->
            require(info.size.width in 1..10000 && info.size.height in 1..10000)
            val ratio = maxOf(info.size.width, info.size.height).toFloat() / 640
            decoder.setTargetSize(
                (info.size.width / ratio.coerceAtLeast(1f)).toInt().coerceAtLeast(1),
                (info.size.height / ratio.coerceAtLeast(1f)).toInt().coerceAtLeast(1))
            decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
        }
    }
}
