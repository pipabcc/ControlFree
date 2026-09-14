# ControlFree R8 规则
#
# 项目未使用 kotlinx.serialization / Gson / 反射加载 app 自身类：
# - Room 通过 KSP 生成代码，无需额外 keep；
# - Compose 对 R8 有官方默认规则；
# - 唯一的反射目标是框架隐藏 API（AppOpsManager.checkOpNoThrow），R8 不会裁剪 framework 类。

# 所有实体/DTO 的枚举经 storedValue 字符串解析，R8 默认保留 values()/valueOf()，
# 但显式保留枚举的 valueOf 以防混淆后的 storedValue 映射被优化。
-keepclassmembers enum com.example.controlfree.** {
    public static **[] values();
    public static ** valueOf(java.lang.String);
}

# 诊断报告会把未捕获异常的堆栈发给用户/日志，保留行号便于还原混淆堆栈。
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile
