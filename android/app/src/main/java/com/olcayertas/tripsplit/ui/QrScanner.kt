package com.olcayertas.tripsplit.ui

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import com.olcayertas.tripsplit.R
import java.util.concurrent.Executors

/**
 * A camera preview that reads QR codes with ZXing core ([QrDecoder]). It works offline and needs no native
 * library. [onCode] gets the text of each QR code once per frame, on the main thread, so the caller must ignore
 * repeats.
 */
@Composable
fun QrScanner(onCode: (String) -> Unit, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    var granted by remember {
        mutableStateOf(ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED)
    }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted = it }
    if (!granted) {
        Column(modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(stringResource(R.string.scan_need_camera))
            Button({ launcher.launch(Manifest.permission.CAMERA) }, Modifier.fillMaxWidth()) { Text(stringResource(R.string.scan_allow_camera)) }
        }
        return
    }
    val owner = LocalLifecycleOwner.current
    val executor = remember { Executors.newSingleThreadExecutor() }
    val decoder = remember { QrDecoder() }
    var luma = remember { ByteArray(0) }
    AndroidView(
        modifier = modifier.fillMaxSize(),
        factory = { ctx ->
            val view = PreviewView(ctx)
            val future = ProcessCameraProvider.getInstance(ctx)
            future.addListener({
                val provider = future.get()
                val preview = Preview.Builder().build().also { it.surfaceProvider = view.surfaceProvider }
                val analysis = ImageAnalysis.Builder()
                    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                    .build()
                val main = ContextCompat.getMainExecutor(ctx)
                analysis.setAnalyzer(executor) { proxy ->
                    // ImageAnalysis gives YUV_420_888 by default. Plane 0 is the luminance, one byte for each pixel.
                    val text = proxy.use {
                        val plane = it.planes[0]
                        val buffer = plane.buffer.apply { rewind() }
                        val size = plane.rowStride * it.height
                        if (luma.size < size) luma = ByteArray(size)
                        buffer.get(luma, 0, minOf(buffer.remaining(), size))
                        decoder.decode(luma, plane.rowStride, it.width, it.height)
                    }
                    if (text != null) main.execute { onCode(text) }
                }
                provider.unbindAll()
                provider.bindToLifecycle(owner, CameraSelector.DEFAULT_BACK_CAMERA, preview, analysis)
            }, ContextCompat.getMainExecutor(ctx))
            view
        },
    )
}
