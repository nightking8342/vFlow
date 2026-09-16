package com.chaomixian.vflow.services

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [executionNotificationIdFor] 的回归测试。
 *
 * 背景：改造前所有工作流共用固定通知 ID 1998，导致并发执行的多个工作流互相覆盖、
 * 且任一工作流结束时的取消会误删仍在运行的其它工作流的通知。改为按工作流派生 ID。
 */
class ExecutionNotificationIdTest {

    /** 每个工作流的 ID 必须落在 [NOTIFICATION_ID_BASE, BASE + RANGE) 区间内。 */
    @Test
    fun notificationIdFallsInsideReservedRange() {
        val workflowIds = listOf(
            "wf_1", "wf_2", "每日签到", "到家开灯", "a", "b",
            "workflow-with-a-very-long-id-0123456789",
            "emoji_🎉_workflow",
        )

        workflowIds.forEach { id ->
            val notificationId = executionNotificationIdFor(id)

            assertTrue(
                "ID '$id' 派生出 $notificationId，低于基址 $NOTIFICATION_ID_BASE",
                notificationId >= NOTIFICATION_ID_BASE
            )
            assertTrue(
                "ID '$id' 派生出 $notificationId，超出区间上界 ${NOTIFICATION_ID_BASE + NOTIFICATION_ID_RANGE}",
                notificationId < NOTIFICATION_ID_BASE + NOTIFICATION_ID_RANGE
            )
        }
    }

    /**
     * 派生出的 ID 必须避开项目现有的全部通知 ID。
     *
     * 这些是代码中各处的硬编码值：TriggerService(2)、PermissionGuardianService(1001)、
     * VoiceTriggerService(2002)、CoreManagementService(3001/3002)、ChatFloatWindowService(97010)，
     * 以及 ExecutionUIService 从 1000 起自增的交互通知区间。
     *
     * 本测试断言新 ID 全部 >= 100_000，天然高于上述所有值，也就不会冲突。
     */
    @Test
    fun notificationIdDoesNotCollideWithExistingNotificationIds() {
        val existingIds = listOf(2, 1001, 1998, 2002, 3001, 3002, 97010)
        val interactionServiceIds = (1000..1100).toList() // ExecutionUIService 的自增区间
        val reserved = existingIds + interactionServiceIds

        val workflowIds = (1..200).map { "workflow_$it" } + listOf("每日签到", "到家开灯", "签到")

        workflowIds.forEach { id ->
            val notificationId = executionNotificationIdFor(id)
            assertTrue(
                "ID '$id' 派生出 $notificationId，与既有通知 ID 冲突",
                notificationId !in reserved
            )
        }
    }

    /** 不同工作流必须派生出不同 ID，否则并发执行时仍会互相覆盖。 */
    @Test
    fun differentWorkflowsGetDifferentNotificationIds() {
        val first = executionNotificationIdFor("每日签到")
        val second = executionNotificationIdFor("到家开灯")

        assertNotEquals(
            "两个不同工作流应派生出不同通知 ID",
            first,
            second
        )
    }

    /** 同一个工作流多次调用必须稳定返回同一 ID（否则更新会变成新建通知）。 */
    @Test
    fun notificationIdIsStableForTheSameWorkflow() {
        val id = "stable_workflow_id"

        assertEquals(
            executionNotificationIdFor(id),
            executionNotificationIdFor(id)
        )
    }

    /**
     * hashCode 为负的工作流 ID 也必须落在合法区间内。
     *
     * 若不取绝对值，负 hashCode 取模后仍为负数，ID 会落到 100_000 以下，可能撞号。
     */
    @Test
    fun negativeHashCodeStillProducesIdInRange() {
        val idWithNegativeHash = generateSequence(0) { it + 1 }
            .map { "wf_$it" }
            .first { it.hashCode() < 0 }

        val notificationId = executionNotificationIdFor(idWithNegativeHash)

        assertTrue(
            "hashCode 为负的 ID '$idWithNegativeHash' (hash=${idWithNegativeHash.hashCode()}) " +
                "派生出 $notificationId，未落在合法区间",
            notificationId >= NOTIFICATION_ID_BASE &&
                notificationId < NOTIFICATION_ID_BASE + NOTIFICATION_ID_RANGE
        )
    }

    /** 空字符串不应导致崩溃或落到区间外。 */
    @Test
    fun emptyWorkflowIdIsHandled() {
        val notificationId = executionNotificationIdFor("")

        assertTrue(
            notificationId >= NOTIFICATION_ID_BASE &&
                notificationId < NOTIFICATION_ID_BASE + NOTIFICATION_ID_RANGE
        )
    }
}
