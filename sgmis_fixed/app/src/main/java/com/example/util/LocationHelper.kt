package com.example.util

import android.annotation.SuppressLint
import android.content.Context
import android.location.Location
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import com.google.android.gms.tasks.CancellationTokenSource
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

object LocationHelper {

    @SuppressLint("MissingPermission")
    suspend fun getDeviceLocation(context: Context): Pair<Double, Double>? {
        return try {
            val fusedLocationClient = LocationServices.getFusedLocationProviderClient(context)
            val cts = CancellationTokenSource()

            suspendCancellableCoroutine { continuation ->
                fusedLocationClient.getCurrentLocation(Priority.PRIORITY_HIGH_ACCURACY, cts.token)
                    .addOnSuccessListener { location: Location? ->
                        if (location != null) {
                            continuation.resume(Pair(location.latitude, location.longitude))
                        } else {
                            // Fallback to last known location if immediate fix is null
                            fusedLocationClient.lastLocation.addOnSuccessListener { lastLoc: Location? ->
                                if (lastLoc != null) {
                                    continuation.resume(Pair(lastLoc.latitude, lastLoc.longitude))
                                } else {
                                    continuation.resume(null)
                                }
                            }.addOnFailureListener {
                                continuation.resume(null)
                            }
                        }
                    }
                    .addOnFailureListener {
                        continuation.resume(null)
                    }

                continuation.invokeOnCancellation {
                    cts.cancel()
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }
}
