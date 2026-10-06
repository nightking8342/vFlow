package com.chaomixian.vflow.ui.workflow_list

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.DashboardCustomize
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.chaomixian.vflow.R
import com.chaomixian.vflow.core.workflow.TileGate
import com.chaomixian.vflow.core.workflow.TileSlot
import com.chaomixian.vflow.core.workflow.model.TileKind

data class TileSelectionItem(
    val tileIndex: Int,
    val assignedWorkflowName: String?,
    val isSelected: Boolean,
    /**
     * 该槽位所属的池。
     *
     * ⚠️ 面板**只列这一池的槽位**（§4.6 闸 2）—— 两池的槽位号都是 0..19、
     * 卡片长得一样，混在一起会让用户「想在开关池加、却点进了执行池的槽」，
     * 而绑定**不会**因此失败（槽位合法），只是行为完全不同。
     * ⚠️ 绑定失败时的提示走 [TileGate.mismatchMessageRes]，与「越界态」的文案**刻意分开**。
     */
    val kind: TileKind = TileKind.EXECUTE,
)

@Composable
fun TileSelectionSheet(
    items: List<TileSelectionItem>,
    onSelect: (TileSelectionItem) -> Unit,
    modifier: Modifier = Modifier,
    kind: TileKind = TileKind.EXECUTE,
) {
    Column(
        modifier = modifier.padding(bottom = 16.dp)
    ) {
        Text(
            // ⚠️ 标题带上池名（§4.6 闸 2）：用户点的是「添加到控制中心（开关）」，
            //    面板标题必须能确认这一点，否则在 40 个槽位里选错池无从察觉。
            text = stringResource(R.string.tile_selection_title) + " · " +
                stringResource(TileGate.poolTitleRes(kind)),
            style = MaterialTheme.typography.headlineSmall,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
        )
        Text(
            text = stringResource(R.string.tile_selection_hint),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 16.dp)
        )
        Spacer(modifier = Modifier.height(16.dp))
        LazyColumn(
            modifier = Modifier.fillMaxWidth(),
            contentPadding = PaddingValues(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            // ⚠️ key 用 `"kind:index"` 而非裸 `tileIndex` —— 两池的 tileIndex 区间不重叠
            //    （0-19 / 20-39），今天裸用也对；但 `key` 一旦重复 Compose 会抛
            //    `IllegalArgumentException`，而**将来任何一次区间调整**都可能让它重复。
            //    带上 kind 是零成本的保险。
            items(items, key = { "${it.kind}:${it.tileIndex}" }) { item ->
                TileSelectionCard(
                    item = item,
                    onClick = { onSelect(item) }
                )
            }
        }
    }
}

@Composable
private fun TileSelectionCard(
    item: TileSelectionItem,
    onClick: () -> Unit,
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        shape = RoundedCornerShape(22.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (item.isSelected) {
                MaterialTheme.colorScheme.primaryContainer
            } else {
                MaterialTheme.colorScheme.surfaceContainerLow
            }
        )
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 18.dp, vertical = 16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = Icons.Rounded.DashboardCustomize,
                contentDescription = null,
                tint = if (item.isSelected) {
                    MaterialTheme.colorScheme.onPrimaryContainer
                } else {
                    MaterialTheme.colorScheme.primary
                }
            )
            Spacer(modifier = Modifier.width(14.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    // ⚠️ 用 `TileSlot.displayName` 而**不是** `tile_label` + 绝对索引 ——
                    //    两池的池内槽号都是 0..19，按绝对索引显示会得到「开关磁贴 21」
                    //    这种对不上面板的名字，而用户在系统面板里看到的是「vFlow Toggle 1」。
                    //    这里与 manifest 的 `android:label` 是**同一套口径**（有测试锁）。
                    text = TileSlot.displayName(
                        item.kind,
                        TileSlot.indexInKind(item.tileIndex) ?: item.tileIndex
                    ),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Medium,
                    color = if (item.isSelected) {
                        MaterialTheme.colorScheme.onPrimaryContainer
                    } else {
                        MaterialTheme.colorScheme.onSurface
                    }
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = item.assignedWorkflowName ?: stringResource(R.string.tile_empty),
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (item.isSelected) {
                        MaterialTheme.colorScheme.onPrimaryContainer
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    }
                )
            }
            if (item.isSelected) {
                Spacer(modifier = Modifier.width(12.dp))
                Icon(
                    imageVector = Icons.Rounded.Check,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onPrimaryContainer
                )
            }
        }
    }
}
