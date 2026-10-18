# B570 Pilot release（R8 full mode + shrinkResources）
# - 協定與資料類別沒有反射序列化（手寫位元組編解碼、org.json），預設規則即可。
# - CameraX、ML Kit barcode 都自帶 consumer rules（AAR 內 proguard.txt），不需另加。

# 設定存檔用 enum.name 保存（JoystickMode / Gear / AccentChoice）：保留名稱，避免改版後讀不回設定
-keepclassmembers enum com.b570.pilot.data.** {
    <fields>;
    public static **[] values();
    public static ** valueOf(java.lang.String);
}

# 當機堆疊仍可對應行號
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile
