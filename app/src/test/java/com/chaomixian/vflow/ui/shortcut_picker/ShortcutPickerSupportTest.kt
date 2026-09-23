package com.chaomixian.vflow.ui.shortcut_picker

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ShortcutPickerSupportTest {

    @Test
    fun buildLaunchCommand_parsesIntentAndExtras() {
        val command = ShortcutPickerSupport.buildLaunchCommand(
            "[Intent { act=com.tencent.mm.ui.ShortCutDispatchAction cmp=com.tencent.mm/com.tencent.mm.ui.LauncherUI flg=0x10000000 }/PersistableBundle[{LauncherUI.Shortcut.LaunchType=launch_type_scan_qrcode}]]"
        )

        requireNotNull(command)
        assertTrue(command.startsWith("am start"))
        assertTrue(command.contains("-a 'com.tencent.mm.ui.ShortCutDispatchAction'"))
        assertTrue(command.contains("-n 'com.tencent.mm/com.tencent.mm.ui.LauncherUI'"))
        assertTrue(command.contains("-f 0x10000000"))
        assertTrue(command.contains("--es 'LauncherUI.Shortcut.LaunchType' 'launch_type_scan_qrcode'"))
    }

    @Test
    fun buildLaunchCommand_prefersLastIntentWhenShortcutCarriesSeveral() {
        // ShortcutInfo.getIntent() 返回 mIntents 的最后一个（前面的只负责堆栈回退），
        // 所以启动目标应取末项。美团「扫一扫」的首项只是跳主界面的兜底项，取首项会退化成「打开 App 主页」。
        val command = ShortcutPickerSupport.buildLaunchCommand(
            "  [Intent { act=android.intent.action.VIEW flg=0x1000c000 cmp=com.sankuai.meituan/com.meituan.android.pt.homepage.activity.MainActivity }/PersistableBundle[{shortcuts=true}], Intent { act=android.intent.action.VIEW dat=imeituan://www.meituan.com/... }/null]"
        )

        requireNotNull(command)
        // 取末项：带上末项的数据
        assertTrue(command.contains("-d 'imeituan://www.meituan.com/...'"))
        // 首项独有的兜底 extras / 组件 / flag 都不能出现——出现了就说明退回了取首项
        assertFalse(command.contains("shortcuts"))
        assertFalse(command.contains("MainActivity"))
        assertFalse(command.contains("-f 0x1000c000"))
    }

    @Test
    fun buildLaunchCommand_keepsPackageWhenOnlyActionAndPackagePresent() {
        // 小米「垃圾清理」这类快捷方式只有 act + pkg、没有 cmp，丢掉 pkg 就没有任何定位信息
        val command = ShortcutPickerSupport.buildLaunchCommand(
            "[Intent { act=miui.intent.action.GARBAGE_CLEANUP pkg=com.miui.cleanmaster }/null]"
        )

        requireNotNull(command)
        assertTrue(command.contains("-a 'miui.intent.action.GARBAGE_CLEANUP'"))
        assertTrue(command.contains("-p 'com.miui.cleanmaster'"))
    }

    @Test
    fun buildLaunchCommand_keepsBothPackageAndComponent() {
        // pkg 与 cmp 并存时两者都要保留（与 dump 原文顺序一致）
        val command = ShortcutPickerSupport.buildLaunchCommand(
            "[Intent { act=com.android.mail.intent.action.LAUNCH_COMPOSE flg=0x1000c000 pkg=com.google.android.gm cmp=com.google.android.gm/.ComposeActivityGmailExternal }/PersistableBundle[{android.intent.extra.shortcut.ID=a@b.com}]]"
        )

        requireNotNull(command)
        assertTrue(command.contains("-p 'com.google.android.gm'"))
        assertTrue(command.contains("-n 'com.google.android.gm/.ComposeActivityGmailExternal'"))
    }

    @Test
    fun readData_splitsKeyValuePairs() {
        val data = ShortcutPickerSupport.readData("act=test.action cmp=com.test/.Main flg=0x10000000")
        assertEquals("test.action", data["act"])
        assertEquals("com.test/.Main", data["cmp"])
        assertEquals("0x10000000", data["flg"])
    }
}
