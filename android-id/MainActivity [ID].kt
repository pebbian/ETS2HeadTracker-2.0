package com.example.ets2headtracker

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.view.PreviewView
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import androidx.compose.foundation.clickable
import androidx.compose.ui.platform.LocalUriHandler

private const val CREDIT_AUTHOR = "Fabian N.A."

class MainActivity : ComponentActivity() {
    private lateinit var settings: Settings
    private lateinit var link: UdpLink
    private lateinit var tracker: HeadTracker
    private val pcIp = mutableStateOf<String?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        settings = Settings(this)
        link = UdpLink(this) { pcIp.value = it }
        tracker = HeadTracker(this, settings, link)
        link.start()
        applyConnection()
        setContent {
            MaterialTheme(colorScheme = darkColorScheme()) {
                Surface(Modifier.fillMaxSize()) {
                    App(this, tracker, settings, pcIp.value, ::applyConnection)
                }
            }
        }
    }

    private fun applyConnection() {
        val ok = settings.manualEnabled && Settings.validIp(settings.manualIp) && Settings.validPort(settings.manualPort)
        link.setManual(if (ok) settings.manualIp.trim() else null, settings.manualPort)
    }

    override fun onDestroy() {
        tracker.close()
        link.stop()
        super.onDestroy()
    }
}

@Composable
private fun App(
    owner: LifecycleOwner,
    tracker: HeadTracker,
    settings: Settings,
    pcIp: String?,
    onConnectionChanged: () -> Unit,
) {
    val ctx = LocalContext.current
    var granted by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(ctx, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED,
        )
    }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted = it }
    LaunchedEffect(Unit) { if (!granted) launcher.launch(Manifest.permission.CAMERA) }
    var axisIdx by remember { mutableIntStateOf(0) }
    val axis = settings.axes[axisIdx]
    val live = tracker.live

    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        Modifier
                            .size(12.dp)
                            .clip(CircleShape)
                            .background(if (pcIp != null) Color(0xFF4CAF50) else Color(0xFFE53935)),
                    )
                    Spacer(Modifier.width(10.dp))
                    Text(
                        if (pcIp != null) "Connected  ·  $pcIp" + (if (settings.manualEnabled) "  (manual)" else "") else "PC not found",
                        style = MaterialTheme.typography.titleMedium,
                    )
                }
                val face = if (tracker.faceFound) "detected" else "not detected"
                Text(
                    "Face $face  ·  ${tracker.fps} fps  ·  ${tracker.delegateName}",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }

        ConnectionSection(settings, onConnectionChanged)

        if (granted) {
            AndroidView(
                factory = { c -> PreviewView(c).also { tracker.startCamera(owner, it) } },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(180.dp)
                    .clip(RoundedCornerShape(12.dp)),
            )
        } else {
            Button(onClick = { launcher.launch(Manifest.permission.CAMERA) }) { Text("Allow camera") }
        }

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(
                onClick = { tracker.enableTracking(!tracker.tracking) },
                enabled = granted,
                modifier = Modifier.weight(1f),
            ) { Text(if (tracker.tracking) "Stop" else "Start") }
            OutlinedButton(onClick = { tracker.recenter() }, modifier = Modifier.weight(1f)) { Text("Recenter") }
        }

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            settings.axes.forEachIndexed { i, a ->
                val m = Modifier.weight(1f)
                if (i == axisIdx) {
                    Button(onClick = { axisIdx = i }, modifier = m) { Text(a.name) }
                } else {
                    OutlinedButton(onClick = { axisIdx = i }, modifier = m) { Text(a.name) }
                }
            }
        }

        CurveEditor(
            axis = axis,
            live = when (axisIdx) {
                0 -> live.yaw
                1 -> live.pitch
                else -> live.roll
            },
            modifier = Modifier
                .fillMaxWidth()
                .height(240.dp),
        )
        Text(
            "X axis: head movement (degrees). Y axis: output to the game (degrees). Drag the points to change the curve.",
            style = MaterialTheme.typography.bodySmall,
        )

        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Invert ${axis.name}", Modifier.weight(1f))
            Switch(checked = axis.invert, onCheckedChange = {
                axis.invert = it
                axis.save()
            })
        }
        OutlinedButton(onClick = { axis.resetCurve() }, modifier = Modifier.fillMaxWidth()) {
            Text("Reset ${axis.name} curve")
        }

        Text("Smoothing (min cutoff) ${"%.2f".format(settings.minCutoff)}  ·  lower = smoother")
        Slider(
            value = settings.minCutoff,
            onValueChange = { settings.minCutoff = it },
            onValueChangeFinished = { settings.save() },
            valueRange = 0.1f..3f,
        )
        Text("Responsiveness (beta) ${"%.3f".format(settings.beta)}  ·  higher = less lag on fast movement")
        Slider(
            value = settings.beta,
            onValueChange = { settings.beta = it },
            onValueChangeFinished = { settings.save() },
            valueRange = 0f..0.3f,
        )

        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Send position (x, y, z)", Modifier.weight(1f))
            Switch(checked = settings.sendPosition, onCheckedChange = {
                settings.sendPosition = it
                settings.save()
            })
        }

        Credits()
    }
}

@Composable
private fun ConnectionSection(settings: Settings, onChanged: () -> Unit) {
    var ip by remember { mutableStateOf(settings.manualIp) }
    var port by remember { mutableStateOf(settings.manualPort.toString()) }
    var error by remember { mutableStateOf<String?>(null) }

    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Manual PC IP", style = MaterialTheme.typography.titleSmall)
                    Text(
                        if (settings.manualEnabled) "The phone sends directly to the address below"
                        else "Automatic: the PC finds the phone via broadcast",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                Switch(checked = settings.manualEnabled, onCheckedChange = {
                    settings.manualEnabled = it
                    settings.save()
                    error = null
                    onChanged()
                })
            }
            if (settings.manualEnabled) {
                OutlinedTextField(
                    value = ip,
                    onValueChange = { ip = it.filter { c -> c.isDigit() || c == '.' }.take(15) },
                    label = { Text("PC IPv4, e.g. 192.168.1.10") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = port,
                    onValueChange = { port = it.filter { c -> c.isDigit() }.take(5) },
                    label = { Text("PC port (same as in the receiver)") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.fillMaxWidth(),
                )
                error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
                Button(
                    onClick = {
                        val p = port.toIntOrNull() ?: -1
                        when {
                            !Settings.validIp(ip) -> error = "Invalid IP. Example: 192.168.1.10"
                            !Settings.validPort(p) -> error = "Port must be between 1024 and 65535"
                            else -> {
                                error = null
                                settings.manualIp = ip.trim()
                                settings.manualPort = p
                                settings.save()
                                onChanged()
                            }
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("Apply") }
            }
        }
    }
}

@Composable
private fun Credits() {
    val ctx = LocalContext.current
    val version = remember {
        try {
            ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionName ?: "-"
        } catch (e: Exception) {
            "-"
        }
    }
    HorizontalDivider()
    val uriHandler = LocalUriHandler.current
    Column(
        Modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Text("ETS2 Head Tracker", style = MaterialTheme.typography.titleSmall)
        Text("Version 2.0", style = MaterialTheme.typography.bodySmall)
        Spacer(Modifier.height(4.dp))
        Text("Made by $CREDIT_AUTHOR", style = MaterialTheme.typography.bodySmall)
        Text(
            text = "TikTok: @doubleoneeight",
            style = MaterialTheme.typography.labelSmall.copy(color = MaterialTheme.colorScheme.primary),
            modifier = Modifier.clickable {
                uriHandler.openUri("https://www.tiktok.com/@doubleoneeight")
            }
        )
        Text(
            text = "Instagram: @fberkastel",
            style = MaterialTheme.typography.labelSmall.copy(color = MaterialTheme.colorScheme.primary),
            modifier = Modifier.clickable {
                uriHandler.openUri("https://www.instagram.com/fberkastel")
            }
        )
        Text(
            text = "Facebook: Fabian N",
            style = MaterialTheme.typography.labelSmall.copy(color = MaterialTheme.colorScheme.primary),
            modifier = Modifier.clickable {
                uriHandler.openUri("https://www.facebook.com/fabian.nugrah12/")
            }
        )
        Spacer(Modifier.height(4.dp))
        Text("Face tracking: MediaPipe Face Landmarker (Google)", style = MaterialTheme.typography.labelSmall)
        Text("Camera: AndroidX CameraX", style = MaterialTheme.typography.labelSmall)
        Text("Filter: One Euro Filter (Casiez et al., 2012)", style = MaterialTheme.typography.labelSmall)
        Text("Output protocol: FreeTrack", style = MaterialTheme.typography.labelSmall)
        Text(
            text = "View Open Source License (Apache 2.0)",
            style = MaterialTheme.typography.labelSmall.copy(color = MaterialTheme.colorScheme.primary),
            modifier = Modifier.clickable {
                uriHandler.openUri("https://www.apache.org/licenses/LICENSE-2.0")
            }
        )
        Spacer(Modifier.height(4.dp))
        Text(
            "Euro Truck Simulator 2 is a product of SCS Software. This app is not affiliated with SCS Software.",
            style = MaterialTheme.typography.labelSmall,
        )
    }
}

@Composable
private fun CurveEditor(axis: AxisConfig, live: Float, modifier: Modifier = Modifier) {
    val lineColor = MaterialTheme.colorScheme.primary
    val gridColor = MaterialTheme.colorScheme.outlineVariant
    val liveColor = Color(0xFFFFB300)

    Canvas(
        modifier = modifier
            .clip(RoundedCornerShape(12.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .pointerInput(axis) {
                val pad = 16.dp.toPx()
                var idx = -1
                fun toPx(p: Pt) = Offset(
                    pad + p.x / axis.inMax * (size.width - 2 * pad),
                    size.height - pad - p.y / axis.outMax * (size.height - 2 * pad),
                )
                detectDragGestures(
                    onDragStart = { o ->
                        idx = axis.points.indices.drop(1)
                            .minByOrNull { (toPx(axis.points[it]) - o).getDistance() } ?: -1
                        if (idx >= 0 && (toPx(axis.points[idx]) - o).getDistance() > 48.dp.toPx()) idx = -1
                    },
                    onDragEnd = {
                        idx = -1
                        axis.save()
                    },
                    onDragCancel = { idx = -1 },
                ) { change, _ ->
                    if (idx > 0) {
                        change.consume()
                        val pts = axis.points
                        val nx = (change.position.x - pad) / (size.width - 2 * pad) * axis.inMax
                        val ny = (size.height - pad - change.position.y) / (size.height - 2 * pad) * axis.outMax
                        val loX = pts[idx - 1].x + 1f
                        val hiX = if (idx == pts.lastIndex) axis.inMax else pts[idx + 1].x - 1f
                        val loY = pts[idx - 1].y
                        val hiY = if (idx == pts.lastIndex) axis.outMax else pts[idx + 1].y
                        pts[idx] = Pt(nx.coerceIn(loX, maxOf(loX, hiX)), ny.coerceIn(loY, maxOf(loY, hiY)))
                    }
                }
            },
    ) {
        val pad = 16.dp.toPx()
        val w = size.width - 2 * pad
        val h = size.height - 2 * pad
        fun px(x: Float, y: Float) = Offset(pad + x / axis.inMax * w, size.height - pad - y / axis.outMax * h)

        for (i in 0..4) {
            val gx = pad + w * i / 4
            val gy = pad + h * i / 4
            drawLine(gridColor, Offset(gx, pad), Offset(gx, pad + h))
            drawLine(gridColor, Offset(pad, gy), Offset(pad + w, gy))
        }

        val pts = axis.points.toList()
        val path = Path()
        val steps = 80
        for (i in 0..steps) {
            val x = axis.inMax * i / steps
            val o = px(x, Curve.eval(pts, x).coerceIn(0f, axis.outMax))
            if (i == 0) path.moveTo(o.x, o.y) else path.lineTo(o.x, o.y)
        }
        drawPath(path, lineColor, style = Stroke(width = 3.dp.toPx()))

        val lx = live.coerceIn(0f, axis.inMax)
        drawLine(
            liveColor, px(lx, 0f), px(lx, axis.outMax),
            strokeWidth = 1.5.dp.toPx(),
            pathEffect = PathEffect.dashPathEffect(floatArrayOf(10f, 10f)),
        )
        drawCircle(liveColor, 6.dp.toPx(), px(lx, Curve.eval(pts, lx)))
        pts.forEach { drawCircle(lineColor, 9.dp.toPx(), px(it.x, it.y)) }
    }
}
