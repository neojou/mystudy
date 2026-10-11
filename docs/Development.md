# 開發

執行、測試，以及名稱與版號怎麼改。系統分層見 `docs/Architecture.md`。

## 工具鏈

用專案裡的 Gradle wrapper。Desktop target 的 Kotlin JVM toolchain 是 25，寫在 `composeApp/build.gradle.kts`。產品版號與 Kotlin、Compose、Gradle wrapper 的版號無關。

## 執行

```bash
./gradlew :composeApp:run
```

`run` 的工作目錄是專案根。Ask、Extract、Draft 會從這個目錄往上找到 `ai-prompts/`，所以改 prompt 檔後，下一次呼叫就用新內容，不用重新編譯。

## 測試

```bash
./gradlew :composeApp:desktopTest
```

行為契約在 `composeApp/src/desktopTest/kotlin/com/neojou/mystudy/wiki/WikiBehaviorTest.kt`。改檢索、送出的 prompt 形狀、ingest 的寫入守衛時，測試一起改。文件與測試不一致時，以測試與目前程式為準，再把文件改到一致。

Ask 的測試鎖了哪些句子，見 `docs/programming/How-To-do-wiki-Ask.md`。Ingest 的測試鎖了哪些路徑，見 `docs/programming/How-To-do-Ingest.md`。

## 打包時的 prompt

`prepareAppResources` 會把 `ai-prompts/` 複製進 Compose 的 app resources。打包後的 app 用的是建置當下那一份。從專案目錄執行時，往上找到的專案目錄那份優先。

## 名稱與版號

顯示名稱、Gradle 專案名、套件名一起改：

```bash
./configure.sh proj_name
```

步驟與三個名稱的差別在 `docs/programming/How-To-Change_Project_Name.md`。

產品版號：

```bash
./configure.sh version 0.2
```

`gradle.properties` 的 `app.version` 與 `AppVersion.NAME` 必須相同。步驟在 `docs/programming/How-To-Change_Version_number.md`。不要只改其中一個檔。
