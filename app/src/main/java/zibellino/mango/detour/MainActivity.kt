package zibellino.mango.detour

import android.Manifest
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
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
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * One recorded fix. GPS naturally gives latitude/longitude/altitude; we
 * treat those as x/y/z per the debug spec (x = latitude, y = longitude,
 * z = altitude in meters).
 */
data class TrackPoint(
    val timestampMillis: Long,
    val x: Double,
    val y: Double,
    val z: Double,
)

class MainActivity : ComponentActivity() {

    private lateinit var locationManager: LocationManager
    private var activeListener: LocationListener? = null

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
        locationManager = getSystemService(LOCATION_SERVICE) as LocationManager

        setContent {
            MaterialTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    RecorderScreen(
                        isRecording = isRecording,
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
        val listener = LocationListener { location -> onNewLocation(location) }
        activeListener = listener

        try {
            locationManager.requestLocationUpdates(
                LocationManager.GPS_PROVIDER,
                /* minTimeMs = */ 10_000L,
                /* minDistanceM = */ 0f,
                listener,
                Looper.getMainLooper(),
            )
            isRecording = true
        } catch (e: SecurityException) {
            // Permission was revoked between the check above and this call.
            activeListener = null
        }
    }

    private fun stopRecording() {
        activeListener?.let { locationManager.removeUpdates(it) }
        activeListener = null
        isRecording = false
    }

    private fun onNewLocation(location: Location) {
        points.add(
            0,
            TrackPoint(
                timestampMillis = System.currentTimeMillis(),
                x = location.latitude,
                y = location.longitude,
                z = location.altitude,
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
    points: List<TrackPoint>,
    onToggle: () -> Unit,
) {
    Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
        Button(onClick = onToggle, modifier = Modifier.fillMaxWidth()) {
            Text(if (isRecording) "Stop Recording" else "Start Recording")
        }

        Spacer(modifier = Modifier.height(8.dp))

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
    return "[$time] x=%.6f  y=%.6f  z=%.2f".format(point.x, point.y, point.z)
}
