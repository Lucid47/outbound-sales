package com.lucid47.soheeyagaja.messagehistory

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
import androidx.compose.material.icons.automirrored.filled.Message
import androidx.compose.material.icons.filled.PrivacyTip
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
fun MessageHistoryToolsDialog(
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
                ?.contains(Manifest.permission.READ_SMS) == true
        }.getOrDefault(false)
    }
    var pendingDays by remember { mutableStateOf<Int?>(30) }
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        if (granted) viewModel.importDeviceMessageHistory(pendingDays)
        else viewModel.messageHistoryPermissionDenied()
    }

    fun requestDeviceImport(days: Int?) {
        pendingDays = days
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.READ_SMS) == PackageManager.PERMISSION_GRANTED) {
            viewModel.importDeviceMessageHistory(days)
        } else {
            permissionLauncher.launch(Manifest.permission.READ_SMS)
        }
    }

    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Scaffold(
            modifier = Modifier.fillMaxSize(),
            topBar = {
                TopAppBar(
                    title = { Text("문자기록 가져오기", fontWeight = FontWeight.Bold) },
                    navigationIcon = {
                        IconButton(onClick = onDismiss, enabled = !state.messageHistoryBusy) {
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
                                "Galaxy 메시지 앱에 저장된 받은 문자와 보낸 문자를 읽어 고객 히스토리에 복사합니다. 이 화면에서는 문자를 보내지 않습니다.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
                if (directAccessAvailable) {
                    item {
                        Text("가져올 기간", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                        Text(
                            "전화번호가 정확히 일치하는 고객에게만 연결하며, 중복 번호가 있는 고객은 자동 연결하지 않습니다.",
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    item {
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            FilledTonalButton(
                                onClick = { requestDeviceImport(30) },
                                enabled = selectedList != null && !state.messageHistoryBusy,
                                modifier = Modifier.weight(1f),
                            ) { Text("최근 30일") }
                            FilledTonalButton(
                                onClick = { requestDeviceImport(90) },
                                enabled = selectedList != null && !state.messageHistoryBusy,
                                modifier = Modifier.weight(1f),
                            ) { Text("최근 90일") }
                        }
                    }
                    item {
                        FilledTonalButton(
                            onClick = { requestDeviceImport(null) },
                            enabled = selectedList != null && !state.messageHistoryBusy,
                            modifier = Modifier.fillMaxWidth().height(52.dp),
                        ) {
                            Icon(Icons.AutoMirrored.Filled.Message, contentDescription = null)
                            Spacer(Modifier.size(8.dp))
                            Text("기기 전체 문자기록")
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
                                    "표준 SMS 문자만 가져옵니다. 채팅+ RCS와 사진·파일 첨부는 Android가 동일한 기록 접근을 제공하지 않아 제외됩니다.",
                                    style = MaterialTheme.typography.bodySmall,
                                )
                            }
                        }
                    }
                } else {
                    item {
                        Surface(
                            color = MaterialTheme.colorScheme.secondaryContainer,
                            shape = androidx.compose.foundation.shape.RoundedCornerShape(8.dp),
                        ) {
                            Row(Modifier.fillMaxWidth().padding(14.dp)) {
                                Icon(Icons.Default.PrivacyTip, contentDescription = null)
                                Spacer(Modifier.size(10.dp))
                                Text(
                                    "Google Play 일반 배포판은 문자 읽기 권한 정책 때문에 직접 가져오기를 제공하지 않습니다.",
                                    style = MaterialTheme.typography.bodySmall,
                                )
                            }
                        }
                    }
                }
                if (state.messageHistoryBusy) {
                    item {
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) {
                            CircularProgressIndicator()
                        }
                    }
                }
            }
        }
    }
}
