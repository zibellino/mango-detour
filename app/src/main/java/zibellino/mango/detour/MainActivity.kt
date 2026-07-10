package zibellino.mango.detour

import android.Manifest
import android.content.pm.PackageManager
import android.location.Location
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.google.android.gms.location.FusedLocationProviderClient
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import kotlinx.coroutines.launch
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.cos

private const val LOG_INTERVAL_MS = 5_000L
private const val METERS_PER_DEGREE_LAT = 111_320.0

/** Minimum horizontal accuracy (meters) before we let the user start recording. */
private const val READY_ACCURACY_THRESHOLD_M = 10f

private val EMA_ALPHAS = listOf(0.4, 0.5, 0.6)

/**
 * One raw, unprocessed log row (plus the EMA-smoothed variants of
 * gpsAltCorrected). No barometer — dropped after testing showed it wasn't
 * earning its complexity: its short-term precision was fine, but its
 * absolute drift meant it never improved on already-good corrected GPS,
 * and this app's outdoor use case rarely stresses GPS enough to need a
 * fallback source. SRTM stays logged for now while the large mismatch
 * against gpsAltCorrected in some sessions is still being investigated.
 */
data class LogRow(
    val timestampMillis: Long,
    val lat: Double,
    val lng: Double,
    val xMeters: Double,
    val yMeters: Double,
    val gpsAltitude: Double?,
    val correctedGpsAltitude: Double?,
    val srtmElevation: Double?,
    val emaByAlpha: Map<Double, Double?>,
)

class MainActivity : ComponentActivity() {

    private lateinit var fusedLocationClient: FusedLocationProviderClient
    private lateinit var geoidModel: GeoidModel

    private val logHandler = Handler(Looper.getMainLooper())
    private var logRunnable: Runnable? = null

    private var latestLocation: Location? = null

    // First fix of the session; xMeters/yMeters are a flat local-plane
    // approximation (equirectangular projection) relative to this, valid
    // for the small distances a walking/driving session covers.
    private var originLat: Double? = null
    private var originLon: Double? = null

    // Running EMA state per alpha, keyed the same as EMA_ALPHAS.
    private val emaState = mutableMapOf<Double, Double>()

    private var locationCallback: LocationCallback? = null

    private val logRows = mutableStateListOf<LogRow>()
    private var isRecording by mutableStateOf(false)
    private var isLocationReady by mutableStateOf(false)
    private var currentAccuracyM by mutableStateOf<Float?>(null)

    private val requestPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) {
                startLocationWarmup()
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        fusedLocationClient = LocationServices.getFusedLocationProviderClient(this)
        ElevationClient.init(this)
        geoidModel = GeoidModel(this)

        setContent {
            MaterialTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    RecorderScreen(
                        isRecording = isRecording,
                        isLocationReady = isLocationReady,
                        currentAccuracyM = currentAccuracyM,
                        rows = logRows,
                        onToggle = ::onToggleClicked,
                    )
                }
            }
        }

        val hasPermission = ContextCompat.checkSelfPermission(
            this,
            Manifest.permission.ACCESS_FINE_LOCATION,
        ) == PackageManager.PERMISSION_GRANTED

        if (hasPermission) {
            startLocationWarmup()
        } else {
            requestPermissionLauncher.launch(Manifest.permission.ACCESS_FINE_LOCATION)
        }
    }

    /**
     * Starts GNSS as soon as we have permission — well before the user
     * taps "Start Recording" — so it's had time to converge by then.
     * isLocationReady gates the Start button so the user isn't recording
     * garbage fixes from a cold GNSS lock.
     */
    private fun startLocationWarmup() {
        val hasPermission = ContextCompat.checkSelfPermission(
            this,
            Manifest.permission.ACCESS_FINE_LOCATION,
        ) == PackageManager.PERMISSION_GRANTED
        if (!hasPermission) return

        val request = LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, 2_000L)
            .setMinUpdateIntervalMillis(1_000L)
            .build()

        val callback = object : LocationCallback() {
            override fun onLocationResult(result: LocationResult) {
                val location = result.lastLocation ?: return
                latestLocation = location
                currentAccuracyM = if (location.hasAccuracy()) location.accuracy else null
                isLocationReady = location.hasAccuracy() && location.accuracy <= READY_ACCURACY_THRESHOLD_M
            }
        }
        locationCallback = callback

        try {
            fusedLocationClient.requestLocationUpdates(request, callback, Looper.getMainLooper())
        } catch (e: SecurityException) {
            locationCallback = null
        }
    }

    private fun onToggleClicked() {
        if (isRecording) {
            stopRecording()
            return
        }
        if (!isLocationReady) return
        beginRecording()
    }

    private fun beginRecording() {
        logRows.clear()
        originLat = null
        originLon = null
        emaState.clear()

        isRecording = true
        takeLogRow() // log immediately, then settle into the periodic interval
        scheduleLogging()
    }

    private fun scheduleLogging() {
        val runnable = object : Runnable {
            override fun run() {
                takeLogRow()
                logHandler.postDelayed(this, LOG_INTERVAL_MS)
            }
        }
        logRunnable = runnable
        logHandler.postDelayed(runnable, LOG_INTERVAL_MS)
    }

    private fun takeLogRow() {
        val location = latestLocation ?: return
        val lat = location.latitude
        val lng = location.longitude

        if (originLat == null) {
            originLat = lat
            originLon = lng
        }

        val (xMeters, yMeters) = localMeters(lat, lng)
        val gpsAltitude = if (location.hasAltitude()) location.altitude else null
        val correctedGpsAltitude = gpsAltitude?.let { it - geoidModel.undulationMeters(lat, lng) }
        val timestamp = System.currentTimeMillis()

        // Update each EMA(alpha) with the latest corrected GPS altitude, if we have one.
        val emaSnapshot = mutableMapOf<Double, Double?>()
        if (correctedGpsAltitude != null) {
            for (alpha in EMA_ALPHAS) {
                val previous = emaState[alpha]
                val updated = if (previous == null) {
                    correctedGpsAltitude
                } else {
                    alpha * correctedGpsAltitude + (1 - alpha) * previous
                }
                emaState[alpha] = updated
                emaSnapshot[alpha] = updated
            }
        } else {
            for (alpha in EMA_ALPHAS) emaSnapshot[alpha] = emaState[alpha]
        }

        lifecycleScope.launch {
            val srtmElevation = ElevationClient.fetchElevation(lat, lng)
            logRows.add(
                0,
                LogRow(
                    timestampMillis = timestamp,
                    lat = lat,
                    lng = lng,
                    xMeters = xMeters,
                    yMeters = yMeters,
                    gpsAltitude = gpsAltitude,
                    correctedGpsAltitude = correctedGpsAltitude,
                    srtmElevation = srtmElevation,
                    emaByAlpha = emaSnapshot,
                ),
            )
        }
    }

    /**
     * Flat local-plane approximation relative to the session's first fix
     * (equirectangular projection). Fine for the scale of a single
     * session; not meant for anything spanning a large area.
     */
    private fun localMeters(lat: Double, lng: Double): Pair<Double, Double> {
        val oLat = originLat ?: lat
        val oLon = originLon ?: lng
        val metersPerDegreeLon = METERS_PER_DEGREE_LAT * cos(Math.toRadians(oLat))
        val xMeters = (lng - oLon) * metersPerDegreeLon
        val yMeters = (lat - oLat) * METERS_PER_DEGREE_LAT
        return xMeters to yMeters
    }

    private fun stopRecording() {
        logRunnable?.let { logHandler.removeCallbacks(it) }
        logRunnable = null
        isRecording = false
        // GNSS keeps running (warm) in case the user starts again.
        saveSessionLog()
    }

    /**
     * Writes every logged row, oldest first, as plain text — the same
     * format shown on screen — to the app's external files directory
     * (Android/data/zibellino.mango.detour/files/), one file per session.
     * No special storage permission needed on modern Android since this
     * is app-scoped external storage.
     */
    private fun saveSessionLog() {
        if (logRows.isEmpty()) return
        val dir = getExternalFilesDir(null) ?: return
        val filename = "session_${fileTimeFormat.format(Date())}.log"
        try {
            File(dir, filename).bufferedWriter().use { writer ->
                logRows.asReversed().forEach { row ->
                    writer.write(formatRow(row))
                    writer.newLine()
                }
            }
        } catch (e: Exception) {
            // Best-effort — a failed save shouldn't crash the session.
        }
    }

    override fun onDestroy() {
        logRunnable?.let { logHandler.removeCallbacks(it) }
        locationCallback?.let { fusedLocationClient.removeLocationUpdates(it) }
        super.onDestroy()
    }
}

@Composable
private fun RecorderScreen(
    isRecording: Boolean,
    isLocationReady: Boolean,
    currentAccuracyM: Float?,
    rows: List<LogRow>,
    onToggle: () -> Unit,
) {
    Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
        Button(
            onClick = onToggle,
            enabled = isRecording || isLocationReady,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(
                when {
                    isRecording -> "Stop Recording"
                    isLocationReady -> "Start Recording"
                    else -> "Waiting for GPS fix..."
                },
            )
        }

        Spacer(modifier = Modifier.height(8.dp))

        currentAccuracyM?.let {
            Text("Current fix accuracy: ±%.1fm".format(it))
            Spacer(modifier = Modifier.height(8.dp))
        }

        Text("Rows logged: ${rows.size}")

        Spacer(modifier = Modifier.height(8.dp))

        LazyColumn {
            items(rows) { row ->
                Text(formatRow(row))
                Spacer(modifier = Modifier.height(6.dp))
            }
        }
    }
}

private val timeFormat = SimpleDateFormat("HH:mm:ss", Locale.US)
private val fileTimeFormat = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US)

private fun formatRow(row: LogRow): String {
    val time = timeFormat.format(Date(row.timestampMillis))
    val gps = row.gpsAltitude?.let { "%.2f".format(it) } ?: "-"
    val gpsCorrected = row.correctedGpsAltitude?.let { "%.2f".format(it) } ?: "-"
    val srtm = row.srtmElevation?.let { "%.2f".format(it) } ?: "-"
    val ema04 = row.emaByAlpha[0.4]?.let { "%.2f".format(it) } ?: "-"
    val ema05 = row.emaByAlpha[0.5]?.let { "%.2f".format(it) } ?: "-"
    val ema06 = row.emaByAlpha[0.6]?.let { "%.2f".format(it) } ?: "-"
    return ("[$time] lat=%.6f lng=%.6f  x=%.1fm y=%.1fm\n" +
        "  gpsAlt=$gps  gpsAltCorrected=$gpsCorrected  srtmElev=$srtm\n" +
        "  ema0.4=$ema04  ema0.5=$ema05  ema0.6=$ema06")
        .format(row.lat, row.lng, row.xMeters, row.yMeters)
}
