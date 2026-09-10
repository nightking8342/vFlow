package com.chaomixian.vflow.core.workflow.module.logic

import com.chaomixian.vflow.core.types.VTypeRegistry
import com.chaomixian.vflow.core.workflow.module.data.CreateVariableModule
import org.junit.Assert.assertEquals
import org.junit.Test

class FunctionParamTypeMapperTest {

    @Test
    fun `toFullTypeId maps short values to registry ids`() {
        assertEquals(VTypeRegistry.STRING.id, FunctionParamTypeMapper.toFullTypeId("string"))
        assertEquals(VTypeRegistry.NUMBER.id, FunctionParamTypeMapper.toFullTypeId("number"))
        assertEquals(VTypeRegistry.BOOLEAN.id, FunctionParamTypeMapper.toFullTypeId("boolean"))
        assertEquals(VTypeRegistry.DICTIONARY.id, FunctionParamTypeMapper.toFullTypeId("dictionary"))
        assertEquals(VTypeRegistry.LIST.id, FunctionParamTypeMapper.toFullTypeId("list"))
        assertEquals(VTypeRegistry.IMAGE.id, FunctionParamTypeMapper.toFullTypeId("image"))
        assertEquals(VTypeRegistry.FILE.id, FunctionParamTypeMapper.toFullTypeId("file"))
        assertEquals(VTypeRegistry.COORDINATE.id, FunctionParamTypeMapper.toFullTypeId("coordinate"))
    }

    @Test
    fun `toFullTypeId returns full id unchanged when already full`() {
        assertEquals(VTypeRegistry.STRING.id, FunctionParamTypeMapper.toFullTypeId(VTypeRegistry.STRING.id))
        assertEquals(VTypeRegistry.NUMBER.id, FunctionParamTypeMapper.toFullTypeId(VTypeRegistry.NUMBER.id))
    }

    @Test
    fun `toFullTypeId handles blank and unknown`() {
        assertEquals(VTypeRegistry.ANY.id, FunctionParamTypeMapper.toFullTypeId(null))
        assertEquals(VTypeRegistry.ANY.id, FunctionParamTypeMapper.toFullTypeId(""))
        assertEquals("bogus", FunctionParamTypeMapper.toFullTypeId("bogus"))
    }

    @Test
    fun `toShortValue maps full ids to short values`() {
        assertEquals(CreateVariableModule.TYPE_STRING, FunctionParamTypeMapper.toShortValue(VTypeRegistry.STRING.id))
        assertEquals(CreateVariableModule.TYPE_NUMBER, FunctionParamTypeMapper.toShortValue(VTypeRegistry.NUMBER.id))
        assertEquals(CreateVariableModule.TYPE_BOOLEAN, FunctionParamTypeMapper.toShortValue(VTypeRegistry.BOOLEAN.id))
        assertEquals(CreateVariableModule.TYPE_DICTIONARY, FunctionParamTypeMapper.toShortValue(VTypeRegistry.DICTIONARY.id))
        assertEquals(CreateVariableModule.TYPE_LIST, FunctionParamTypeMapper.toShortValue(VTypeRegistry.LIST.id))
        assertEquals(CreateVariableModule.TYPE_IMAGE, FunctionParamTypeMapper.toShortValue(VTypeRegistry.IMAGE.id))
        assertEquals(CreateVariableModule.TYPE_FILE, FunctionParamTypeMapper.toShortValue(VTypeRegistry.FILE.id))
        assertEquals(CreateVariableModule.TYPE_COORDINATE, FunctionParamTypeMapper.toShortValue(VTypeRegistry.COORDINATE.id))
    }

    @Test
    fun `toShortValue round trips`() {
        for (short in CreateVariableModule.TYPE_OPTIONS) {
            assertEquals(short, FunctionParamTypeMapper.toShortValue(FunctionParamTypeMapper.toFullTypeId(short)))
        }
    }
}
