package com.chaomixian.vflow.ui.settings

import android.os.Bundle
import android.widget.Toast
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import com.chaomixian.vflow.ui.common.glass.VFlowSwitch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.ButtonGroupDefaults
import androidx.compose.material3.ToggleButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.chaomixian.vflow.R
import com.chaomixian.vflow.core.types.VObject
import com.chaomixian.vflow.core.types.VObjectFactory
import com.chaomixian.vflow.core.types.basic.VBoolean
import com.chaomixian.vflow.core.types.basic.VNumber
import com.chaomixian.vflow.core.types.basic.VString
import com.chaomixian.vflow.core.workflow.GlobalVariableStore
import com.chaomixian.vflow.core.workflow.module.data.CreateVariableModule
import com.chaomixian.vflow.ui.common.BaseActivity
import com.chaomixian.vflow.ui.common.VFlowTheme
import java.util.Locale

class GlobalVariableConfigActivity : BaseActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            VFlowTheme {
                GlobalVariableConfigScreen(onBack = { finish() })
            }
        }
    }
}

private enum class GlobalVariableType {
    TEXT,
    NUMBER,
    BOOLEAN
}

private data class GlobalVariableItem(
    val name: String,
    val value: VObject
)

private data class GlobalVariableEditorState(
    val originalName: String? = null,
    val name: String = "",
    val type: GlobalVariableType = GlobalVariableType.TEXT,
    val valueText: String = "",
    val booleanValue: Boolean = false
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun GlobalVariableConfigScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val items = remember { mutableStateListOf<GlobalVariableItem>().apply { addAll(loadGlobalVariableItems(context)) } }
    var editorState by remember { mutableStateOf<GlobalVariableEditorState?>(null) }
    var deleteTarget by remember { mutableStateOf<GlobalVariableItem?>(null) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.global_variable_config_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.common_back))
                    }
                }
            )
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text(
                text = stringResource(R.string.global_variable_config_desc),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            FilledTonalButton(
                onClick = { editorState = GlobalVariableEditorState() },
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(stringResource(R.string.global_variable_config_add))
            }

            if (items.isEmpty()) {
                Card(modifier = Modifier.fillMaxWidth()) {
                    Text(
                        text = stringResource(R.string.global_variable_config_empty),
                        modifier = Modifier.padding(16.dp),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            } else {
                items.forEach { item ->
                    Card(modifier = Modifier.fillMaxWidth()) {
                        Column(
                            modifier = Modifier.padding(16.dp),
                            verticalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            Text(item.name, style = MaterialTheme.typography.titleMedium)
                            Text(
                                text = "${typeLabel(context, item.value)}: ${formatValue(item.value)}",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis
                            )
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(12.dp)
                            ) {
                                OutlinedButton(
                                    onClick = {
                                        editorState = item.toEditorState()
                                    },
                                    modifier = Modifier.weight(1f)
                                ) {
                                    Text(stringResource(R.string.global_variable_config_edit))
                                }
                                TextButton(
                                    onClick = { deleteTarget = item },
                                    modifier = Modifier.weight(1f)
                                ) {
                                    Text(stringResource(R.string.global_variable_config_delete))
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    editorState?.let { state ->
        GlobalVariableEditorDialog(
            initialState = state,
            onDismiss = { editorState = null },
            onSave = { updated ->
                val normalizedName = updated.name.trim()
                if (normalizedName.isBlank()) {
                    Toast.makeText(context, context.getString(R.string.global_variable_config_invalid_name), Toast.LENGTH_SHORT).show()
                    return@GlobalVariableEditorDialog
                }

                val value = when (updated.type) {
                    GlobalVariableType.TEXT -> VString(updated.valueText)
                    GlobalVariableType.NUMBER -> updated.valueText.toDoubleOrNull()?.let { VNumber(it) }
                    GlobalVariableType.BOOLEAN -> VBoolean(updated.booleanValue)
                } ?: run {
                    Toast.makeText(context, context.getString(R.string.global_variable_config_invalid_value), Toast.LENGTH_SHORT).show()
                    return@GlobalVariableEditorDialog
                }

                val duplicate = items.any { it.name == normalizedName && it.name != updated.originalName }
                if (duplicate) {
                    Toast.makeText(context, context.getString(R.string.global_variable_config_duplicate_name), Toast.LENGTH_SHORT).show()
                    return@GlobalVariableEditorDialog
                }

                val newMap = items.associate { it.name to it.value }.toMutableMap()
                updated.originalName?.let { oldName ->
                    if (oldName != normalizedName) {
                        newMap.remove(oldName)
                    }
                }
                newMap[normalizedName] = value

                persistGlobalVariableItems(context, newMap)
                items.clear()
                items.addAll(loadGlobalVariableItems(context))
                editorState = null
            }
        )
    }

    deleteTarget?.let { target ->
        AlertDialog(
            onDismissRequest = { deleteTarget = null },
            title = { Text(stringResource(R.string.global_variable_config_delete_confirm_title)) },
            text = { Text(stringResource(R.string.global_variable_config_delete_confirm_message, target.name)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        val newMap = items.associate { it.name to it.value }.toMutableMap()
                        newMap.remove(target.name)
                        persistGlobalVariableItems(context, newMap)
                        items.clear()
                        items.addAll(loadGlobalVariableItems(context))
                        deleteTarget = null
                    }
                ) { Text(stringResource(R.string.global_variable_config_delete)) }
            },
            dismissButton = {
                TextButton(onClick = { deleteTarget = null }) { Text(stringResource(R.string.common_cancel)) }
            }
        )
    }
}

@Composable
private fun GlobalVariableEditorDialog(
    initialState: GlobalVariableEditorState,
    onDismiss: () -> Unit,
    onSave: (GlobalVariableEditorState) -> Unit
) {
    var name by remember(initialState) { mutableStateOf(initialState.name) }
    var type by remember(initialState) { mutableStateOf(initialState.type) }
    var valueText by remember(initialState) { mutableStateOf(initialState.valueText) }
    var booleanValue by remember(initialState) { mutableStateOf(initialState.booleanValue) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                stringResource(
                    if (initialState.originalName == null) {
                        R.string.global_variable_config_add
                    } else {
                        R.string.global_variable_config_edit
                    }
                )
            )
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text(stringResource(R.string.global_variable_config_name_label)) },
                    singleLine = true
                )
                TypeChoiceButtonGroup(
                    selectedType = type,
                    onTypeSelected = { type = it }
                )
                when (type) {
                    GlobalVariableType.BOOLEAN -> Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(stringResource(R.string.global_variable_config_boolean_value))
                        VFlowSwitch(checked = booleanValue, onCheckedChange = { booleanValue = it })
                    }
                    else -> OutlinedTextField(
                        value = valueText,
                        onValueChange = { valueText = it },
                        modifier = Modifier.fillMaxWidth(),
                        label = {
                            Text(
                                stringResource(
                                    if (type == GlobalVariableType.NUMBER) {
                                        R.string.global_variable_config_number_value
                                    } else {
                                        R.string.global_variable_config_value_label
                                    }
                                )
                            )
                        },
                        placeholder = {
                            Text(
                                stringResource(
                                    if (type == GlobalVariableType.NUMBER) {
                                        R.string.global_variable_config_number_hint
                                    } else {
                                        R.string.global_variable_config_text_hint
                                    }
                                )
                            )
                        },
                        singleLine = true
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { onSave(initialState.copy(name = name, type = type, valueText = valueText, booleanValue = booleanValue)) }) {
                Text(stringResource(R.string.common_save))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.common_cancel)) }
        }
    )
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun TypeChoiceButtonGroup(
    selectedType: GlobalVariableType,
    onTypeSelected: (GlobalVariableType) -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(ButtonGroupDefaults.ConnectedSpaceBetween)
    ) {
        listOf(
            GlobalVariableType.TEXT to stringResource(R.string.option_vflow_variable_create_type_string),
            GlobalVariableType.NUMBER to stringResource(R.string.option_vflow_variable_create_type_number),
            GlobalVariableType.BOOLEAN to stringResource(R.string.option_vflow_variable_create_type_boolean)
        ).forEachIndexed { index, (itemType, label) ->
            ToggleButton(
                checked = selectedType == itemType,
                onCheckedChange = { checked ->
                    if (checked && selectedType != itemType) {
                        onTypeSelected(itemType)
                    }
                },
                modifier = Modifier
                    .weight(1f)
                    .semantics { role = Role.RadioButton },
                shapes = when (index) {
                    0 -> ButtonGroupDefaults.connectedLeadingButtonShapes()
                    2 -> ButtonGroupDefaults.connectedTrailingButtonShapes()
                    else -> ButtonGroupDefaults.connectedMiddleButtonShapes()
                }
            ) {
                Text(label)
            }
        }
    }
}

private fun GlobalVariableItem.toEditorState(): GlobalVariableEditorState {
    return when (value) {
        is VString -> GlobalVariableEditorState(
            originalName = name,
            name = name,
            type = GlobalVariableType.TEXT,
            valueText = value.raw
        )
        is VNumber -> GlobalVariableEditorState(
            originalName = name,
            name = name,
            type = GlobalVariableType.NUMBER,
            valueText = value.raw.toString()
        )
        is VBoolean -> GlobalVariableEditorState(
            originalName = name,
            name = name,
            type = GlobalVariableType.BOOLEAN,
            booleanValue = value.raw
        )
        else -> GlobalVariableEditorState(
            originalName = name,
            name = name,
            type = GlobalVariableType.TEXT,
            valueText = value.asString()
        )
    }
}

private fun loadGlobalVariableItems(context: android.content.Context): List<GlobalVariableItem> {
    return GlobalVariableStore.getAll(context)
        .toList()
        .sortedBy { it.first }
        .map { (name, value) -> GlobalVariableItem(name = name, value = value) }
}

private fun persistGlobalVariableItems(context: android.content.Context, values: Map<String, VObject>) {
    GlobalVariableStore.replaceAll(context, values)
}

private fun formatValue(value: VObject): String {
    return when (value) {
        is VString -> value.raw
        is VNumber -> value.raw.toString()
        is VBoolean -> value.raw.toString()
        else -> value.asString()
    }
}

private fun typeLabel(context: android.content.Context, value: VObject): String {
    return when (value) {
        is VString -> context.getString(R.string.option_vflow_variable_create_type_string)
        is VNumber -> context.getString(R.string.option_vflow_variable_create_type_number)
        is VBoolean -> context.getString(R.string.option_vflow_variable_create_type_boolean)
        else -> context.getString(R.string.variable_type_text)
    }
}
