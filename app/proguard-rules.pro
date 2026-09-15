# 保留 Room 生成的实现与序列化器
-keep class * extends androidx.room.RoomDatabase
-keepclassmembers class * { @androidx.room.* <fields>; }

# LSPosed 入口（libxposed API 102）：框架按 META-INF/xposed/java_init.list 中的
# 全限定类名反射实例化，入口类与 Hooker 实现不可被混淆/移除
-keep class cc.ytdttj.noticleaner.keepalive.LspEntry { *; }
-keep class cc.ytdttj.noticleaner.keepalive.LspEntry$* { *; }
