# X → GIF（Android 原始碼專案）

繁體中文 Android App：貼上公開 X／Twitter 貼文連結 → 擷取影片 → 手機端轉成 GIF → 保存至使用者選擇的資料夾。

**交付狀態：這是原始碼專案，不是可直接安裝的 APK。尚未完成 Android 編譯與真機驗證。** 本環境沒有 Android SDK，Gradle 建置嘗試亦因無法連線下載發行套件而中止。不能據此保證所有裝置或 X 貼文可用。

## 功能

- 貼上連結，或在 X 點「分享」將文字連結分享給 X → GIF。
- 接受 x.com／twitter.com，將 `/photo/1`、`/video/1` 及查詢參數正規化為貼文網址。
- 使用 Android 系統資料夾選擇器，記住保存位置及授權。不要求「所有檔案存取」權限。
- 320／480／720 像素寬，10／15／24 FPS，保持影片比例且不放大小影片。
- 起始秒數、1–60 秒片段長度。預設為前 10 秒、480 像素、15 FPS；不是自動轉換整段長影片。
- 多影片貼文可選第 1–4 段影片；這是擷取器列出的影片順序，不一定等同混合圖片貼文的媒體編號。網址尾部 `/photo/1` 本身不能證明內容是影片。
- 前景服務處理下載／轉換，離開畫面後仍可繼續；通知顯示處理中，詳細狀態及結果在 App。系統強制終止、強制停止或重新開機後不會續傳。
- 轉換完成後可開啟 GIF。失敗時嘗試刪除不完整的輸出檔，完成後刪除暫存影片。
- 可按「更新擷取引擎」更新 yt-dlp，以應對 X 網站變更。更新需要連線 GitHub／上游發布服務。

## 產生 APK：Android Studio

1. 解壓縮 ZIP，以 Android Studio 開啟內層 `XGif` 資料夾（包含 `settings.gradle.kts`）。
2. 使用 JDK 17，安裝 Android SDK Platform 35，允許 Gradle 同步及下載依賴。
3. 用 Build 選單的 Generate/Build APK 功能，或在專案終端執行：

```sh
./gradlew testDebugUnitTest assembleDebug
```

Windows：

```bat
gradlew.bat testDebugUnitTest assembleDebug
```

4. 輸出位置：`app/build/outputs/apk/debug/app-debug.apk`。
5. 將 APK 複製到 Android 手機，依系統提示允許該檔案管理器安裝未知來源 App，再安裝。開發版 APK 只適合測試；正式發布應使用自己的簽署金鑰。

已包含 Gradle Wrapper。建置電腦需可連線 Google Maven、Maven Central、Gradle 套件站。

## 沒有 Android Studio：GitHub Actions

1. 在自己的 GitHub repository 根目錄放入 `XGif` 資料夾內的所有內容，包括隱藏的 `.github` 資料夾；不要多包一層 XGif。
2. 開啟 Actions → Build APK → Run workflow。
3. 成功後下載該次執行的 `XGif-debug-apk` artifact，解壓縮取得 APK。

本次沒有替你建立 repository、上傳 GitHub 或執行 workflow；提供的是可自行執行的設定檔。

## 使用

1. 安裝後開啟 App。
2. 按「選擇保存資料夾」，例如在內部儲存空間 Download 下建立 `XGif`，再按系統的「使用這個資料夾」。
3. 貼上 X 影片貼文連結，調整片段與畫質，按「下載並轉換 GIF」。
4. 等待 App 顯示「已保存」，從 App 或檔案管理器開啟 GIF。

Android 11 之後，系統對根目錄、Download 根目錄、Android/data 等位置有選取限制；請使用自行建立的子資料夾。若選擇雲端檔案供應商，保存位置可能是雲端；要存在手機請選「內部儲存空間」或 SD 卡。

GIF 沒有聲音。高幀率、大尺寸或長片段會大幅增加檔案大小與轉換時間。程式先下載來源影片再裁切轉換，短 GIF 並不代表只下載短片段；來源設定 200 MB 上限，但無法保證所有串流下載器都能預先得知檔案大小。

## 支援範圍與限制

- 最低 Android 8.0（API 26）；打包 arm64-v8a、armeabi-v7a、x86_64 原生依賴。
- X 公開可存取影片，無自建伺服器、不需自行填入 X API key。
- 需要登入、私人、地區限制、已刪除或純圖片貼文不保證可用。未實作帳戶登入或 cookies 匯入。
- 不繞過 DRM 或存取限制。僅下載你有權保存／轉換的內容。
- X 修改介面或限制流量時，可能需更新擷取引擎；並非任何 X 連結都保證成功。
- 使用者參考網址 `https://x.com/Insuelin/status/2112074026441199770/photo/1` 已納入網址正規化測試，但此環境無法讀取該貼文，未證實它能下載。
- 未驗證 16 KB page-size 裝置上的第三方原生套件相容性；發布前需真機測試。
- 系統強制終止時留下的暫存工作目錄會在下次工作開始前清理。

## 技術

Kotlin + Android Views；前景 dataSync Service；Storage Access Framework + DocumentFile；yt-dlp Android wrapper 0.18.1 + 同版 FFmpeg；GIF 使用 palettegen/paletteuse 調色盤及無限循環。網路下載、轉檔與檔案複製均在 IO 執行緒。

專案版本固定於 build.gradle.kts：AGP 8.9.2、Kotlin 2.1.20、Gradle 8.11.1、Java 17、compile/target SDK 35。

## 驗證狀態

已完成：

- 使用本機 FFmpeg 對合成 640×360／30 FPS 測試影片執行相同轉換濾鏡與映射參數。
- ffprobe 驗證輸出：480×270、15 幀、時長 1 秒；GIF 可解析。
- 檢查 Gradle Wrapper JAR、專案 XML、ZIP 結構。

未完成：

- `testDebugUnitTest` 與 `assembleDebug`：尚未執行到編譯階段，Gradle 發行套件下載因 Network is unreachable 失敗。
- 真機 UI、通知、背景服務、資料夾授權、X 影片下載及 Android 原生 FFmpeg 執行。
- `XLinkTest.kt` 已提供網址正規化／偽冒網域拒絕等測試，需在正常 Android 建置環境執行。

真機驗收建議：公開短影片轉換；分享入口；旋轉畫面；切到背景；重開 App 後保存；資料夾撤銷授權；無影片與登入限制貼文；斷網；起始秒數超過影片；剩餘空間不足。

## 依賴來源與授權

- youtubedl-android（GPL-3.0）：https://github.com/yausername/youtubedl-android
- yt-dlp：https://github.com/yt-dlp/yt-dlp
- FFmpeg：https://ffmpeg.org/
- Android SAF：https://developer.android.com/training/data-storage/shared/documents-files
- Gradle Wrapper：https://github.com/gradle/gradle/tree/v8.11.1（Apache-2.0）

本專案原始碼以 GPL-3.0-or-later 提供，見 LICENSE；第三方元件依各自授權。對外散布二進位檔前，應保留元件授權並提供所需的對應原始碼及建置資訊。完整上游授權應以所下載套件為準。
