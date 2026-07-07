package zibellino.mango.detour

import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL

/**
 * Thin client for the Google Maps Elevation API — a lookup of DEM
 * (SRTM/Copernicus-derived) ground elevation for a given lat/lon. This is
 * a ground-truth *reference*, not a live sensor: it's only as fine as the
 * underlying ~30m grid, interpolated. Its job here is purely to give the
 * barometer something stable to anchor its drifting bias against.
 *
 * Requires ELEVATION_API_KEY to be set (see build.gradle.kts) and the
 * Elevation API enabled + billing set up on that Google Cloud project.
 */
object ElevationClient {

    /** Returns ground elevation in meters, or null if the lookup failed. */
    suspend fun fetchElevation(latitude: Double, longitude: Double): Double? =
        withContext(Dispatchers.IO) {
            if (BuildConfig.ELEVATION_API_KEY.isBlank()) return@withContext null

            try {
                val uri = Uri.parse("https://maps.googleapis.com/maps/api/elevation/json")
                    .buildUpon()
                    .appendQueryParameter("locations", "$latitude,$longitude")
                    .appendQueryParameter("key", BuildConfig.ELEVATION_API_KEY)
                    .build()

                val connection = URL(uri.toString()).openConnection() as HttpURLConnection
                connection.connectTimeout = 8_000
                connection.readTimeout = 8_000
                connection.requestMethod = "GET"

                val body = connection.inputStream.use { stream ->
                    BufferedReader(InputStreamReader(stream)).readText()
                }
                connection.disconnect()

                val json = JSONObject(body)
                if (json.optString("status") != "OK") return@withContext null

                val results = json.optJSONArray("results") ?: return@withContext null
                if (results.length() == 0) return@withContext null

                results.getJSONObject(0).optDouble("elevation").takeIf { !it.isNaN() }
            } catch (e: Exception) {
                null
            }
        }
}
