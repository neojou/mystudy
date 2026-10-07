# 如何更改版號

產品版號是使用者在 About 裡看到的版本。它和 Kotlin、Compose、Gradle wrapper 的版號無關。

改產品版號請用 `./configure.sh version`。不要只改其中一個檔案，否則 About 與打包版號會不一致。

## 兩個寫入點

兩處必須是同一個字串。模板的初始值是 `0.1`。

1. `gradle.properties` 的 `app.version`
2. `composeApp/src/commonMain/kotlin/com/neojou/mystudy/AppVersion.kt` 的 `NAME`

```kotlin
const val NAME: String = "0.1" // configure:app.version
```

行尾的 `configure:app.version` 是 `configure.sh` 的標記。腳本只改 Kotlin 原始碼裡這一行的字串，而且必須正好一處。上面的程式片段是說明用的範例，腳本不會把它當成第二處標記。版號不含 `v` 前綴。

`version` 指令不會改寫這份說明。改完後以 `gradle.properties` 的 `app.version` 為準。

## 哪些地方讀這個版號

- About 第二行。`HomeScreen.kt` 的 `AboutDialog` 顯示 `Version ` 加上 `AppVersion.NAME`，例如 `Version 0.1`。Top Menu 的 About 與 macOS 系統選單的 About 是同一個對話框，所以兩邊的版號相同。
- Gradle 專案版本。`composeApp/build.gradle.kts` 的 `version` 讀 `app.version`。jar 名稱會帶這個版號。
- 桌面套件版號。同一個檔案的 `nativeDistributions.packageVersion` 也讀 `app.version`。以後打成 dmg 或 pkg 時，套件版號就是這個值。macOS 選單的 About 仍走上面的對話框，不會改顯示 JDK 版號。

## 用指令改版號

```bash
./configure.sh version 0.2
```

格式是 `MAJOR`、`MAJOR.MINOR` 或 `MAJOR.MINOR.PATCH`。每一段都是非負整數，除了單獨的 `0` 以外不可有前導零。`0.2` 與 `0.2.0` 合法。`v0.2`、空白與先行號不合法。

已經是這個版號時，指令只印出現況，不改檔案。

這個格式同時符合 macOS dmg 與 pkg 的要求。Windows 的 msi 與 exe 要求正好三段，例如 `0.2.0`。以後若要打 Windows 安裝檔，請把參數寫成三段。

## 指令故意不改的版號

下列版號是工具鏈，不是產品版號：

- `gradle/libs.versions.toml` 裡的 Kotlin、Compose 與函式庫版本
- `gradle/wrapper/gradle-wrapper.properties` 的 Gradle 版本

專案顯示名稱見 `How-To-Change_Project_Name.md`。
