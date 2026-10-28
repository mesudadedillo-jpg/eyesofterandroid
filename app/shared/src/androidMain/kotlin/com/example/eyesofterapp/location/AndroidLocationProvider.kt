package com.example.eyesofterapp.location

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat
import com.example.eyesofterapp.domain.model.Coordenadas
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import com.google.android.gms.tasks.CancellationTokenSource
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

class AndroidLocationProvider(
    private val context: Context,
    private val requestPermission: ((onResult: (Boolean) -> Unit) -> Unit)? = null
) : LocationProvider {

    private val fusedLocationClient by lazy {
        LocationServices.getFusedLocationProviderClient(context)
    }

    @SuppressLint("MissingPermission")
    override suspend fun obtenerUbicacion(): Coordenadas? {
        val permisoConcedido = suspendCancellableCoroutine<Boolean> { continuation ->
            val fineGranted = ContextCompat.checkSelfPermission(
                context, Manifest.permission.ACCESS_FINE_LOCATION
            ) == PackageManager.PERMISSION_GRANTED

            val coarseGranted = ContextCompat.checkSelfPermission(
                context, Manifest.permission.ACCESS_COARSE_LOCATION
            ) == PackageManager.PERMISSION_GRANTED

            if (fineGranted || coarseGranted) {
                continuation.resume(true)
            } else if (requestPermission != null) {
                requestPermission.invoke { resultGranted ->
                    if (continuation.isActive) {
                        continuation.resume(resultGranted)
                    }
                }
            } else {
                continuation.resume(false)
            }
        }

        if (!permisoConcedido) {
            return null
        }

        return suspendCancellableCoroutine { continuation ->
            val cancellationTokenSource = CancellationTokenSource()

            fusedLocationClient.getCurrentLocation(
                Priority.PRIORITY_HIGH_ACCURACY,
                cancellationTokenSource.token
            ).addOnSuccessListener { location ->
                if (location != null) {
                    continuation.resume(Coordenadas(location.latitude, location.longitude))
                } else {
                    // Si getCurrentLocation no obtiene fix aún, probar lastLocation
                    fusedLocationClient.lastLocation.addOnSuccessListener { lastLoc ->
                        if (lastLoc != null) {
                            continuation.resume(Coordenadas(lastLoc.latitude, lastLoc.longitude))
                        } else {
                            // Coordenadas por defecto para emulador sin punto GPS fijado aún
                            continuation.resume(Coordenadas(20.0825, -98.3622)) // Tulancingo / Hidalgo
                        }
                    }.addOnFailureListener {
                        continuation.resume(Coordenadas(20.0825, -98.3622))
                    }
                }
            }.addOnFailureListener {
                continuation.resume(Coordenadas(20.0825, -98.3622))
            }

            continuation.invokeOnCancellation {
                cancellationTokenSource.cancel()
            }
        }
    }
}
