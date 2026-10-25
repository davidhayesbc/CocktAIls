package com.davidhayesbc.cocktails.android.location

import android.annotation.SuppressLint
import android.content.Context
import android.location.Criteria
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Build
import android.os.Bundle
import android.os.CancellationSignal
import android.os.Handler
import android.os.Looper
import java.util.concurrent.Executor
import java.util.concurrent.atomic.AtomicBoolean

data class ApproximateCoordinates(val latitude: Double, val longitude: Double)

/** Requests one coarse foreground fix and completes with null after a short timeout. */
@SuppressLint("MissingPermission", "DEPRECATION")
fun requestApproximateLocation(context: Context, onResult: (ApproximateCoordinates?) -> Unit) {
    val locationManager = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager
    val provider = runCatching {
        locationManager.getBestProvider(
            Criteria().apply {
                accuracy = Criteria.ACCURACY_COARSE
                powerRequirement = Criteria.POWER_LOW
            },
            true,
        )
    }.getOrNull()
    if (provider == null) {
        onResult(null)
        return
    }

    val handler = Handler(Looper.getMainLooper())
    val completed = AtomicBoolean(false)
    var cleanupOnTimeout: () -> Unit = {}
    val timeout = Runnable {
        if (completed.compareAndSet(false, true)) {
            cleanupOnTimeout()
            onResult(null)
        }
    }
    handler.postDelayed(timeout, LOCATION_TIMEOUT_MILLIS)

    fun finish(location: Location?) {
        handler.removeCallbacks(timeout)
        if (completed.compareAndSet(false, true)) {
            onResult(location?.let { ApproximateCoordinates(it.latitude, it.longitude) })
        }
    }

    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
        val cancellationSignal = CancellationSignal()
        cleanupOnTimeout = cancellationSignal::cancel
        val executor = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) context.mainExecutor else Executor { command ->
            handler.post(command)
        }
        locationManager.getCurrentLocation(provider, cancellationSignal, executor) { location ->
            finish(location)
        }
    } else {
        val listener = object : LocationListener {
            override fun onLocationChanged(location: Location) = finish(location)

            override fun onProviderDisabled(providerName: String) = finish(null)

            override fun onProviderEnabled(providerName: String) = Unit

            override fun onStatusChanged(providerName: String?, status: Int, extras: Bundle?) = Unit
        }
        cleanupOnTimeout = { locationManager.removeUpdates(listener) }
        runCatching { locationManager.requestSingleUpdate(provider, listener, Looper.getMainLooper()) }
            .onFailure { finish(null) }
    }
}

private const val LOCATION_TIMEOUT_MILLIS = 20_000L
