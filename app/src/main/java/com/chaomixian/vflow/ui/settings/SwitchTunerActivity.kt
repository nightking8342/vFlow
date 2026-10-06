package com.chaomixian.vflow.ui.settings

import android.content.Context
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.chaomixian.vflow.R
import com.chaomixian.vflow.ui.common.VFlowTheme
import com.chaomixian.vflow.ui.common.glass.LiquidToggleTokens
import com.chaomixian.vflow.ui.common.glass.VFlowSwitch
import com.chaomixian.vflow.ui.common.glass.switchTunerOverrides

/**
 * **玻璃开关调参页**（fork 新增，2026-10-06）。
 *
 * ## 它解决的是什么
 *
 * 开关的尺寸已经来回改过五轮，每轮都要「改常量 → 打包 → 互传 → 装上 → 看」，
 * 一次十几分钟，而**手感这种事必须在真机上看**。本页把三个尺寸做成滑杆，
 * 现场拖动即可预览 —— 定稿后再把数值写回常量（页底有「重置」）。
 *
 * ⚠️ **调参值是「运行期覆盖」而不是直接改常量**：`LiquidToggleTokens` 里的
 * `val` 在类加载时求值，运行期改不了；故新增一个 `switchTunerOverrides`
 * 的**可变覆盖表**，`LiquidToggleTokens` 的 getter 先查它、查不到才用默认值。
 * 这样本页可以在不重编译的前提下改尺寸，而**默认值仍是常量**（不点开本页的
 * 用户行为完全不变）。
 *
 * ⚠️ **覆盖只在本次进程内有效**（不落盘）：它的用途是「调到满意为止」，
 * 满意之后应当把数值写回常量并**移除对应的覆盖项**。落盘的话会变成
 * 「用户设备上有一个没人记得来源的尺寸」—— 本仓库在磁贴 `kind` 上踩过
 * 同形的坑（Gson 不填默认值 ⇒ 缺键读出 null ⇒ 静默消失）。
 */
class SwitchTunerActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            VFlowTheme {
                SwitchTunerScreen(onBack = { finish() })
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SwitchTunerScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    var trackWidth by remember { mutableFloatStateOf(LiquidToggleTokens.trackWidthDp().value) }
    var thumbWidth by remember { mutableFloatStateOf(LiquidToggleTokens.thumbWidthDp().value) }
    var thumbHeight by remember { mutableFloatStateOf(LiquidToggleTokens.thumbHeightDp().value) }
    var previewChecked by remember { mutableStateOf(true) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.switch_tuner_entry_title)) },
                navigationIcon = {
                    TextButton(onClick = onBack) { Text(stringResource(R.string.common_back)) }
                },
            )
        },
    ) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text(
                stringResource(R.string.switch_tuner_hint),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(bottom = 12.dp),
            )

            // ==== 预览 ====
            Row(
                Modifier.fillMaxWidth().padding(vertical = 20.dp),
                horizontalArrangement = Arrangement.spacedBy(24.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                VFlowSwitch(
                    checked = previewChecked,
                    onCheckedChange = { previewChecked = it },
                    modifier = Modifier.switchTunerOverrides(
                        trackWidth = trackWidth.dp,
                        thumbWidth = thumbWidth.dp,
                        thumbHeight = thumbHeight.dp,
                    ),
                )
                VFlowSwitch(
                    checked = !previewChecked,
                    onCheckedChange = { previewChecked = !it },
                    modifier = Modifier.switchTunerOverrides(
                        trackWidth = trackWidth.dp,
                        thumbWidth = thumbWidth.dp,
                        thumbHeight = thumbHeight.dp,
                    ),
                )
            }

            TuneSlider(
                label = stringResource(R.string.switch_tuner_track_width),
                value = trackWidth,
                range = 40f..72f,
                onChange = { trackWidth = it },
            )
            TuneSlider(
                label = stringResource(R.string.switch_tuner_thumb_width),
                value = thumbWidth,
                range = 16f..40f,
                onChange = { thumbWidth = it },
            )
            TuneSlider(
                label = stringResource(R.string.switch_tuner_thumb_height),
                value = thumbHeight,
                range = 12f..28f,
                onChange = { thumbHeight = it },
            )

            // ==== 派生量：调的时候要能看到「有没有越界」 ====
            val travel = trackWidth - thumbWidth - LiquidToggleTokens.paddingDp.value * 2
            Text(
                stringResource(R.string.switch_tuner_derived, travel),
                style = MaterialTheme.typography.bodySmall,
                fontWeight = FontWeight.SemiBold,
                color = if (travel > 2f) {
                    MaterialTheme.colorScheme.primary
                } else {
                    MaterialTheme.colorScheme.error
                },
                modifier = Modifier.padding(top = 8.dp),
            )

            TextButton(
                onClick = {
                    trackWidth = LiquidToggleTokens.defaultTrackWidth.value
                    thumbWidth = LiquidToggleTokens.defaultThumbWidth.value
                    thumbHeight = LiquidToggleTokens.defaultThumbHeight.value
                    clearTunerOverrides(context)
                },
                modifier = Modifier.padding(top = 16.dp),
            ) {
                Text(stringResource(R.string.switch_tuner_reset))
            }

            Text(
                stringResource(R.string.switch_tuner_current, trackWidth, thumbWidth, thumbHeight),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 8.dp),
            )
        }
    }
}

@Composable
private fun TuneSlider(
    label: String,
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    onChange: (Float) -> Unit,
) {
    Column(Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(label, style = MaterialTheme.typography.bodyMedium)
            Text("%.0f dp".format(value), style = MaterialTheme.typography.bodyMedium)
        }
        Slider(
            value = value,
            onValueChange = onChange,
            valueRange = range,
            steps = (range.endInclusive - range.start).toInt() - 1,
        )
    }
}

/** 清空调参覆盖（把开关恢复成常量默认尺寸）。 */
private fun clearTunerOverrides(@Suppress("UNUSED_PARAMETER") context: Context) {
    com.chaomixian.vflow.ui.common.glass.clearSwitchTunerOverrides()
}
