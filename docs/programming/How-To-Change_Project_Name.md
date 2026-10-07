# 如何更改專案名稱

這個專案把「給人看的名稱」、「Gradle 專案名」和「Kotlin 套件名」分開存放。畫面與 macOS 選單讀的是顯示名稱。改名請用 `./configure.sh proj_name`，不要只改其中一個檔案。

## 三種名稱

| 用途 | 寫在哪裡 | 誰讀它 |
| --- | --- | --- |
| 顯示名稱 | `gradle.properties` 的 `app.displayName`，以及 `AppVersion.APP_NAME` | 視窗標題、網頁標題、主畫面、About、macOS 選單與 Dock |
| Gradle 專案名 | `gradle.properties` 的 `app.rootName` | `settings.gradle.kts` 的 `rootProject.name`、Wasm 輸出模組名、打包檔名 |
| 套件名 | `gradle.properties` 的 `app.group` | 原始碼目錄、`mainClass`、Compose resources 套件 |

目前顯示名稱是 mystudy。`com.neojou.tools` 是共用工具套件，不是產品套件，改名時不會動到它。

顯示名稱與程式裡的常數必須相同。常數在：

`composeApp/src/commonMain/kotlin/com/neojou/mystudy/AppVersion.kt`

```kotlin
const val APP_NAME: String = "mystudy" // configure:app.displayName
```

行尾的 `configure:app.displayName` 是 `configure.sh` 的標記。腳本只改 Kotlin 原始碼裡這一行的字串，而且必須正好一處。上面的程式片段是說明用的範例，腳本不會把它當成第二處標記。

## 畫面上的讀取位置

這些地方都讀 `AppVersion.APP_NAME`，不各自寫死名稱。

- 主畫面中央文字：`composeApp/src/commonMain/kotlin/com/neojou/mystudy/HomeScreen.kt`
- About 第一行：同一個檔案裡的 `AboutDialog`。Top Menu 的 About 與 macOS 系統選單的 About 共用這一個對話框。
- Desktop 視窗標題：`composeApp/src/desktopMain/kotlin/com/neojou/mystudy/Main.kt` 的 `Window(title = ...)`
- Wasm 分頁標題：`composeApp/src/wasmJsMain/kotlin/com/neojou/mystudy/WasmMain.kt` 在啟動時設定 `document.title`
- 靜態網頁標題：`composeApp/src/wasmJsMain/resources/index.html` 的 `<title>`。這一行不是 Kotlin，所以 `proj_name` 會另外改它。

## macOS 選單為什麼會顯示 MainKt 或 java

用 `./gradlew :composeApp:run` 啟動時，程式還沒包成 `.app`。若沒有另外告訴系統應用程式名稱，macOS 最上方的應用程式選單會用主類別名稱 `MainKt`。點選單裡的 About 則會打開 Java 內建面板，名稱是執行檔 `java`，版號是 JDK 的版號。

Desktop 用兩個時機寫入顯示名稱：

1. JVM 啟動參數。`composeApp/build.gradle.kts` 在 macOS 上為 `run` 加上 `-Dapple.awt.application.name` 與 `-Xdock:name`，值是 `app.displayName`。選單名稱在 JVM 啟動時就決定，所以必須放在啟動參數，不能等畫面畫出來再改。
2. `Main.kt` 進入視窗之前再設定一次 `apple.awt.application.name`，值是 `AppVersion.APP_NAME`。直接執行 jar、沒有經過 Gradle 時仍然看得到正確名稱。

打包後的選單與 Dock 用同一份顯示名稱：`composeApp/build.gradle.kts` 的 `nativeDistributions.macOS.dockName`。打包檔名用沒有空白的 `app.rootName`，因為顯示名稱可以含空白。`bundleID` 用 `app.group`。

系統選單的 About 不使用 Java 內建面板。`Main.kt` 的 `installMacAboutHandler` 在 macOS 上呼叫 `java.awt.Desktop.setAboutHandler`，把該項目接到 `AboutRequest.show()`。對話框因此與 Top Menu 的 About 是同一個，第一行是專案名稱，第二行是版號。

## 用指令改名稱

```bash
./configure.sh proj_name "Stock Viewer"
./configure.sh proj_name MyApp
```

名稱可以含空白。不可包含 `"`、`\`、`$`、`#`、`=` 或換行。

腳本會：

- 把 `app.displayName` 與 `APP_NAME` 那一行改成新名稱
- 把 `index.html` 的 `<title>` 改成新名稱
- 把說明文件裡的舊顯示名稱換成新名稱
- 若名稱含英文或數字，另外產生 Gradle 專案名與套件最後一段。例如 Stock Viewer 變成 Gradle 名稱 StockViewer、套件最後一段 stockviewer。組織前綴保留，只換最後一段，並搬移對應的原始碼目錄
- 沒有英數內容的名稱（例如純中文）只改顯示字串，Gradle 專案名與套件維持不變

這個指令不會重新命名所在的資料夾。目錄名稱請在複製模板時自己取。

Kotlin、Compose 與函式庫版號不在這裡改。產品版號見 `How-To-Change_Version_number.md`。
