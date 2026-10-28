package com.example.eyesofterapp

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.tooling.preview.Preview
import androidx.core.content.ContextCompat
import com.example.eyesofterapp.location.AndroidLocationProvider

class MainActivity : ComponentActivity() {

    private var onPermissionResultCallback: ((Boolean) -> Unit)? = null

    private val requestPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        val granted = permissions.values.any { it }
        if (granted) {
            Toast.makeText(this, "📍 Permiso GPS Concedido en Android", Toast.LENGTH_SHORT).show()
        } else {
            Toast.makeText(this, "❌ Permiso GPS Denegado en Android", Toast.LENGTH_SHORT).show()
        }
        onPermissionResultCallback?.invoke(granted)
        onPermissionResultCallback = null
    }

    fun solicitarPermisosUbicacion(callback: (Boolean) -> Unit) {
        val fineGranted = ContextCompat.checkSelfPermission(
            this, Manifest.permission.ACCESS_FINE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED

        val coarseGranted = ContextCompat.checkSelfPermission(
            this, Manifest.permission.ACCESS_COARSE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED

        if (fineGranted || coarseGranted) {
            Toast.makeText(this, "📍 Sensor GPS ya autorizado en el dispositivo", Toast.LENGTH_SHORT).show()
            callback(true)
        } else {
            onPermissionResultCallback = callback
            requestPermissionLauncher.launch(
                arrayOf(
                    Manifest.permission.ACCESS_FINE_LOCATION,
                    Manifest.permission.ACCESS_COARSE_LOCATION
                )
            )
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)

        setContent {
            val locationProvider = remember {
                AndroidLocationProvider(
                    context = this,
                    requestPermission = ::solicitarPermisosUbicacion
                )
            }
            App(locationProvider = locationProvider)
        }
    }
}

@Preview
@Composable
fun AppAndroidPreview() {
    App()
}
