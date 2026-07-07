package zibellino.mango.detour

import android.Manifest
import android.content.pm.PackageManager
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
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
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * A single raw sample taken during a recording session, before any
 * correction is applied.
 *
 * x/y come from Fused Location (GNSS blended with Wi-Fi/cell) — altitude
 * from that provider is intentionally never read; it's too weak to be
 * worth combining with the barometer once the Elevation API is in play.
 *
 * baroAltitude is the barometer's raw reading (SensorManager.getAltitude
 * against the standard sea-level pressure constant) — it drifts with
 * weather but captures real elevation *change* faithfully in the short
 * term, which is exactly what we want it for.
 *
 * apiElevation is the Elevation API's ground-truth lookup for (x, y) at
 * the moment of this ping — a DEM value, accurate to roughly 1-4m in flat
 * terrain, used purely to correct the barometer's drift, never as a
 * live position source itself.
 */
data class PingSample(
    val timestampMillis: Long,
    val x: Double,
    val y: Double,
    val baroAltitude: Double,
    val apiElevation: Double?,
)

/**
 * A ping after post-session correction.
 *
 * correctedZ = baroAltitude - fittedOffset, where fittedOffset is a
 * regression fit of (baroAltitude - apiElevation) over time across the
 * whole session. Real elevation change cancels out of that subtraction
 * (it's present in both baroAltitude and apiElevation), so only sensor
 * bias/drift and per-point DEM noise remain to be fitted and removed.
 */
data class CorrectedPoint(
    val timestampMillis: Long,
    val x: Double,
    val y: Double,
    val correctedZ: Double,
)

/** Least-squares fit of offset(t) = intercept + slope * t. */
private data class LinearFit(val intercept: Double, val slope: Double, val residualRmsM: Double)

private fun fitLinearDrift(points: List<Pair<Double, Double>>): LinearFit {
    // points: (tSeconds, offset)
    if (points.size < 2) {
        val flat = points.firstOrNull()?.second ?: 0.0
        return LinearFit(intercept = flat, slope = 0.0, residualRmsM = 0.0)
    }

    val n = points.size
    val sumT = points.sumOf { it.first }
    val sumO = points.sumOf { it.second }
    val meanT = sumT / n
    val meanO = sumO / n

    var covariance = 0.0
    var variance = 0.0
    for ((t, o) in points) {
        covariance += (t - meanT) * (o - meanO)
        variance += (t - meanT) * (t - meanT)
    }

    val slope = if (variance > 1e-9) covariance / variance else 0.0
    val intercept = meanO - slope * meanT

    var residualSquaredSum = 0.0
    for ((t, o) in points) {
        val fitted = intercept + slope * t
        val residual = o - fitted
        residualSquaredSum += residual * residual
    }
    val residualRms = kotlin.math.sqrt(residualSquaredSum / n)

    return LinearFit(intercept, slope, residualRms)
}

private const val PING_INTERVAL_MS = 5_000L

class MainActivity : ComponentActivity() {

    private lateinit var fusedLocationClient: FusedLocationProviderClient
    private lateinit var sensorManager: SensorManager
    private var pressureSensor: Sensor? = null

    private val pingHandler = Handler(Looper.getMainLooper())
    private var pingRunnable: Runnable? = null

    private var latestLocation: Location? = null
    private var latestBaroAltitude: Double? = null

    private var locationCallback: LocationCallback? = null
    private var sensorListener: SensorEventListener? = null

    private val rawPings = mutableListOf<PingSample>()
    private var isRecording by mutableStateOf(false)
    private var isProcessing by mutableStateOf(false)
    private var rawPingCount by mutableStateOf(0)
    private var residualRmsM by mutableStateOf<Double?>(null)
    private val correctedPoints = mutableStateListOf<CorrectedPoint>()

    private val requestPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) {
                beginRecording()
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        fusedLocationClient = LocationServices.getFusedLocationProviderClient(this)
        sensorManager = getSystemService(SENSOR_SERVICE) as SensorManager
        pressureSensor = sensorManager.getDefaultSensor(Sensor.TYPE_PRESSURE)

        setContent {
            MaterialTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    RecorderScreen(
                        isRecording = isRecording,
                        isProcessing = isProcessing,
                        hasBarometer = pressureSensor != null,
                        hasApiKey = BuildConfig.ELEVATION_API_KEY.isNotBlank(),
                        rawPingCount = rawPingCount,
                        residualRmsM = residualRmsM,
                        points = correctedPoints,
                        onToggle = ::onToggleClicked,
                    )
                }
            }
        }
    }

    private fun onToggleClicked() {
        if (isRecording) {
            stopRecordingAndProcess()
            return
        }

        val hasPermission = ContextCompat.checkSelfPermission(
            this,
            Manifest.permission.ACCESS_FINE_LOCATION,
        ) == PackageManager.PERMISSION_GRANTED

        if (hasPermission) {
            beginRecording()
        } else {
            requestPermissionLauncher.launch(Manifest.permission.ACCESS_FINE_LOCATION)
        }
    }

    private fun beginRecording() {
        rawPings.clear()
        correctedPoints.clear()
        rawPingCount = 0
        residualRmsM = null
        latestLocation = null
        latestBaroAltitude = null

        // Barometer: keep only the latest raw reading. No filtering here —
        // correction happens entirely in post-processing.
        pressureSensor?.let { sensor ->
            val listener = object : SensorEventListener {
                override fun onSensorChanged(event: SensorEvent) {
                    latestBaroAltitude = SensorManager.getAltitude(
                        SensorManager.PRESSURE_STANDARD_ATMOSPHERE,
                        event.values[0],
                    ).toDouble()
                }

                override fun onAccuracyChanged(sensor: Sensor, accuracy: Int) = Unit
            }
            sensorListener = listener
            sensorManager.registerListener(listener, sensor, SensorManager.SENSOR_DELAY_UI)
        }

        // Fused location for x/y only — altitude from this provider is
        // never read.
        val hasPermission = ContextCompat.checkSelfPermission(
            this,
            Manifest.permission.ACCESS_FINE_LOCATION,
        ) == PackageManager.PERMISSION_GRANTED
        if (!hasPermission) return

        val request = LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, 5_000L)
            .setMinUpdateIntervalMillis(2_000L)
            .build()

        val callback = object : LocationCallback() {
            override fun onLocationResult(result: LocationResult) {
                result.lastLocation?.let { latestLocation = it }
            }
        }
        locationCallback = callback

        try {
            fusedLocationClient.requestLocationUpdates(request, callback, Looper.getMainLooper())
        } catch (e: SecurityException) {
            locationCallback = null
            return
        }

        isRecording = true
        schedulePing()
    }

    /** Every PING_INTERVAL_MS: snapshot x/y + barometer, look up ground elevation, log raw. */
    private fun schedulePing() {
        val runnable = object : Runnable {
            override fun run() {
                takePing()
                pingHandler.postDelayed(this, PING_INTERVAL_MS)
            }
        }
        pingRunnable = runnable
        pingHandler.postDelayed(runnable, PING_INTERVAL_MS)
    }

    private fun takePing() {
        val location = latestLocation ?: return
        val baro = latestBaroAltitude ?: return
        val timestamp = System.currentTimeMillis()
        val x = location.latitude
        val y = location.longitude

        lifecycleScope.launch {
            val apiElevation = ElevationClient.fetchElevation(x, y)
            rawPings.add(
                PingSample(
                    timestampMillis = timestamp,
                    x = x,
                    y = y,
                    baroAltitude = baro,
                    apiElevation = apiElevation,
                ),
            )
            rawPingCount = rawPings.size
        }
    }

    private fun stopRecordingAndProcess() {
        pingRunnable?.let { pingHandler.removeCallbacks(it) }
        pingRunnable = null
        locationCallback?.let { fusedLocationClient.removeLocationUpdates(it) }
        locationCallback = null
        sensorListener?.let { sensorManager.unregisterListener(it) }
        sensorListener = null
        isRecording = false
        isProcessing = true

        lifecycleScope.launch {
            processSession()
            isProcessing = false
        }
    }

    /**
     * offset(t) = baroAltitude(t) - apiElevation(t) for every ping that got
     * a valid Elevation API result. True elevation change is present in
     * both terms and cancels out, leaving barometer bias/drift plus
     * per-point DEM noise — exactly what we want the regression to model
     * and remove. correctedZ = baroAltitude - fittedOffset(t).
     */
    private fun processSession() {
        val usablePings = rawPings.filter { it.apiElevation != null }
        if (usablePings.isEmpty()) {
            // No successful Elevation API lookups (e.g. no network, no API
            // key, or every request failed) — fall back to raw barometer
            // readings with no drift correction, since that's still better
            // than nothing.
            correctedPoints.clear()
            correctedPoints.addAll(
                rawPings.map {
                    CorrectedPoint(it.timestampMillis, it.x, it.y, it.baroAltitude)
                },
            )
            residualRmsM = null
            return
        }

        val startMillis = usablePings.first().timestampMillis
        val fitInput = usablePings.map { ping ->
            val tSeconds = (ping.timestampMillis - startMillis) / 1000.0
            val offset = ping.baroAltitude - ping.apiElevation!!
            tSeconds to offset
        }
        val fit = fitLinearDrift(fitInput)

        correctedPoints.clear()
        correctedPoints.addAll(
            rawPings.map { ping ->
                val tSeconds = (ping.timestampMillis - startMillis) / 1000.0
                val fittedOffset = fit.intercept + fit.slope * tSeconds
                val correctedZ = ping.baroAltitude - fittedOffset
                CorrectedPoint(ping.timestampMillis, ping.x, ping.y, correctedZ)
            },
        )
        residualRmsM = fit.residualRmsM
    }

    override fun onDestroy() {
        pingRunnable?.let { pingHandler.removeCallbacks(it) }
        locationCallback?.let { fusedLocationClient.removeLocationUpdates(it) }
        sensorListener?.let { sensorManager.unregisterListener(it) }
        super.onDestroy()
    }
}

@Composable
private fun RecorderScreen(
    isRecording: Boolean,
    isProcessing: Boolean,
    hasBarometer: Boolean,
    hasApiKey: Boolean,
    rawPingCount: Int,
    residualRmsM: Double?,
    points: List<CorrectedPoint>,
    onToggle: () -> Unit,
) {
    Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
        Button(
            onClick = onToggle,
            enabled = !isProcessing,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(
                when {
                    isProcessing -> "Processing..."
                    isRecording -> "Stop Recording"
                    else -> "Start Recording"
                },
            )
        }

        Spacer(modifier = Modifier.height(8.dp))

        if (!hasBarometer) {
            Text("No barometer on this device — elevation correction is unavailable.")
            Spacer(modifier = Modifier.height(8.dp))
        }
        if (!hasApiKey) {
            Text("No ELEVATION_API_KEY configured — falls back to raw, uncorrected barometer readings.")
            Spacer(modifier = Modifier.height(8.dp))
        }

        if (isRecording) {
            Text("Raw pings logged: $rawPingCount")
        } else {
            Text("Corrected points: ${points.size}")
            residualRmsM?.let {
                Text("Drift-fit residual: ±%.2fm (1-sigma, this session)".format(it))
            }
        }

        Spacer(modifier = Modifier.height(8.dp))

        LazyColumn {
            items(points) { point ->
                Text(formatPoint(point))
            }
        }
    }
}

private val timeFormat = SimpleDateFormat("HH:mm:ss", Locale.US)

private fun formatPoint(point: CorrectedPoint): String {
    val time = timeFormat.format(Date(point.timestampMillis))
    return "[$time] x=%.6f  y=%.6f  z=%.2f".format(point.x, point.y, point.correctedZ)
}
