# 手機監測 PhoneMonitor

背景定時記錄:電量、電池健康、溫度、電壓、電流、功率、充電循環、記憶體、儲存空間、網路、流量、熱狀態、螢幕/省電狀態。
有折線圖、可匯出 CSV、開機自動恢復。

## 使用步驟
1. GitHub 建立 **Private** 新 repo,把本資料夾所有檔案(含 `.github` 隱藏資料夾)上傳到 main 分支。
2. 進入 repo → Actions,等 "Build APK" 跑完(約 3~5 分鐘)。
3. 到 Releases 下載 `PhoneMonitor-vN.apk`,手機安裝(需允許「安裝未知應用程式」)。
4. 日後要更新:改程式碼 → push,Actions 自動出新版 APK,**直接安裝即可覆蓋**,資料保留。

## 覆蓋更新原理
- 第一次 Actions 會產生簽名金鑰存入 `keystore/release.jks`,之後永遠用同一把。**請勿刪除、repo 請保持私有。**
- `versionCode` = Actions 執行編號,每次自動遞增。
- `applicationId`(com.example.phonemonitor)一旦發布就不要改。
