package com.lucid47.soheeyagaja.location

import android.annotation.SuppressLint
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.lucid47.soheeyagaja.customers.CustomerManagementViewModel
import com.lucid47.soheeyagaja.data.CustomerWithFields
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import org.json.JSONArray
import org.json.JSONObject

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
    val visibleCustomers = customers.filter { record ->
        record.customer.latitude != null && record.customer.longitude != null &&
            (!state.mapScheduleOnly || record.customer.id in scheduledIds)
    }
    val html = mapHtml(visibleCustomers, lastTouchByCustomer)

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
                            "지도 표시 ${visibleCustomers.size}명",
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
                MapWebView(html = html, modifier = Modifier.fillMaxSize())
            }
        }
    }
}

@SuppressLint("SetJavaScriptEnabled")
@Composable
private fun MapWebView(html: String, modifier: Modifier = Modifier) {
    AndroidView(
        modifier = modifier,
        factory = { context ->
            WebView(context).apply {
                webViewClient = WebViewClient()
                settings.javaScriptEnabled = true
                settings.domStorageEnabled = true
                loadDataWithBaseURL("https://localhost/", html, "text/html", "UTF-8", null)
            }
        },
        update = { webView ->
            webView.loadDataWithBaseURL("https://localhost/", html, "text/html", "UTF-8", null)
        },
    )
}

private fun mapHtml(
    customers: List<CustomerWithFields>,
    lastTouchByCustomer: Map<Long, Long?>,
): String {
    val markers = JSONArray().apply {
        customers.forEach { record ->
            val customer = record.customer
            put(
                JSONObject().apply {
                    put("name", customer.name)
                    put("address", customer.address)
                    put("lat", customer.latitude)
                    put("lng", customer.longitude)
                    put(
                        "lastTouch",
                        lastTouchByCustomer[customer.id]?.let {
                            MAP_DATE_FORMATTER.format(Instant.ofEpochMilli(it).atZone(ZoneId.systemDefault()))
                        } ?: "기록 없음",
                    )
                },
            )
        }
    }
    return """
        <!doctype html><html><head><meta name="viewport" content="width=device-width,initial-scale=1,maximum-scale=1">
        <link rel="stylesheet" href="https://unpkg.com/leaflet@1.9.4/dist/leaflet.css">
        <style>html,body,#map{height:100%;margin:0} .leaflet-popup-content{font-size:16px;line-height:1.45}</style></head>
        <body><div id="map"></div><script src="https://unpkg.com/leaflet@1.9.4/dist/leaflet.js"></script><script>
        const points=$markers; const map=L.map('map').setView([36.4,127.8],7);
        L.tileLayer('https://{s}.tile.openstreetmap.org/{z}/{x}/{y}.png',{maxZoom:19,attribution:'© OpenStreetMap'}).addTo(map);
        const bounds=[]; points.forEach(p=>{const pos=[p.lat,p.lng];bounds.push(pos);
          L.marker(pos).addTo(map).bindPopup('<b>'+escapeHtml(p.name)+'</b><br>'+escapeHtml(p.lastTouch)+'<br>'+escapeHtml(p.address));});
        if(bounds.length===1) map.setView(bounds[0],15); else if(bounds.length>1) map.fitBounds(bounds,{padding:[32,32]});
        function escapeHtml(v){return String(v??'').replace(/[&<>'"]/g,c=>({'&':'&amp;','<':'&lt;','>':'&gt;',"'":'&#39;','"':'&quot;'}[c]));}
        </script></body></html>
    """.trimIndent()
}

private val MAP_DATE_FORMATTER = DateTimeFormatter.ofPattern("yyyy.MM.dd HH:mm")
