package zibellino.mango.detour

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Ground elevation lookup for the barometer's drift-correction fit — now
 * backed by locally-cached SRTM tiles instead of a paid, key-gated API.
 *
 * The first lookup in a new ~1x1 degree area triggers a one-time tile
 * download from AWS's public "elevation-tiles-prod" bucket (no key, no
 * billing, no account). Every lookup after that in the same area is a
 * local file read — no network, no rate limit, no per-caller quota.
 *
 * This is a ground-truth *reference*, not a live sensor: it's only as
 * fine as the underlying ~30m SRTM grid, interpolated. Its job here is
 * purely to give the barometer something stable to anchor its drifting
 * bias against (see MainActivity.processSession).
 */
object ElevationClient {

    private var provider: SrtmElevationProvider? = null

    /** Call once, e.g. from Activity.onCreate, before the first fetchElevation call. */
    fun init(context: Context) {
        if (provider == null) {
            provider = SrtmElevationProvider(context.applicationContext)
        }
    }

    /** Returns ground elevation in meters, or null if the lookup failed. */
    suspend fun fetchElevation(latitude: Double, longitude: Double): Double? =
        withContext(Dispatchers.IO) {
            provider?.getElevation(latitude, longitude)
        }

    /** Returns the full sample (elevation, 4 corners, error estimate), or null if the lookup failed. */
    suspend fun fetchSample(latitude: Double, longitude: Double): SrtmSample? =
        withContext(Dispatchers.IO) {
            provider?.getSample(latitude, longitude)
        }
}
