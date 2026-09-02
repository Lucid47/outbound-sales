package com.lucid47.soheeyagaja.location

import android.annotation.SuppressLint
import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.Geocoder
import android.location.Location
import android.location.LocationManager
import android.os.CancellationSignal
import androidx.core.content.ContextCompat
import androidx.core.location.LocationManagerCompat
import com.lucid47.soheeyagaja.data.AppDatabase
import com.lucid47.soheeyagaja.data.CustomerWithFields
import java.util.Locale
import kotlin.coroutines.resume
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext

class CustomerLocationRepository(
    private val context: Context,
    private val database: AppDatabase,
) {
    private val geocoder = Geocoder(context, Locale.KOREA)

    suspend fun geocodeCustomers(customers: List<CustomerWithFields>): Int = withContext(Dispatchers.IO) {
        var updated = 0
        customers.asSequence()
            .filter { it.customer.address.isNotBlank() && (it.customer.latitude == null || it.customer.longitude == null) }
            .forEach { record ->
                val result = runCatching {
                    @Suppress("DEPRECATION")
                    geocoder.getFromLocationName(record.customer.address, 1)?.firstOrNull()
                }.getOrNull() ?: return@forEach
                database.customerDao().updateCoordinates(
                    customerId = record.customer.id,
                    latitude = result.latitude,
                    longitude = result.longitude,
                    geocodedAt = System.currentTimeMillis(),
                )
                updated += 1
            }
        updated
    }

    suspend fun currentAddress(): String? {
        val location = currentLocation() ?: return null
        return withContext(Dispatchers.IO) {
            runCatching {
                @Suppress("DEPRECATION")
                geocoder.getFromLocation(location.latitude, location.longitude, 1)?.firstOrNull()
                    ?.getAddressLine(0)
            }.getOrNull()?.trim()?.ifEmpty { null }
        }
    }

    @SuppressLint("MissingPermission")
    private suspend fun currentLocation(): Location? {
        check(hasLocationPermission()) { "방문 위치를 기록하려면 위치 권한이 필요합니다." }
        val manager = context.getSystemService(LocationManager::class.java)
        val provider = listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER)
            .firstOrNull { runCatching { manager.isProviderEnabled(it) }.getOrDefault(false) }
            ?: return null
        return suspendCancellableCoroutine { continuation ->
            val signal = CancellationSignal()
            continuation.invokeOnCancellation { signal.cancel() }
            LocationManagerCompat.getCurrentLocation(
                manager,
                provider,
                signal,
                ContextCompat.getMainExecutor(context),
            ) { location ->
                if (continuation.isActive) continuation.resume(location)
            }
        }
    }

    private fun hasLocationPermission(): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED
}
