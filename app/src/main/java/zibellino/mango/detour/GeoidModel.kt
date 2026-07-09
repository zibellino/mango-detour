package zibellino.mango.detour

import android.content.Context
import kotlin.math.floor

/**
 * EGM96 geoid undulation lookup (N = height of the geoid above the WGS84
 * ellipsoid, in meters), using the standard NGA 15-arcminute global grid
 * bundled at assets/egm96/WW15MGH.DAC (721 rows x 1440 columns, big-endian
 * signed 16-bit centimeters, north-to-south from +90 lat, west-to-east
 * from 0 lon).
 *
 * This is what separates GNSS's ellipsoidal altitude from an orthometric
 * (mean-sea-level-relative) altitude like SRTM's:
 *   orthometricHeight = ellipsoidalHeight - N
 *
 * The whole grid is ~2MB, small enough to load fully into memory once and
 * do local bilinear-interpolated lookups with zero network and zero
 * per-call cost after the first load.
 */
class GeoidModel(private val context: Context) {

    private var grid: ShortArray? = null

    private val rows = 721 // 180 degrees / 0.25 degree spacing + 1
    private val cols = 1440 // 360 degrees / 0.25 degree spacing

    /** Returns the geoid undulation N (meters) at (lat, lon) in degrees. */
    fun undulationMeters(latDeg: Double, lonDeg: Double): Double {
        val data = loadGridIfNeeded()

        var recordIndex = (90.0 - latDeg) / 0.25
        recordIndex = recordIndex.coerceIn(0.0, rows - 1.0)

        var lon = lonDeg % 360.0
        if (lon < 0) lon += 360.0
        var heightIndex = lon / 0.25
        heightIndex = heightIndex.coerceIn(0.0, cols.toDouble())

        val j = floor(recordIndex).toInt()
        val i = floor(heightIndex).toInt()

        val xFrac = heightIndex - i
        val yFrac = recordIndex - j

        val f11 = sample(data, j, i)
        val f21 = sample(data, j, i + 1)
        val f12 = sample(data, j + 1, i)
        val f22 = sample(data, j + 1, i + 1)

        val top = f11 * (1 - xFrac) + f21 * xFrac
        val bottom = f12 * (1 - xFrac) + f22 * xFrac
        val centimeters = top * (1 - yFrac) + bottom * yFrac

        return centimeters / 100.0
    }

    private fun sample(data: ShortArray, recordIndexIn: Int, heightIndexIn: Int): Double {
        val recordIndex = recordIndexIn.coerceIn(0, rows - 1)
        var heightIndex = heightIndexIn
        if (heightIndex >= cols) heightIndex -= cols
        if (heightIndex < 0) heightIndex += cols
        return data[recordIndex * cols + heightIndex].toDouble()
    }

    private fun loadGridIfNeeded(): ShortArray {
        grid?.let { return it }

        val bytes = context.assets.open("egm96/WW15MGH.DAC").use { it.readBytes() }
        val shorts = ShortArray(bytes.size / 2)
        for (k in shorts.indices) {
            val hi = bytes[k * 2].toInt() and 0xFF
            val lo = bytes[k * 2 + 1].toInt() and 0xFF
            shorts[k] = ((hi shl 8) or lo).toShort() // big-endian signed 16-bit
        }
        grid = shorts
        return shorts
    }
}
