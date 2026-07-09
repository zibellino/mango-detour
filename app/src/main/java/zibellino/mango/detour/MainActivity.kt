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
import kotlin.math.cos

private const val LOG_INTERVAL_MS = 10_000L
private const val METERS_PER_DEGREE_LAT = 111_320.0

/**
 * One raw, unprocessed log row. Every field is logged as-is from its
 * source — no filtering, no fusion, no correction. The point of this
 * screen is to eyeball how the three altitude sources actually agree or
 * disagree in practice before writing any correction logic.
 */
data class LogRow(
    val timestampMillis: Long,
    val lat: Double,
    val lng: Double,
    val xMeters: Double,
    val yMeters: Double,
    val gpsAltitude: Double?,
    val baroAltitude: Double?,
    val srtmElevation: Double?,
)

class MainActivity : ComponentActivity() {

    private lateinit var fusedLocationClient: FusedLocationProviderClient
    private lateinit var sensorManager: SensorManager
    private var pressureSensor: Sensor? = null

    private val logHandler = Handler(Looper.getMainLooper())
    private var logRunnable: Runnable? = null

    private var latestLocation: Location? = null
    private var latestBaroAltitude: Double? = null

    // First fix of the session; xMeters/yMeters are a flat local-plane
    // approximation (equirectangular projection) relative to this, valid
    // for the small distances a walking/driving session covers.
    private var originLat: Double? = null
    private var originLon: Double? = null

    private var locationCallback: LocationCallback? = null
    private var sensorListener: SensorEventListener? = null

    private val logRows = mutableStateListOf<LogRow>()
    private var isRecording by mutableStateOf(false)

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
        ElevationClient.init(this)

        setContent {
            MaterialTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    RecorderScreen(
                        isRecording = isRecording,
                        hasBarometer = pressureSensor != null,
                        rows = logRows,
                        onToggle = ::onToggleClicked,
                    )
                }
            }
        }
    }

    private fun onToggleClicked() {
        if (isRecording) {
            stopRecording()
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
        logRows.clear()
        latestLocation = null
        latestBaroAltitude = null
        originLat = null
        originLon = null

        // Barometer: keep only the latest raw reading, nothing else.
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
        val baro = latestBaroAltitude
        val timestamp = System.currentTimeMillis()

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
                    baroAltitude = baro,
                    srtmElevation = srtmElevation,
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
        locationCallback?.let { fusedLocationClient.removeLocationUpdates(it) }
        locationCallback = null
        sensorListener?.let { sensorManager.unregisterListener(it) }
        sensorListener = null
        isRecording = false
    }

    override fun onDestroy() {
        logRunnable?.let { logHandler.removeCallbacks(it) }
        locationCallback?.let { fusedLocationClient.removeLocationUpdates(it) }
        sensorListener?.let { sensorManager.unregisterListener(it) }
        super.onDestroy()
    }
}

@Composable
private fun RecorderScreen(
    isRecording: Boolean,
    hasBarometer: Boolean,
    rows: List<LogRow>,
    onToggle: () -> Unit,
) {
    Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
        Button(onClick = onToggle, modifier = Modifier.fillMaxWidth()) {
            Text(if (isRecording) "Stop Recording" else "Start Recording")
        }

        Spacer(modifier = Modifier.height(8.dp))

        if (!hasBarometer) {
            Text("No barometer on this device — baroAltitude will always be blank.")
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

private fun formatRow(row: LogRow): String {
    val time = timeFormat.format(Date(row.timestampMillis))
    val gps = row.gpsAltitude?.let { "%.2f".format(it) } ?: "-"
    val baro = row.baroAltitude?.let { "%.2f".format(it) } ?: "-"
    val srtm = row.srtmElevation?.let { "%.2f".format(it) } ?: "-"
    return "[$time] lat=%.6f lng=%.6f  x=%.1fm y=%.1fm\n  gpsAlt=$gps  baroAlt=$baro  srtmElev=$srtm"
        .format(row.lat, row.lng, row.xMeters, row.yMeters)
}
