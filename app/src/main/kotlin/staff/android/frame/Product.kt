package staff.android.frame

import android.Manifest
import android.content.pm.PackageManager
import android.util.Size
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.*
import androidx.camera.core.resolutionselector.AspectRatioStrategy
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.Image
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.Alignment
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LocalLifecycleOwner
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.util.concurrent.Executors

const val ABOUT =
    "Frame uses CameraX for real foreground preview, JPEG capture and a lightweight luminance analyzer. Captures remain in app-private storage. The loopback backend simulates processing status from metadata only; it never receives camera bytes. Emulator camera output is not physical-camera image-quality evidence."

private fun frameResolutionSelector(): ResolutionSelector =
    ResolutionSelector.Builder()
        .setAspectRatioStrategy(
            AspectRatioStrategy.RATIO_4_3_FALLBACK_AUTO_STRATEGY
        )
        .setResolutionStrategy(
            ResolutionStrategy(
                Size(640, 480),
                ResolutionStrategy.FALLBACK_RULE_CLOSEST_HIGHER_THEN_LOWER
            )
        )
        .build()

fun validateView(d: JSONObject) {
    val rows = d.getJSONArray("jobs")
    require(rows.length() <= 20)
    for (i in 0 until rows.length()) {
        val j = rows.getJSONObject(i)
        require(j.getString("status") in setOf("QUEUED", "COMPLETE", "CANCELLED"))
        require(j.getInt("generation") > 0)
        j.getString("assetId")
    }
}

class CameraDiagnostics {
    var frames by mutableLongStateOf(0)
    var luma by mutableIntStateOf(0)
}

@Composable
fun Product(state: CaseState, model: CaseModel, elapsed: Long, diagnostics: CameraDiagnostics, viewportHeight: Dp) {
    Text("CAMERA · PRIVATE CAPTURES", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
    CameraPanel(state, model, diagnostics, viewportHeight)
    val jobs = state.data?.optJSONArray("jobs")
    if (jobs != null)
        for (i in 0 until jobs.length()) {
            val j = jobs.getJSONObject(i)
            InfoCard(
                "Capture ${j.getString("assetId").take(8)} · simulation", when (j.getString("status")) { "QUEUED" -> "Waiting for processing"; "COMPLETE" -> "Processing complete"; else -> "Processing cancelled" })
            if (j.getString("status") == "QUEUED")
                OutlinedButton(
                    onClick = {
                        model.send(
                            "cancel",
                            JSONObject()
                                .put("assetId", j.getString("assetId"))
                                .put("generation", j.getInt("generation")))
                    },
                    enabled = ready(state),
                    modifier = Modifier.testTag("cancel")) {
                        Text("Cancel processing simulation")
                    }
            if (j.getString("status") == "CANCELLED")
                OutlinedButton(
                    onClick = {
                        model.send(
                            "retry",
                            JSONObject()
                                .put("assetId", j.getString("assetId"))
                                .put("generation", j.getInt("generation")))
                    },
                    enabled = ready(state),
                    modifier = Modifier.testTag("retry-job")) {
                        Text("Retry processing simulation")
                    }
        }
}

@Composable
fun CameraPanel(state: CaseState, model: CaseModel, diagnostics: CameraDiagnostics, viewportHeight: Dp) {
    val context = LocalContext.current
    val owner = LocalLifecycleOwner.current
    val scope = rememberCoroutineScope()
    var granted by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) ==
                PackageManager.PERMISSION_GRANTED)
    }
    val permission =
        rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
            granted = it
        }
    val store = remember { CaptureStore(File(context.filesDir, "captures")) }
    var loaded by remember { mutableStateOf(false) }
    var review by remember { mutableStateOf(false) }
    var savedFiles by remember { mutableStateOf<List<File>>(emptyList()) }
    var latest by remember { mutableStateOf<File?>(null) }
    var thumbnail by remember { mutableStateOf<android.graphics.Bitmap?>(null) }
    var metadata by remember { mutableStateOf<JSONObject?>(null) }
    var count by remember { mutableIntStateOf(0) }
    var busy by remember { mutableStateOf(false) }
    var problem by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(Unit) {
        withContext(Dispatchers.IO) { store.files() }
            .let {
                savedFiles = it
                count = it.size
                latest = it.firstOrNull()
                loaded = true
            }
    }
    LaunchedEffect(latest) {
        thumbnail = null
        metadata = null
        val file = latest
        if (file != null)
            try {
                val pair =
                    withContext(Dispatchers.IO) { store.thumbnail(file) to store.metadata(file) }
                thumbnail = pair.first
                metadata = pair.second
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                problem = "Saved capture could not be read."
            }
    }
    if (!granted) {
        InfoCard(
            "Camera access",
            "Allow camera access to preview and capture. Your images stay on this device.")
        Button(
            onClick = { permission.launch(Manifest.permission.CAMERA) },
            modifier = Modifier.testTag("permission")) {
                Text("Allow camera")
            }
    } else {
        val preview = remember {
            PreviewView(context).apply { scaleType = PreviewView.ScaleType.FIT_CENTER }
        }
        val capture = remember {
            ImageCapture.Builder()
                .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
                .setResolutionSelector(frameResolutionSelector())
                .build()
        }
        var bound by remember { mutableStateOf(false) }
        var frames by remember { mutableLongStateOf(0) }
        var luma by remember { mutableIntStateOf(0) }
        DisposableEffect(owner) {
            val executor = Executors.newSingleThreadExecutor()
            val main = ContextCompat.getMainExecutor(context)
            val future = ProcessCameraProvider.getInstance(context)
            var active = true
            var provider: ProcessCameraProvider? = null
            val previewCase = Preview.Builder().build()
            previewCase.setSurfaceProvider(preview.surfaceProvider)
            val analysis =
                ImageAnalysis.Builder()
                    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                    .setResolutionSelector(frameResolutionSelector())
                    .build()
            var observed = 0L
            var last = 0L
            analysis.setAnalyzer(executor) { image ->
                try {
                    val plane = image.planes[0]
                    val buffer = plane.buffer
                    var sum = 0L
                    var samples = 0
                    for (y in 0 until image.height step 16)
                        for (x in 0 until image.width step 16) {
                            val index = y * plane.rowStride + x * plane.pixelStride
                            if (index < buffer.limit()) {
                                sum += buffer.get(index).toInt() and 255
                                samples++
                            }
                        }
                    observed++
                    val now = android.os.SystemClock.elapsedRealtime()
                    if (now - last >= 500) {
                        last = now
                        val n = observed
                        val mean = if (samples == 0) 0 else (sum / samples).toInt()
                        main.execute {
                            if (active) {
                                frames = n
                                luma = mean
                                diagnostics.frames = n
                                diagnostics.luma = mean
                            }
                        }
                    }
                } finally {
                    image.close()
                }
            }
            future.addListener(
                {
                    if (active)
                        try {
                            provider = future.get()
                            provider!!.bindToLifecycle(
                                owner,
                                CameraSelector.DEFAULT_BACK_CAMERA,
                                previewCase,
                                capture,
                                analysis)
                            bound = true
                        } catch (e: Exception) {
                            problem =
                                "Camera could not bind this preview/capture/analysis combination."
                        }
                },
                main)
            onDispose {
                active = false
                bound = false
                analysis.clearAnalyzer()
                provider?.unbind(previewCase, capture, analysis)
                executor.shutdown()
            }
        }
        BoxWithConstraints(Modifier.fillMaxWidth(), contentAlignment = Alignment.TopCenter) {
            // Bound only the displayed surface; CameraX resolution and FIT_CENTER stay unchanged.
            val previewWidth = if (maxWidth >= 640.dp) minOf(maxWidth, viewportHeight * .45f * (4f / 3f)) else maxWidth
            AndroidView(
                factory = { preview },
                modifier = Modifier.width(previewWidth).aspectRatio(4f / 3f).clip(RoundedCornerShape(18.dp)).testTag("preview"))
        }
        Button(
            onClick = {
                busy = true
                problem = null
                val file = store.temporary()
                capture.targetRotation =
                    preview.display?.rotation ?: android.view.Surface.ROTATION_0
                capture.takePicture(
                    ImageCapture.OutputFileOptions.Builder(file).build(),
                    ContextCompat.getMainExecutor(context),
                    object : ImageCapture.OnImageSavedCallback {
                        override fun onImageSaved(output: ImageCapture.OutputFileResults) {
                            scope.launch {
                                try {
                                    latest = withContext(Dispatchers.IO) { store.complete(file) }
                                    savedFiles = listOf(latest!!) + savedFiles
                                    count++
                                    review = true
                                } catch (e: Exception) {
                                    problem = "Capture could not be published locally."
                                } finally {
                                    busy = false
                                }
                            }
                        }

                        override fun onError(error: ImageCaptureException) {
                            file.delete()
                            busy = false
                            problem = "Capture failed. Preview can continue; retry the capture."
                        }
                    })
            },
            enabled = loaded && bound && !busy && count < 10,
            modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp).testTag("capture")) {
                Text(if (busy) "Saving capture…" else "Capture frame")
            }
        if (count >= 10)
            Text(
                "The local lab holds ten captures. Preserve evidence, then clear this app to start another collection.")
    }
    problem?.let { InfoCard("Camera state", it) }
    if (savedFiles.size > 1)
        FlowRow {
            for ((index, file) in savedFiles.withIndex()) FilterChip(
                selected = latest == file,
                onClick = { latest = file },
                label = { Text("Capture ${savedFiles.size-index}") })
        }
    thumbnail?.let { image ->
        Card(onClick = { review = true }, colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant), modifier = Modifier.fillMaxWidth().testTag("review-capture")) {
            Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                Image(image.asImageBitmap(), "Most recently selected capture", modifier = Modifier.size(64.dp).clip(RoundedCornerShape(8.dp)))
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("Review saved capture", fontWeight = FontWeight.Bold)
                    Text("$count of 10 saved · private to this app", style = MaterialTheme.typography.bodySmall)
                }
            }
        }
    }
    if (review) AlertDialog(onDismissRequest = { review = false }, title = { Text("Saved capture") }, text = {
        Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            thumbnail?.let { Image(it.asImageBitmap(), "Latest saved capture", modifier = Modifier.fillMaxWidth().height(220.dp).testTag("saved-image")) }
            metadata?.let { data ->
                Text("Capture ${data.getString("assetId").take(8)}", fontWeight = FontWeight.Bold)
                Text("${data.getInt("width")} × ${data.getInt("height")} JPEG · private to this app")
                Text("Processing is simulated. Only image ID, dimensions and digest are sent to the local service; the image stays here.")
                Button(onClick = { model.send("register", data); review = false }, enabled = ready(state), modifier = Modifier.testTag("register")) { Text("Register processing simulation") }
            } ?: Text("Reading saved capture…")
        }
    }, confirmButton = { TextButton(onClick = { review = false }) { Text("Back to camera") } })
}
