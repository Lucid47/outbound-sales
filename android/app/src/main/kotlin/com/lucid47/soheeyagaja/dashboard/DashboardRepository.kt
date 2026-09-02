package com.lucid47.soheeyagaja.dashboard

import androidx.room.withTransaction
import com.lucid47.soheeyagaja.data.AppDatabase
import com.lucid47.soheeyagaja.data.DashboardSettingsEntity
import com.lucid47.soheeyagaja.data.DashboardStatusEntity
import com.lucid47.soheeyagaja.data.ProcessStatusLogEntity

enum class DashboardPaletteFamily(val displayName: String, val colors: List<String>) {
    BLUE("파랑", listOf("EAF3FF", "D4E7FF", "B6D4FE", "8CBDF6", "5C9CEB", "357BD8", "1E5FBF", "164A98", "103A78", "0A2857")),
    GREEN("초록", listOf("E9F8F0", "CFEFDE", "A9E1C3", "7ACF9F", "4CB77C", "2A9A60", "207C4D", "17623C", "104A2E", "0A3420")),
    PURPLE("보라", listOf("F2ECFF", "E2D5FF", "CDB7FA", "AE91F0", "8C6BE0", "7150CC", "593BB0", "452D8C", "34216B", "25164E")),
    ORANGE("주황", listOf("FFF2E5", "FFDFC2", "FFC78F", "FBAA58", "EC8A2D", "D96D16", "B95310", "923E0C", "6E2D08", "4D1E05")),
    RED("빨강", listOf("FFEDEC", "FFD8D5", "FFB8B3", "F98D86", "EC625A", "D9433B", "B92F29", "94231F", "711916", "50100E")),
    GRAY("회색", listOf("F2F4F7", "E4E8ED", "CED4DC", "B1BAC6", "929EAD", "748292", "5B6877", "46515E", "333C47", "232A32")),
}

class DashboardRepository(private val database: AppDatabase) {
    private val dao = database.dashboardDao()

    fun observeStatuses() = dao.observeStatuses()

    fun observeSettings() = dao.observeSettings()

    suspend fun ensureDefaults() = database.withTransaction {
        if (dao.getStatuses().isEmpty()) {
            val now = System.currentTimeMillis()
            dao.upsertStatuses(defaultNames.mapIndexed { index, name ->
                DashboardStatusEntity(
                    id = "dashboard-status-${index + 1}",
                    name = name,
                    colorHex = colorFor(index, defaultNames.size, DashboardPaletteFamily.BLUE),
                    orderIndex = index,
                    updatedAtEpochMillis = now,
                )
            })
        }
        if (dao.getSettings() == null) {
            dao.upsertSettings(
                DashboardSettingsEntity(updatedAtEpochMillis = System.currentTimeMillis()),
            )
        }
    }

    suspend fun setCustomerStatus(customerId: Long, statusId: String) = database.withTransaction {
        val customer = requireNotNull(database.customerDao().getById(customerId)) { "고객을 찾지 못했습니다." }
        val statuses = dao.getStatuses()
        val next = requireNotNull(statuses.firstOrNull { it.id == statusId }) { "상태를 찾지 못했습니다." }
        val previous = statuses.firstOrNull { it.id == customer.dashboardStatusId } ?: statuses.firstOrNull()
        if (previous?.id == next.id && customer.dashboardStatusId != null) return@withTransaction
        val now = System.currentTimeMillis()
        dao.setCustomerStatus(customer.id, next.id, now)
        dao.insertProcessLog(
            ProcessStatusLogEntity(
                listId = customer.listId,
                customerId = customer.id,
                previousStatusId = previous?.id,
                previousStatusName = previous?.name,
                nextStatusId = next.id,
                nextStatusName = next.name,
                createdAtEpochMillis = now,
            ),
        )
        database.customerListDao().touch(customer.listId, now)
    }

    suspend fun setStatusCount(requestedCount: Int) = database.withTransaction {
        val count = requestedCount.coerceIn(1, 10)
        val current = dao.getStatuses().sortedBy(DashboardStatusEntity::orderIndex).toMutableList()
        if (current.size == count) return@withTransaction
        val now = System.currentTimeMillis()
        if (count < current.size) {
            val removed = current.drop(count)
            val remaining = current.take(count).toMutableList()
            dao.moveCustomersFromRemovedStatuses(removed.map { it.id }, remaining.last().id, now)
            dao.deleteStatuses(removed.map { it.id })
            current.clear()
            current.addAll(remaining)
        } else {
            for (index in current.size until count) {
                current += DashboardStatusEntity(
                    id = "dashboard-status-${now}-$index",
                    name = if (index < defaultNames.size) defaultNames[index] else "상태 ${index + 1}",
                    colorHex = "5C9CEB",
                    orderIndex = index,
                    updatedAtEpochMillis = now,
                )
            }
        }
        val family = paletteFamily(dao.getSettings()?.paletteFamily)
        dao.upsertStatuses(current.mapIndexed { index, status ->
            status.copy(
                colorHex = colorFor(index, current.size, family),
                orderIndex = index,
                updatedAtEpochMillis = now,
            )
        })
        dao.upsertSettings(
            (dao.getSettings() ?: DashboardSettingsEntity(updatedAtEpochMillis = now)).copy(
                statusCount = count,
                updatedAtEpochMillis = now,
            ),
        )
    }

    suspend fun renameStatus(statusId: String, name: String) = database.withTransaction {
        val trimmed = name.trim()
        if (trimmed.isEmpty()) return@withTransaction
        val now = System.currentTimeMillis()
        val statuses = dao.getStatuses()
        dao.upsertStatuses(statuses.map { status ->
            if (status.id == statusId) status.copy(name = trimmed, updatedAtEpochMillis = now) else status
        })
    }

    suspend fun setPalette(family: DashboardPaletteFamily) = database.withTransaction {
        val now = System.currentTimeMillis()
        val statuses = dao.getStatuses().sortedBy(DashboardStatusEntity::orderIndex)
        dao.upsertStatuses(statuses.mapIndexed { index, status ->
            status.copy(
                colorHex = colorFor(index, statuses.size, family),
                orderIndex = index,
                updatedAtEpochMillis = now,
            )
        })
        dao.upsertSettings(
            (dao.getSettings() ?: DashboardSettingsEntity(updatedAtEpochMillis = now)).copy(
                paletteFamily = family.name,
                statusCount = statuses.size,
                updatedAtEpochMillis = now,
            ),
        )
    }

    suspend fun setLegendVisible(visible: Boolean) {
        val now = System.currentTimeMillis()
        dao.upsertSettings(
            (dao.getSettings() ?: DashboardSettingsEntity(updatedAtEpochMillis = now)).copy(
                showsLegend = visible,
                updatedAtEpochMillis = now,
            ),
        )
    }

    private fun paletteFamily(value: String?): DashboardPaletteFamily =
        DashboardPaletteFamily.entries.firstOrNull { it.name == value } ?: DashboardPaletteFamily.BLUE

    companion object {
        val defaultNames = listOf("신규", "연락 대기", "상담 진행", "후속 관리", "완료")

        fun colorFor(index: Int, count: Int, family: DashboardPaletteFamily): String {
            if (count <= 1) return family.colors[family.colors.lastIndex / 2]
            val position = index.toDouble() * family.colors.lastIndex / (count - 1).toDouble()
            return family.colors[position.toInt().coerceIn(family.colors.indices)]
        }
    }
}
