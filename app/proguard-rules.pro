# 保留 Room 生成的实现与序列化器
-keep class * extends androidx.room.RoomDatabase
-keepclassmembers class * { @androidx.room.* <fields>; }
