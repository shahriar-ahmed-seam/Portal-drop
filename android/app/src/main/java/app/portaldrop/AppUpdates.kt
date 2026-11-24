package app.portaldrop

import android.net.Uri
import java.io.IOException
import java.net.URI
import javax.net.ssl.HttpsURLConnection
import org.json.JSONObject

internal data class AppRelease(val tag: String, val page: Uri)

internal object AppUpdates {
    private const val LATEST = "https://api.github.com/repos/shahriar-ahmed-seam/Portal-drop/releases/latest"
    private const val RELEASE = "https://github.com/shahriar-ahmed-seam/Portal-drop/releases/tag/"
    private const val LIMIT = 256 * 1024
    private val versionFormat = Regex("""v?[0-9]+(?:\.[0-9]+)+""")

    fun newerThan(installed: String): AppRelease? {
        val current = version(installed) ?: throw IOException("The installed version could not be compared.")
        val connection = URI(LATEST).toURL().openConnection() as HttpsURLConnection
        try {
            connection.connectTimeout = 7000
            connection.readTimeout = 7000
            connection.useCaches = false
            connection.instanceFollowRedirects = false
            connection.setRequestProperty("Accept", "application/vnd.github+json")
            connection.setRequestProperty("User-Agent", "PortalDrop-Android")
            if (connection.responseCode != 200) throw IOException("GitHub could not return the latest release.")
            val response = connection.inputStream.bufferedReader(Charsets.UTF_8).use { reader ->
                val buffer = CharArray(4096)
                val body = StringBuilder()
                while (true) {
                    val count = reader.read(buffer)
                    if (count == -1) break
                    if (body.length + count > LIMIT) throw IOException("The release response is too large.")
                    body.append(buffer, 0, count)
                }
                JSONObject(body.toString())
            }
            if (response.optBoolean("draft") || response.optBoolean("prerelease")) return null
            val tag = response.optString("tag_name")
            val latest = version(tag) ?: throw IOException("The release version could not be compared.")
            for (index in 0 until maxOf(current.size, latest.size)) {
                val comparison = latest.getOrElse(index) { 0L }.compareTo(current.getOrElse(index) { 0L })
                if (comparison > 0) return AppRelease(tag, Uri.parse(RELEASE + tag))
                if (comparison < 0) return null
            }
            return null
        } finally {
            connection.disconnect()
        }
    }

    private fun version(raw: String): List<Long>? {
        if (raw.length > 64 || !versionFormat.matches(raw)) return null
        return raw.removePrefix("v").split('.').map { part -> part.toLongOrNull() ?: return null }
    }
}
