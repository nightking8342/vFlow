package com.chaomixian.vflow.core.workflow.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FunctionParamValidatorTest {

    @Test
    fun `isValidName_accepts_normal_names`() {
        assertTrue(FunctionParamValidator.isValidName("url"))
        assertTrue(FunctionParamValidator.isValidName("max_retry_count"))
        assertTrue(FunctionParamValidator.isValidName("a1_b2"))
        // 与项目一致：不强制 snake_case，允许大写、中文、数字开头
        assertTrue(FunctionParamValidator.isValidName("MyVar"))
        assertTrue(FunctionParamValidator.isValidName("城市"))
        assertTrue(FunctionParamValidator.isValidName("1abc"))
        assertTrue(FunctionParamValidator.isValidName("_abc"))
    }

    @Test
    fun `isValidName_rejects_blank_and_special_chars`() {
        assertFalse(FunctionParamValidator.isValidName(""))
        assertFalse(FunctionParamValidator.isValidName("has hyphen"))
        assertFalse(FunctionParamValidator.isValidName("has.dot"))        // . 会破坏 [[var]] 引用
        assertFalse(FunctionParamValidator.isValidName("has[bracket"))
        assertFalse(FunctionParamValidator.isValidName("has]bracket"))
        assertFalse(FunctionParamValidator.isValidName("has${'$'}{var}"))   // ${ 会破坏内联脚本
    }

    @Test
    fun `validate_returns_valid_for_empty_or_ok_params`() {
        assertTrue(FunctionParamValidator.validate(emptyList()).isValid)
        val params = listOf(
            FunctionParam("url", "vflow.type.string"),
            FunctionParam("city", "vflow.type.string", isRequired = true)
        )
        assertTrue(FunctionParamValidator.validate(params).isValid)
    }

    @Test
    fun `validate_detects_duplicate_name`() {
        val params = listOf(
            FunctionParam("url", "vflow.type.string"),
            FunctionParam("url", "vflow.type.number")
        )
        val result = FunctionParamValidator.validate(params)
        assertFalse(result.isValid)
        assertEquals(FunctionParamError.DUPLICATE_NAME, result.errorKind)
        assertEquals("url", result.duplicateName)
    }

    @Test
    fun `validate_detects_invalid_name`() {
        val params = listOf(
            FunctionParam("bad.name", "vflow.type.string")
        )
        val result = FunctionParamValidator.validate(params)
        assertFalse(result.isValid)
        assertEquals(FunctionParamError.INVALID_NAME, result.errorKind)
    }

    @Test
    fun `validate_considers_blank_name_invalid`() {
        val params = listOf(FunctionParam("", "vflow.type.string"))
        assertEquals(FunctionParamError.INVALID_NAME, FunctionParamValidator.validate(params).errorKind)
    }
}
