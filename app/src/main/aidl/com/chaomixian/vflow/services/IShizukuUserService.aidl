// 文件路径: src/main/aidl/com/chaomixian/vflow/services/IShizukuUserService.aidl
package com.chaomixian.vflow.services;

import android.os.Bundle;
import android.view.Surface;

/**
 * 定义了 Shizuku Shell 服务的接口。
 * 使用与 vClick 完全一致的、带显式事务码的旧式定义，以确保最大兼容性。
 */
interface IShizukuUserService {
    /**
     * 销毁服务 - Shizuku 要求的标准方法。
     * 事务码 16777114 即 IBinder.LAST_CALL_TRANSACTION，这是 Shizuku 识别它的关键。
     */
    void destroy() = 16777114;

    /**
     * 执行 shell 命令。
     */
    String exec(String command) = 1;

    /**
     * 执行 shell 命令并返回结构化结果。
     */
    Bundle execWithResult(String command) = 7;

    /**
     * 退出服务。
     */
    void exit() = 2;

    /**
     * 启动守护任务来监控并保活指定的服务。
     */
    void startWatcher(String packageName, String serviceName) = 3;

    /**
     * 停止守护任务。
     */
    void stopWatcher() = 4;

    /**
     * 创建一个虚拟屏幕。
     * @return 返回虚拟屏幕的 displayId，如果失败则返回 -1。
     */
    int createVirtualDisplay(in Surface surface, int width, int height, int dpi) = 5;

    /**
     * 销毁一个虚拟屏幕。
     */
    void destroyVirtualDisplay(int displayId) = 6;

    /**
     * 设置某块屏幕的输入法显示策略（fork 新增）。
     *
     * 取值同 `WindowManager.DISPLAY_IME_POLICY_*`：
     *  0 = LOCAL（输入法显示在该屏）/ 1 = FALLBACK_DISPLAY（回落主屏，系统默认）/ 2 = HIDE。
     *
     * ⚠️ 底层是 `IWindowManager.setDisplayImePolicy`，需要 `INTERNAL_SYSTEM_WINDOW`
     * （`com.android.shell` 持有），且目标屏必须是 **trusted** 的虚拟屏，否则抛 SecurityException。
     *
     * @return 是否成功。失败一律返回 false，不抛异常（避免打挂调用方）。
     */
    boolean setDisplayImePolicy(int displayId, int policy) = 8;
}
