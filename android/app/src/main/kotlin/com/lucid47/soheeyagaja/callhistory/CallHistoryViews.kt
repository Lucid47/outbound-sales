package com.lucid47.soheeyagaja.callhistory

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.FileOpen
import androidx.compose.material.icons.filled.Phone
import androidx.compose.material.icons.filled.PrivacyTip
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.lucid47.soheeyagaja.customers.CustomerManagementViewModel
import com.lucid47.soheeyagaja.data.CustomerListSummary

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CallHistoryToolsDialog(
    viewModel: CustomerManagementViewModel,
    customerLists: List<CustomerListSummary>,
    onDismiss: () -> Unit,
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val selectedList = customerLists.firstOrNull { it.id == state.selectedListId }
    val directAccessAvailable = remember {
        runCatching {
            context.packageManager
                .getPackageInfo(context.packageName, PackageManager.GET_PERMISSIONS)
                .requestedPermissions
                ?.contains(Manifest.permission.READ_CALL_LOG) == true
        }.getOrDefault(false)
    }
    var pendingDays by remember { mutableStateOf<Int?>(30) }
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        if (granted) viewModel.importDeviceCallHistory(pendingDays) else viewModel.callHistoryPermissionDenied()
    }
    val csvPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) viewModel.importCallHistoryCsv(uri)
    }

    fun requestDeviceImport(days: Int?) {
        pendingDays = days
        if (
            ContextCompat.checkSelfPermission(context, Manifest.permission.READ_CALL_LOG) ==
            PackageManager.PERMISSION_GRANTED
        ) {
            viewModel.importDeviceCallHistory(days)
        } else {
            permissionLauncher.launch(Manifest.permission.READ_CALL_LOG)
        }
    }

    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Scaffold(
            modifier = Modifier.fillMaxSize(),
            topBar = {
                TopAppBar(
                    title = { Text("통화기록 가져오기", fontWeight = FontWeight.Bold) },
                    navigationIcon = {
                        IconButton(onClick = onDismiss, enabled = !state.callHistoryBusy) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "닫기")
                        }
                    },
                )
            },
        ) { padding ->
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(16.dp, padding.calculateTopPadding() + 8.dp, 16.dp, 32.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                item {
                    Surface(
                        color = MaterialTheme.colorScheme.surfaceVariant,
                        shape = androidx.compose.foundation.shape.RoundedCornerShape(8.dp),
                    ) {
                        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Text(selectedList?.name ?: "고객리스트 없음", fontWeight = FontWeight.Bold)
                            Text(
                                "전화번호가 정확히 일치하는 고객에게만 통화기록을 연결합니다. 중복 번호는 자동 연결하지 않습니다.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
                item {
                    Text("CSV 파일", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                    Text(
                        "공개 배포판에서도 사용할 수 있습니다. 전화번호와 통화일시 열은 필수이며, 통화유형과 통화시간 열은 선택입니다.",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                item {
                    Button(
                        onClick = {
                            csvPicker.launch(arrayOf("text/csv", "text/plain", "application/csv", "application/vnd.ms-excel"))
                        },
                        enabled = selectedList != null && !state.callHistoryBusy,
                        modifier = Modifier.fillMaxWidth().height(56.dp),
                    ) {
                        Icon(Icons.Default.FileOpen, contentDescription = null)
                        Spacer(Modifier.size(8.dp))
                        Text("통화기록 CSV 선택")
                    }
                }
                item {
                    Surface(
                        color = MaterialTheme.colorScheme.secondaryContainer,
                        shape = androidx.compose.foundation.shape.RoundedCornerShape(8.dp),
                    ) {
                        Row(Modifier.fillMaxWidth().padding(14.dp)) {
                            Icon(Icons.Default.PrivacyTip, contentDescription = null)
                            Spacer(Modifier.size(10.dp))
                            Text(
                                "Google Play 정책을 위해 일반 배포판은 통화기록 권한을 요청하지 않습니다.",
                                style = MaterialTheme.typography.bodySmall,
                            )
                        }
                    }
                }
                if (directAccessAvailable) {
                    item {
                        Text("이 기기에서 직접", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                        Text(
                            "개발용 APK에서만 표시됩니다. Android 통화기록 접근 권한을 한 번 허용해야 합니다.",
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    item {
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            FilledTonalButton(
                                onClick = { requestDeviceImport(30) },
                                enabled = !state.callHistoryBusy,
                                modifier = Modifier.weight(1f),
                            ) { Text("최근 30일") }
                            FilledTonalButton(
                                onClick = { requestDeviceImport(90) },
                                enabled = !state.callHistoryBusy,
                                modifier = Modifier.weight(1f),
                            ) { Text("최근 90일") }
                        }
                    }
                    item {
                        FilledTonalButton(
                            onClick = { requestDeviceImport(null) },
                            enabled = !state.callHistoryBusy,
                            modifier = Modifier.fillMaxWidth().height(52.dp),
                        ) {
                            Icon(Icons.Default.Phone, contentDescription = null)
                            Spacer(Modifier.size(8.dp))
                            Text("기기 전체 통화기록")
                        }
                    }
                }
                if (state.callHistoryBusy) {
                    item {
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) {
                            CircularProgressIndicator()
                        }
                    }
                }
                state.statusMessage?.let { message ->
                    item { Text(message, color = MaterialTheme.colorScheme.primary) }
                }
                state.errorMessage?.let { message ->
                    item { Text(message, color = MaterialTheme.colorScheme.error) }
                }
            }
        }
    }
}
