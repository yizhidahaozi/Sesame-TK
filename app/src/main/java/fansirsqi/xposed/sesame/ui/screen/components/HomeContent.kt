package fansirsqi.xposed.sesame.ui.screen.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import fansirsqi.xposed.sesame.ui.screen.DeviceInfoCard
import fansirsqi.xposed.sesame.ui.screen.ModuleStatusCard
import fansirsqi.xposed.sesame.ui.screen.ServicesStatusCard
import fansirsqi.xposed.sesame.ui.viewmodel.MainViewModel

@Composable
fun HomeContent(
    moduleStatus: MainViewModel.ModuleStatus,
    serviceStatus: MainViewModel.ServiceStatus,
    deviceInfoMap: Map<String, String>?,
    oneWord: String,
    isOneWordLoading: Boolean,
    isStatusCardExpanded: Boolean,
    isServiceCardExpanded: Boolean,
    onStatusCardClick: () -> Unit,
    onServiceCardClick: () -> Unit,
    onOneWordClick: () -> Unit
) {
    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
        contentPadding = PaddingValues(vertical = 16.dp)
    ) {
        item {
            Column(
                modifier = Modifier.fillMaxWidth(),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(
                    "本应用开源免费,严禁倒卖!!\n如果你在闲鱼看到,欢迎给我们反馈",
                    textAlign = TextAlign.Center,
                    color = MaterialTheme.colorScheme.error,
                    fontWeight = FontWeight.Bold,
                    style = MaterialTheme.typography.titleSmall
                )
            }
        }
        // 1. 模块状态
        item {
            ModuleStatusCard(
                status = moduleStatus,
                expanded = isStatusCardExpanded,
                onClick = onStatusCardClick
            )
        }

        // 2. 服务权限
        item {
            ServicesStatusCard(
                status = serviceStatus,
                expanded = isServiceCardExpanded,
                onClick = onServiceCardClick
            )
        }

        // 3. 设备信息
        item {
            if (deviceInfoMap != null) {
                DeviceInfoCard(deviceInfoMap)
            } else {
                Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator()
                }
            }
        }

        // 4. 一言
        item {
            OneWordCard( // 提取出的一言卡片组件
                oneWord = oneWord,
                isLoading = isOneWordLoading,
                onClick = onOneWordClick
            )
        }


    }
}