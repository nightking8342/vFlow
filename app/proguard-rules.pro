# Add project specific ProGuard rules here.
# You can control the set of applied configuration files using the
# proguardFiles setting in build.gradle.
#
# For more details, see
#   http://developer.android.com/guide/developing/tools/proguard.html

# If your project uses WebView with JS, uncomment the following
# and specify the fully qualified class name to the JavaScript interface
# class:
#-keepclassmembers class fqcn.of.javascript.interface.for.webview {
#   public *;
#}

# Uncomment this to preserve the line number information for
# debugging stack traces.
#-keepattributes SourceFile,LineNumberTable

# If you keep the line number information, uncomment this to
# hide the original source file name.
#-renamesourcefileattribute SourceFile

# ===========================
# vFlow 保留规则
# ===========================

# 1. 保留所有数据模型 (Data Classes)
-keep class com.chaomixian.vflow.core.workflow.model.** { *; }
-keep class com.chaomixian.vflow.core.module.InputDefinition { *; }
-keep class com.chaomixian.vflow.core.module.OutputDefinition { *; }
-keep class com.chaomixian.vflow.core.module.ActionMetadata { *; }
-keep class com.chaomixian.vflow.core.module.BlockBehavior { *; }

# 2. 保留所有模块实现类 (Modules)
-keep class com.chaomixian.vflow.core.workflow.module.** { *; }
-keep class com.chaomixian.vflow.core.module.** { *; }

# 3. 保留触发器处理器 (Trigger Handlers)
-keep class com.chaomixian.vflow.core.workflow.module.triggers.handlers.** { *; }
-keep interface com.chaomixian.vflow.core.workflow.module.triggers.handlers.ITriggerHandler { *; }

# 4. 保留自定义变量类型 (VObject 及子类)
-keep class com.chaomixian.vflow.core.types.** { *; }
-keep class com.chaomixian.vflow.core.module.*Variable { *; }
-keep class com.chaomixian.vflow.core.workflow.module.notification.NotificationObject { *; }

# 5. 保留 Gson 相关
-keepattributes Signature
-keepattributes *Annotation*
-keep class com.google.gson.** { *; }

# 6. 保留 Parcelable 实现 (用于 Intent 传递)
-keep class * implements android.os.Parcelable {
  public static final android.os.Parcelable$Creator *;
}

# 7. 保留 Lua 相关
-keep class org.luaj.vm2.** { *; }

# 7.1 保留 Rhino JavaScript 引擎
-keep class org.mozilla.javascript.** { *; }
-keep class org.mozilla.javascript.regexp.** { *; }
-keepattributes *Annotation*
-dontwarn org.mozilla.javascript.**

# 8. 忽略 XR/javax 警告
-dontwarn javax.script.**
-dontwarn org.luaj.vm2.script.**
-dontwarn com.android.extensions.xr.**
-dontwarn com.google.androidxr.**
-dontwarn org.apache.bcel.classfile.**
-dontwarn org.apache.bcel.generic.**

# 9. 保留数据仓库模型 (Repository & Update)
-keep class com.chaomixian.vflow.data.repository.model.** { *; }
-keep class com.chaomixian.vflow.data.update.** { *; }
-keep class com.chaomixian.vflow.data.repository.api.** { *; }

# 9.1 保留 API 协议模型与认证持久化模型
-keep class com.chaomixian.vflow.api.model.** { *; }
-keep class com.chaomixian.vflow.api.auth.** { *; }

# 9.2 保留日志与轻量本地持久化 DTO
-keep class com.chaomixian.vflow.core.logging.** { *; }
-keep class com.chaomixian.vflow.core.module.RecentModulesManager$RecentModuleRecord { *; }

# 9.3 保留定义在 handler 文件中的请求 DTO
-keep class com.chaomixian.vflow.api.handler.BatchExportRequest { *; }
-keep class com.chaomixian.vflow.api.handler.ImportWorkflowDataRequest { *; }
-keep class com.chaomixian.vflow.api.handler.WorkflowImportData { *; }

# 10. 保留 Shizuku & AIDL 相关
-keep class com.chaomixian.vflow.services.IShizukuUserService { *; }
-keep class com.chaomixian.vflow.services.IShizukuUserService$Stub { *; }
-keep class com.chaomixian.vflow.services.ShizukuUserService { *; }
# 保留 Shizuku 官方库的 provider
-keep class dev.rikka.shizuku.** { *; }

# 11. 保留 ML Kit (文字识别)
-keep class com.google.mlkit.** { *; }
-keep class com.google.android.gms.tasks.** { *; }

# 12. 保留 Kotlin Coroutines (协程) 内部组件
-keepnames class kotlinx.coroutines.internal.MainDispatcherFactory {}
-keepnames class kotlinx.coroutines.CoroutineExceptionHandler {}
-keep class kotlinx.coroutines.android.AndroidExceptionPreHandler {}
-keep class kotlinx.coroutines.android.AndroidDispatcherFactory {}

# 13. 保留 Coil (图片加载)
-keep class coil.** { *; }

# 14. 保留 OkHttp 相关
-keepattributes *Annotation*
-keep class okhttp3.** { *; }
-keep interface okhttp3.** { *; }
-dontwarn okhttp3.**
-dontwarn okio.**

# 15. 保留基本属性和调试信息
# LineNumberTable: 确保崩溃日志（LogManager）能显示行号，方便排查问题
# SourceFile: 保留源文件名
# EnclosingMethod, InnerClasses: 防止内部类/匿名类在反射时找不到宿主
-keepattributes SourceFile,LineNumberTable
-keepattributes EnclosingMethod,InnerClasses,MemberClasses

# 16. 保留 Kotlin 元数据
-keep class kotlin.Metadata { *; }
-keepclassmembers class com.chaomixian.vflow.** {
    @kotlin.Metadata *;
}

# 17. 保留注解属性
-keepattributes *Annotation*
-keepattributes RuntimeVisibleAnnotations
-keepattributes RuntimeVisibleParameterAnnotations
-keepattributes AnnotationDefault

# 18. 通用枚举保护
-keepclassmembers enum * {
    public static **[] values();
    public static ** valueOf(java.lang.String);
}

# 19. Android 组件与 View 的构造函数
-keepclassmembers class * extends android.view.View {
   public <init>(android.content.Context);
   public <init>(android.content.Context, android.util.AttributeSet);
   public <init>(android.content.Context, android.util.AttributeSet, int);
}
# 确保 Fragment 的无参构造函数存在（系统恢复 Fragment 时需要）
-keepclassmembers class * extends androidx.fragment.app.Fragment {
   public <init>();
}

# 20. 针对 Material Design 组件的反射
-keep class com.google.android.material.** { *; }
-dontwarn com.google.android.material.**

# 21. 针对 ViewBinding
-keep class androidx.databinding.** { *; }
-keepclassmembers class * extends androidx.viewbinding.ViewBinding {
    public static ** bind(android.view.View);
    public static ** inflate(android.view.LayoutInflater);
    public static ** inflate(android.view.LayoutInflater, android.view.ViewGroup, boolean);
}

# 22. 确保自定义控件不被混淆
-keep class com.chaomixian.vflow.ui.workflow_editor.RichTextView { *; }
-keep class com.chaomixian.vflow.ui.overlay.** { *; }

# 23. 确保 JS/Lua 脚本引擎的底层兼容
-dontwarn java.awt.**
-dontwarn java.beans.**

# 24. 确保 Shizuku 的 Binder 接口方法不被改名
-keepclassmembers class * extends android.os.Binder {
    <methods>;
}

# 25. OpenCV native library
-keep class org.opencv.** { *; }
-keepclassmembers class org.opencv.** { *; }
-dontwarn org.opencv.**

# 26. PP-OCRv5 JNI / Gson bridge
# PpOcrV5Native is called by static JNI symbol names, and Gson fills the
# PpOcrV5NativeResponse data classes by field name.
-keep class com.chaomixian.vflow.ocr.** { *; }
-keepclasseswithmembernames class * {
    native <methods>;
}

# 27. Sherpa-ncnn JNI 通过固定字段名读取 Kotlin 配置对象
# release 混淆改名后会在 GetObjectField 时出现 fid == null
-keep class com.k2fsa.sherpa.ncnn.** { *; }

# 28. ONNX Runtime JNI 会按固定签名反射/构造 Java 类型
# release 混淆 ai.onnxruntime 包后会在 NodeInfo/ValueInfo 等桥接类型上触发 NoSuchMethodError
-keep class ai.onnxruntime.** { *; }
-keep interface ai.onnxruntime.** { *; }

# 29. Netty / Reactor / Lettuce 可选依赖 (Android 不需要)
-dontwarn io.netty.util.internal.logging.Log4J**
-dontwarn io.netty.util.internal.logging.Log4J2**
-dontwarn io.netty.util.internal.Hidden$NettyBlockHoundIntegration
-dontwarn reactor.util.context.ReactorContextAccessor
-dontwarn io.micrometer.context.**
-dontwarn io.lettuce.core.support.LettuceCdiExtension
-dontwarn javax.enterprise.inject.spi.**
-dontwarn reactor.blockhound.integration.**
-dontwarn org.apache.log4j.**
-dontwarn org.apache.logging.log4j.**

# 30. Umeng analytics / ASMS
-keepclassmembers class * {
   public <init>(org.json.JSONObject);
}
-keep public class com.chaomixian.vflow.R$* {
    public static final int *;
}
-keep class com.umeng.** { *; }
-keep interface com.umeng.** { *; }
-dontwarn com.umeng.**

# ═══ 31. Xposed 通道（fork 新增）═══
# 设计文档：docs/fork/xposed-channel-design.md §4.4 / §4.4.1。
#
# ⚠️ 本段的三条规则**都是「静默失效」的防线**，写错的表现一律是
#    「模块装了、LSPosed 勾了、重启了，但什么都没发生、零报错」。

# ① ⚠️⚠️ 最易漏的一条：`XposedProvider` 只被【合并后的 manifest 字符串】引用，
#    代码里**零引用** ⇒ R8 默认会把它当死代码剥掉 ⇒ 框架加载不到 provider
#    ⇒ 模块静默不加载。探针（未混淆）踩不到这条，只有 release 才暴露。
#    注意 `io.github.libxposed:service` 自带的 proguard.txt 只有一条
#    `-dontwarn io.github.libxposed.annotation.**`，**不含任何 keep**。
-keep class io.github.libxposed.service.** { *; }

# ② 入口类由 APK 根 `META-INF/xposed/java_init.list` 按**类名字符串**加载，
#    被混淆 = 模块完全不生效。keepnames 保证类名不被重命名。
-keep class com.chaomixian.vflow.xposed.VFlowHookEntry { *; }
-keepnames class com.chaomixian.vflow.xposed.VFlowHookEntry

# ③ `io.github.libxposed:api` 是 compileOnly（运行期由框架提供），
#    R8 在 release 阶段看不到它 ⇒ 会报 missing class。
-dontwarn io.github.libxposed.api.**

# ④ AIDL 接口（P1b）。跨进程按**接口名 / 方法名**协商，
#    混淆掉的表现是 binder 事务找不到方法（静默失败，不报错）。
#    形态照第 10 节 Shizuku & AIDL 的写法：接口 + $Stub + 实现类分别 keep。
-keep class com.chaomixian.vflow.xposed.IHookHost { *; }
-keep class com.chaomixian.vflow.xposed.IHookHost$Stub { *; }
-keep class com.chaomixian.vflow.xposed.IHookCallback { *; }
-keep class com.chaomixian.vflow.xposed.IHookCallback$Stub { *; }
-keep class com.chaomixian.vflow.services.HookChannelService { *; }
# ⑤ hook 层其余类：它们被入口类引用，但入口类若被 keep 而它们被混淆，
#    内部调用仍成立（同一次混淆），故**不逐个 keep** —— 只有「按名字被外部
#    找到的」才需要 keep（入口类、AIDL、provider）。
#    ⚠️ 但 wire 层被两端共享，且**不允许**被 inline 成 App 侧依赖，故保留类名。
-keepnames class com.chaomixian.vflow.xposed.wire.**
