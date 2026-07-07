package zibellino.mango.detour

import android.Manifest
import android.content.pm.PackageManager
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.location.Location
import android.os.Bundle
import android.os.Looper
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
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
import com.google.android.gms.location.CurrentLocationRequest
import com.google.android.gms.location.FusedLocationProviderClient
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.sqrt

/**
 * One recorded fix. x = latitude, y = longitude, z = altitude in meters.
 *
 * x/y come from Google Play services' Fused Location Provider, which
 * blends GNSS with Wi-Fi and cell-tower positioning (whichever sources are
 * available) rather than raw GPS_PROVIDER alone. horizontalAccuracyM is the
 * 68%-confidence radius Fused Location reports for that blended fix.
 *
 * z comes from a Kalman filter that fuses the barometer (high-resolution,
 * but drifts with weather since it assumes a reference sea-level pressure)
 * with GNSS altitude (absolute, but noisy and geometrically weak — see
 * verticalAccuracyM on a raw fix). verticalAccuracyM here is the filter's
 * own 1-sigma uncertainty on z, which is generally tighter than a raw GNSS
 * vertical accuracy once the filter has had a few fixes to lock onto the
 * barometer's bias.
 */
data class TrackPoint(
    val timestampMillis: Long,
    val x: Double,
    val y: Double,
    val z: Double,
    val horizontalAccuracyM: Float?,
    val verticalAccuracyM: Float?,
)

/**
 * Two-state Kalman filter fusing barometric altitude with GNSS altitude.
 *
 * State: [z, bias]
 *   z    = true altitude (meters)
 *   bias = offset between the barometer's raw altitude reading and z,
 *          caused by the barometer assuming a fixed standard sea-level
 *          pressure (1013.25 hPa) instead of today's actual local
 *          sea-level pressure. This offset drifts slowly as weather
 *          changes but is effectively constant over minutes.
 *
 * Barometer measurement model: measuredBaroAltitude = z + bias + noise
 * GNSS measurement model:      measuredGpsAltitude  = z + noise
 *
 * Neither source alone can separate z from bias (the barometer is fast but
 * biased; GNSS is unbiased but slow/noisy/geometrically weak vertically) —
 * fusing both is what lets the filter converge on a tight, driftless z.
 */
class AltitudeFusionFilter(
    private val processNoiseZ: Double = 0.30,       // m^2/s, allows z to move with the phone
    private val processNoiseBias: Double = 0.0004,  // m^2/s, bias drifts slowly (weather)
    private val baroMeasurementNoise: Double = 0.03, // m^2, barometer is precise sample-to-sample
    private val defaultGpsVariance: Double = 225.0,  // m^2 (~15m) if a fix has no reported accuracy
) {
    private var z = 0.0
    private var bias = 0.0
    // Covariance matrix P (2x2), row-major: [[Pzz, Pzb], [Pbz, Pbb]]
    private var p = arrayOf(doubleArrayOf(400.0, 0.0), doubleArrayOf(0.0, 100.0))
    private var initialized = false
    private var lastBaroReading: Double? = null

    val currentZ: Double get() = z
    val currentZStdDevM: Double get() = sqrt(p[0][0].coerceAtLeast(0.0))

    /** Advances process noise to account for elapsed time since the last update. */
    private fun predict(dtSeconds: Double) {
        val dt = dtSeconds.coerceIn(0.0, 5.0) // clamp to avoid a huge gap blowing up covariance
        p[0][0] += processNoiseZ * dt
        p[1][1] += processNoiseBias * dt
    }

    /** Feed a fresh barometric altitude reading (SensorManager.getAltitude output). */
    fun updateBaro(measuredBaroAltitude: Double, dtSeconds: Double) {
        if (!initialized) {
            z = measuredBaroAltitude
            bias = 0.0
            lastBaroReading = measuredBaroAltitude
            initialized = true
            return
        }
        predict(dtSeconds)

        // Measurement model H = [1, 1]: residual = measured - (z + bias)
        val residual = measuredBaroAltitude - (z + bias)
        val pzz = p[0][0]; val pzb = p[0][1]; val pbz = p[1][0]; val pbb = p[1][1]
        val s = pzz + pzb + pbz + pbb + baroMeasurementNoise
        val kz = (pzz + pzb) / s
        val kb = (pbz + pbb) / s

        z += kz * residual
        bias += kb * residual

        val newPzz = pzz - kz * (pzz + pzb)
        val newPzb = pzb - kz * (pzb + pbb)
        val newPbz = pbz - kb * (pzz + pzb)
        val newPbb = pbb - kb * (pzb + pbb)
        p = arrayOf(doubleArrayOf(newPzz, newPzb), doubleArrayOf(newPbz, newPbb))

        lastBaroReading = measuredBaroAltitude
    }

    /** Feed a fresh GNSS/fused-location altitude reading. */
    fun updateGps(measuredGpsAltitude: Double, verticalAccuracyM: Float?, dtSeconds: Double) {
        val r = verticalAccuracyM?.let { (it * it).toDouble() } ?: defaultGpsVariance

        if (!initialized) {
            z = measuredGpsAltitude
            bias = 0.0
            initialized = true
            return
        }
        predict(dtSeconds)

        // Measurement model H = [1, 0]: residual = measured - z
        val residual = measuredGpsAltitude - z
        val pzz = p[0][0]; val pzb = p[0][1]; val pbz = p[1][0]; val pbb = p[1][1]
        val s = pzz + r
        val kz = pzz / s
        val kb = pbz / s

        z += kz * residual
        bias += kb * residual

        val newPzz = pzz - kz * pzz
        val newPzb = pzb - kz * pzb
        val newPbz = pbz - kb * pzz
        val newPbb = pbb - kb * pzb
        p = arrayOf(doubleArrayOf(newPzz, newPzb), doubleArrayOf(newPbz, newPbb))
    }
}

class MainActivity : ComponentActivity() {

    private lateinit var fusedLocationClient: FusedLocationProviderClient
    private lateinit var sensorManager: SensorManager
    private var pressureSensor: Sensor? = null

    private val altitudeFilter = AltitudeFusionFilter()
    private var lastBaroTimestampNanos: Long? = null
    private var lastGpsTimestampMillis: Long? = null

    // Most recent horizontal fix; altitude is re-fused with the barometer
    // before we ever record a point, so we hang onto x/y + its accuracy here.
    private var latestHorizontal: Location? = null

    private var locationCallback: LocationCallback? = null
    private var sensorListener: SensorEventListener? = null

    private val points = mutableStateListOf<TrackPoint>()
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

        setContent {
            MaterialTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    RecorderScreen(
                        isRecording = isRecording,
                        hasBarometer = pressureSensor != null,
                        points = points,
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
        // Barometer: high-rate relative altitude, fused continuously.
        pressureSensor?.let { sensor ->
            val listener = object : SensorEventListener {
                override fun onSensorChanged(event: SensorEvent) {
                    val pressureHpa = event.values[0]
                    val baroAltitude = SensorManager.getAltitude(
                        SensorManager.PRESSURE_STANDARD_ATMOSPHERE,
                        pressureHpa,
                    ).toDouble()

                    val nowNanos = event.timestamp
                    val dtSeconds = lastBaroTimestampNanos?.let { (nowNanos - it) / 1e9 } ?: 0.0
                    lastBaroTimestampNanos = nowNanos

                    altitudeFilter.updateBaro(baroAltitude, dtSeconds)
                    maybeEmitPoint()
                }

                override fun onAccuracyChanged(sensor: Sensor, accuracy: Int) = Unit
            }
            sensorListener = listener
            sensorManager.registerListener(listener, sensor, SensorManager.SENSOR_DELAY_UI)
        }

        // Fused location: GNSS blended with Wi-Fi/cell for x/y, plus a GNSS
        // altitude that periodically corrects the barometer's drifting bias.
        val hasPermission = ContextCompat.checkSelfPermission(
            this,
            Manifest.permission.ACCESS_FINE_LOCATION,
        ) == PackageManager.PERMISSION_GRANTED
        if (!hasPermission) return

        val request = LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, 10_000L)
            .setMinUpdateIntervalMillis(2_000L)
            .build()

        val callback = object : LocationCallback() {
            override fun onLocationResult(result: LocationResult) {
                val location = result.lastLocation ?: return
                onNewFusedLocation(location)
            }
        }
        locationCallback = callback

        try {
            fusedLocationClient.requestLocationUpdates(request, callback, Looper.getMainLooper())
            isRecording = true
        } catch (e: SecurityException) {
            // Permission was revoked between the check above and this call.
            locationCallback = null
        }
    }

    private fun stopRecording() {
        locationCallback?.let { fusedLocationClient.removeLocationUpdates(it) }
        locationCallback = null
        sensorListener?.let { sensorManager.unregisterListener(it) }
        sensorListener = null
        lastBaroTimestampNanos = null
        lastGpsTimestampMillis = null
        isRecording = false
    }

    private fun onNewFusedLocation(location: Location) {
        latestHorizontal = location

        val nowMillis = System.currentTimeMillis()
        val dtSeconds = lastGpsTimestampMillis?.let { (nowMillis - it) / 1000.0 } ?: 0.0
        lastGpsTimestampMillis = nowMillis

        if (location.hasAltitude()) {
            val verticalAccuracy = if (location.hasVerticalAccuracy()) {
                location.verticalAccuracyMeters
            } else {
                null
            }
            altitudeFilter.updateGps(location.altitude, verticalAccuracy, dtSeconds)
        }

        maybeEmitPoint()
    }

    private fun maybeEmitPoint() {
        val horizontal = latestHorizontal ?: return
        points.add(
            0,
            TrackPoint(
                timestampMillis = System.currentTimeMillis(),
                x = horizontal.latitude,
                y = horizontal.longitude,
                z = altitudeFilter.currentZ,
                horizontalAccuracyM = if (horizontal.hasAccuracy()) horizontal.accuracy else null,
                verticalAccuracyM = altitudeFilter.currentZStdDevM.toFloat(),
            ),
        )
    }

    override fun onDestroy() {
        stopRecording()
        super.onDestroy()
    }
}

@Composable
private fun RecorderScreen(
    isRecording: Boolean,
    hasBarometer: Boolean,
    points: List<TrackPoint>,
    onToggle: () -> Unit,
) {
    Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
        Button(onClick = onToggle, modifier = Modifier.fillMaxWidth()) {
            Text(if (isRecording) "Stop Recording" else "Start Recording")
        }

        Spacer(modifier = Modifier.height(8.dp))

        if (!hasBarometer) {
            Text("No barometer on this device — z uses GNSS altitude only.")
            Spacer(modifier = Modifier.height(8.dp))
        }

        Text("Points recorded: ${points.size}")

        Spacer(modifier = Modifier.height(8.dp))

        LazyColumn(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            items(points) { point ->
                Text(formatPoint(point))
            }
        }
    }
}

private val timeFormat = SimpleDateFormat("HH:mm:ss", Locale.US)

private fun formatPoint(point: TrackPoint): String {
    val time = timeFormat.format(Date(point.timestampMillis))
    val hAcc = point.horizontalAccuracyM?.let { "±%.1fm".format(it) } ?: "±?"
    val vAcc = point.verticalAccuracyM?.let { "±%.1fm".format(it) } ?: "±?"
    return "[$time] x=%.6f  y=%.6f  (%s)   z=%.2f  (%s)".format(
        point.x, point.y, hAcc, point.z, vAcc,
    )
}
