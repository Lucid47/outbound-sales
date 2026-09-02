package com.lucid47.soheeyagaja.location

import android.content.Context
import android.content.pm.PackageManager
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.background
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.google.android.gms.maps.CameraUpdateFactory
import com.google.android.gms.maps.model.LatLng
import com.google.android.gms.maps.model.LatLngBounds
import com.google.maps.android.compose.GoogleMap
import com.google.maps.android.compose.MapUiSettings
import com.google.maps.android.compose.MarkerComposable
import com.google.maps.android.compose.rememberCameraPositionState
import com.google.maps.android.compose.rememberUpdatedMarkerState
import com.lucid47.soheeyagaja.customers.CustomerManagementViewModel
import com.lucid47.soheeyagaja.data.CustomerWithFields
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CustomerMapDialog(
    viewModel: CustomerManagementViewModel,
    onDismiss: () -> Unit,
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val customers by viewModel.allCustomers.collectAsStateWithLifecycle()
    val history by viewModel.allHistoryEntries.collectAsStateWithLifecycle()
    val schedule by viewModel.todaySchedule.collectAsStateWithLifecycle()
    val scheduledIds = schedule.mapTo(hashSetOf()) { it.customerId }
    val lastTouchByCustomer = history.groupBy { it.customerId }
        .mapValues { (_, entries) -> entries.maxOfOrNull { it.occurredAtEpochMillis } }
    val visitCountByCustomer = history.asSequence()
        .filter { it.type == "QUICK_LOCATION" }
        .groupingBy { it.customerId }
        .eachCount()
    val eligibleCustomers = customers.filter { record ->
        !state.mapScheduleOnly || record.customer.id in scheduledIds
    }
    val visibleCustomers = eligibleCustomers.filter { record ->
        record.customer.latitude != null && record.customer.longitude != null
    }
    val missingCoordinates = eligibleCustomers.size - visibleCustomers.size

    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Scaffold(
            modifier = Modifier.fillMaxSize(),
            topBar = {
                TopAppBar(
                    title = { Text("고객 지도") },
                    navigationIcon = {
                        IconButton(onClick = onDismiss) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "닫기")
                        }
                    },
                    actions = {
                        IconButton(onClick = viewModel::geocodeVisibleCustomers, enabled = !state.mapBusy) {
                            if (state.mapBusy) CircularProgressIndicator() else {
                                Icon(Icons.Default.Refresh, contentDescription = "주소 좌표 새로고침")
                            }
                        }
                    },
                )
            },
        ) { padding ->
            Column(modifier = Modifier.fillMaxSize().padding(padding)) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column {
                        Text(if (state.mapScheduleOnly) "오늘 스케줄 고객" else "전체 고객")
                        Text(
                            buildString {
                                append("지도 표시 ${visibleCustomers.size}명")
                                if (missingCoordinates > 0) append(" · 좌표 없음 ${missingCoordinates}명")
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("스케줄만")
                        Switch(
                            checked = state.mapScheduleOnly,
                            onCheckedChange = viewModel::setMapScheduleOnly,
                        )
                    }
                }
                GoogleCustomerMap(
                    customers = visibleCustomers,
                    lastTouchByCustomer = lastTouchByCustomer,
                    visitCountByCustomer = visitCountByCustomer,
                    modifier = Modifier.fillMaxSize(),
                )
            }
        }
    }
}

@Composable
private fun GoogleCustomerMap(
    customers: List<CustomerWithFields>,
    lastTouchByCustomer: Map<Long, Long?>,
    visitCountByCustomer: Map<Long, Int>,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val mapsConfigured = remember(context) { isGoogleMapsConfigured(context) }
    if (!mapsConfigured) {
        Box(modifier = modifier.padding(32.dp), contentAlignment = Alignment.Center) {
            Text(
                text = "Google 지도 API 키가 설정되지 않았습니다.\n개발 설정의 GOOGLE_MAPS_API_KEY를 확인해 주세요.",
                textAlign = TextAlign.Center,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        return
    }

    val cameraPositionState = rememberCameraPositionState()
    var mapLoaded by remember { mutableStateOf(false) }
    val positions = customers.mapNotNull { record ->
        val latitude = record.customer.latitude ?: return@mapNotNull null
        val longitude = record.customer.longitude ?: return@mapNotNull null
        LatLng(latitude, longitude)
    }

    LaunchedEffect(mapLoaded, positions) {
        if (!mapLoaded) return@LaunchedEffect
        when (positions.size) {
            0 -> cameraPositionState.move(
                CameraUpdateFactory.newLatLngZoom(KOREA_CENTER, KOREA_ZOOM),
            )
            1 -> cameraPositionState.animate(
                CameraUpdateFactory.newLatLngZoom(positions.first(), CUSTOMER_ZOOM),
            )
            else -> {
                val bounds = LatLngBounds.builder().apply { positions.forEach(::include) }.build()
                cameraPositionState.animate(CameraUpdateFactory.newLatLngBounds(bounds, MAP_PADDING_PX))
            }
        }
    }

    GoogleMap(
        modifier = modifier,
        cameraPositionState = cameraPositionState,
        uiSettings = MapUiSettings(
            compassEnabled = true,
            mapToolbarEnabled = true,
            zoomControlsEnabled = false,
        ),
        onMapLoaded = { mapLoaded = true },
    ) {
        customers.forEach { record ->
            val customer = record.customer
            val latitude = customer.latitude ?: return@forEach
            val longitude = customer.longitude ?: return@forEach
            val lastTouch = lastTouchByCustomer[customer.id]?.let {
                MAP_DATE_FORMATTER.format(Instant.ofEpochMilli(it).atZone(ZoneId.systemDefault()))
            } ?: "기록 없음"

            key(customer.id) {
                MarkerComposable(
                    keys = arrayOf(customer.name, (visitCountByCustomer[customer.id] ?: 0).toString()),
                    state = rememberUpdatedMarkerState(position = LatLng(latitude, longitude)),
                    anchor = Offset(0.5f, 1f),
                    title = customer.name,
                    snippet = "$lastTouch\n${customer.address}",
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Surface(
                            shape = RoundedCornerShape(6.dp),
                            color = MaterialTheme.colorScheme.primaryContainer,
                            contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                            shadowElevation = 3.dp,
                        ) {
                            Text(
                                text = "${customer.name} · 방문 ${visitCountByCustomer[customer.id] ?: 0}회",
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 5.dp),
                                style = MaterialTheme.typography.labelMedium,
                                fontWeight = FontWeight.Bold,
                            )
                        }
                        Box(Modifier.size(width = 3.dp, height = 9.dp).background(MaterialTheme.colorScheme.primary))
                    }
                }
            }
        }
    }
}

private fun isGoogleMapsConfigured(context: Context): Boolean {
    val applicationInfo = context.packageManager.getApplicationInfo(
        context.packageName,
        PackageManager.GET_META_DATA,
    )
    val apiKey = applicationInfo.metaData?.getString(GOOGLE_MAPS_API_KEY_METADATA).orEmpty()
    return apiKey.isNotBlank() && apiKey != UNCONFIGURED_API_KEY
}

private const val GOOGLE_MAPS_API_KEY_METADATA = "com.google.android.geo.API_KEY"
private const val UNCONFIGURED_API_KEY = "UNCONFIGURED"
private val KOREA_CENTER = LatLng(36.4, 127.8)
private const val KOREA_ZOOM = 7f
private const val CUSTOMER_ZOOM = 15f
private const val MAP_PADDING_PX = 96
private val MAP_DATE_FORMATTER = DateTimeFormatter.ofPattern("yyyy.MM.dd HH:mm")
