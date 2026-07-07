package zibellino.mango.detour

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.RandomAccessFile
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale
import java.util.zip.GZIPInputStream
import kotlin.math.floor

/**
 * Looks up ground elevation from SRTM1 (30m) tiles, downloaded on demand
 * from AWS's public "elevation-tiles-prod" bucket (Mapzen's Skadi layout)
 * and cached locally. No API key, no billing, no per-caller rate limit —
 * once a tile is cached, lookups in that 1x1 degree area are pure local
 * file reads and never touch the network again.
 *
 * Tile layout: https://s3.amazonaws.com/elevation-tiles-prod/skadi/{latDir}/{name}.hgt.gz
 * e.g. lat=48.21, lon=16.36  ->  skadi/N48/N48E016.hgt.gz
 *
 * Each .hgt file is a raw grid of big-endian signed 16-bit elevation
 * samples (meters), north-to-south, west-to-east, SRTM1 = 3601x3601
 * samples covering exactly one degree of latitude and longitude.
 * Void/no-data samples are encoded as -32768.
 */
class SrtmElevationProvider(context: Context) {

    private val cacheDir: File = File(context.filesDir, "srtm").apply { mkdirs() }
    private val gridSize = 3601 // SRTM1: samples per tile edge
    private val bytesPerSample = 2

    /**
     * Returns the interpolated elevation in meters at (lat, lon), or null
     * if the tile couldn't be fetched or the samples nearest to the point
     * are void (e.g. over open ocean in some SRTM gaps).
     */
    suspend fun getElevation(lat: Double, lon: Double): Double? = withContext(Dispatchers.IO) {
        val tileName = tileName(lat, lon)
        val tileFile = ensureTileCached(tileName) ?: return@withContext null
        readInterpolatedElevation(tileFile, lat, lon)
    }

    // --- Tile naming -------------------------------------------------

    private fun tileName(lat: Double, lon: Double): String {
        val latFloor = floor(lat).toInt()
        val lonFloor = floor(lon).toInt()
        val latPart = "%s%02d".format(if (latFloor >= 0) "N" else "S", kotlin.math.abs(latFloor))
        val lonPart = "%s%03d".format(if (lonFloor >= 0) "E" else "W", kotlin.math.abs(lonFloor))
        return "$latPart$lonPart"
    }

    // --- Caching / download -------------------------------------------

    private fun ensureTileCached(tileName: String): File? {
        val cached = File(cacheDir, "$tileName.hgt")
        if (cached.exists() && cached.length() == (gridSize.toLong() * gridSize * bytesPerSample)) {
            return cached
        }

        val latDir = tileName.substring(0, 3) // e.g. "N48"
        val url = "https://s3.amazonaws.com/elevation-tiles-prod/skadi/$latDir/$tileName.hgt.gz"

        return try {
            val connection = URL(url).openConnection() as HttpURLConnection
            connection.connectTimeout = 15_000
            connection.readTimeout = 30_000
            connection.connect()

            if (connection.responseCode != HttpURLConnection.HTTP_OK) {
                connection.disconnect()
                return null // e.g. tile is entirely open ocean and doesn't exist
            }

            val tempFile = File(cacheDir, "$tileName.hgt.tmp")
            GZIPInputStream(connection.inputStream).use { gzipIn ->
                tempFile.outputStream().use { out -> gzipIn.copyTo(out) }
            }
            connection.disconnect()

            if (tempFile.length() != (gridSize.toLong() * gridSize * bytesPerSample)) {
                tempFile.delete()
                return null // unexpected size, don't cache a corrupt tile
            }

            tempFile.renameTo(cached)
            cached
        } catch (e: Exception) {
            null
        }
    }

    // --- Local lookup ---------------------------------------------------

    private fun readInterpolatedElevation(tileFile: File, lat: Double, lon: Double): Double? {
        val tileLat = floor(lat)
        val tileLon = floor(lon)

        // Fractional position within the tile, 0.0 at the south/west edge.
        val fracLat = lat - tileLat
        val fracLon = lon - tileLon

        // Row 0 is the north edge, so invert fracLat for row indexing.
        val rowF = (1.0 - fracLat) * (gridSize - 1)
        val colF = fracLon * (gridSize - 1)

        val row0 = floor(rowF).toInt().coerceIn(0, gridSize - 2)
        val col0 = floor(colF).toInt().coerceIn(0, gridSize - 2)
        val row1 = row0 + 1
        val col1 = col0 + 1

        val tRow = rowF - row0
        val tCol = colF - col0

        RandomAccessFile(tileFile, "r").use { raf ->
            val s00 = readSample(raf, row0, col0) ?: return null
            val s01 = readSample(raf, row0, col1) ?: return null
            val s10 = readSample(raf, row1, col0) ?: return null
            val s11 = readSample(raf, row1, col1) ?: return null

            // Bilinear interpolation between the four surrounding samples.
            val top = s00 * (1 - tCol) + s01 * tCol
            val bottom = s10 * (1 - tCol) + s11 * tCol
            return top * (1 - tRow) + bottom * tRow
        }
    }

    /** Returns null if the sample is SRTM's void marker (-32768). */
    private fun readSample(raf: RandomAccessFile, row: Int, col: Int): Double? {
        val offset = (row.toLong() * gridSize + col) * bytesPerSample
        raf.seek(offset)
        val high = raf.read()
        val low = raf.read()
        if (high == -1 || low == -1) return null
        val value = ((high shl 8) or low).toShort().toInt() // big-endian signed 16-bit
        return if (value == -32768) null else value.toDouble()
    }
}
