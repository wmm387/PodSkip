# Activity / Service 都在 Manifest 里登记，R8 会自动保留，这里只做几处兜底。

# 保留行号，线上崩溃栈还能定位到具体行
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile

# 无障碍服务由系统实例化，避免被裁掉
-keep class com.zero.podskip.core.SwipeKeyService { *; }

# AppCompat / Material 内部有反射读取属性，AGP 已带它们自己的 consumer rules，
# 这里保守兜底，防止后续升级依赖后漏规则
-keep class androidx.appcompat.widget.** { *; }
-dontwarn androidx.**
