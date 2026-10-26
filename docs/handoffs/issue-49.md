# Issue #49 確定仕様 — TODAYパネルに音量スライダ＋出力先切替

## 構造（A設計レビュー確定）

- `system/MediaVolumeAccess.kt`（新規、TorchController準拠の薄いラッパー）:
  - `current(): Int` / `max(): Int` / `set(index: Int)`（内部でSTREAM_MUSIC固定、setStreamVolume flags=0）
  - `observe(onChanged: () -> Unit): () -> Unit` — `VOLUME_CHANGED_ACTION` receiver登録/解除（`EXTRA_VOLUME_STREAM_TYPE` で MUSIC フィルタ、受信時は `current()` 再読）
  - テストseam: `internal var streamVolumeReader` / `streamMaxReader` / `streamVolumeWriter` / `resetSeams()`
- 音量状態は専用 `StateFlow<VolumeUi(volume, max)>` をVMが持つ（TodayUiには入れない）
- **receiver登録はTODAYパネルopen中のみ**（overlay駆動: `eventsObserverUnregister` と同型。VMのoverlay状態flowをcollectしてopen時observe→close時unregister。onClearedでも解除）
- 表示時・foreground時も再読（broadcast非発火端末の保険）

## UI

- TodayPanel 最下部に「音量」ブロック常時表示（PanelDividerは前ブロックの有無に関わらず自然に）
- M3 `Slider`（`steps = max - 1`、M3がProgressBar semantics/SetProgressを自動付与）+ `defaultMinSize(48dp)`
- 「出力先」行 `clickable(role=Role.Button)` + `onClickLabel`「出力先を変更」
- 出力先行タップ → VM → `nav.beginExternalFlow()` + MainActivity `openMediaOutputPanel(intent=Intent(Settings.Panel.ACTION_MEDIA_OUTPUT), fallbackIntent=Intent(Settings.ACTION_SOUND_SETTINGS))` を `launchExternalOrToast` 経由（arm+rollbackが正しく効く）
- 新規strings: 「音量」「出力先」、onClickLabel用、失敗Toast用

## テスト（B計画要約）

- L1: fraction↔index変換の純粋関数（clamp・往復・max=0ガード）、seam経由set（stream=MUSIC,flags=0捕捉）
- L2: seam駆動でスライダSetProgress→writerへindex、broadcast駆動→StateFlow更新、パネルopen毎にreader再読、最小TodayUiでも音量ブロック存在、出力先行→`ACTION_MEDIA_OUTPUT`のaction厳密一致（blocking monitor）+arm維持、bogus intent→rollback→Quiet、onClickLabel検証
- L3: emulatorでスライダ→`media volume --stream 3`実変化、KEYCODE_VOLUMEで追従、出力パネル実表示
- L4: 実機（pending-device-checks追記）

## 実装メモ（確定仕様からの解釈・補足）

- `Settings.Panel.ACTION_MEDIA_OUTPUT`・`AudioManager.VOLUME_CHANGED_ACTION`・
  `EXTRA_VOLUME_STREAM_TYPE` はいずれも非SDK（@SystemApi/hidden）定数のため、
  安定したアクション文字列リテラル（`com.android.settings.panel.action.MEDIA_OUTPUT`、
  `android.media.VOLUME_CHANGED_ACTION` 等）を使用。
- `VOLUME_CHANGED_ACTION` はprotected broadcastでアプリ・shellとも送信不可。
  そのため `MediaVolumeAccess.volumeReceiver`（BroadcastReceiver本体）をinternal公開し、
  L2は実Intentでreceiverを直接駆動（TorchController.torchCallbackと同型）。
  実broadcastの配送自体は `realStreamChangeRepaintsTheSliderViaTheOsBroadcast`
  （実 `setStreamVolume` → OS broadcast → StateFlow更新）で検証。
- seamのwriterは `(streamType, index, flags)` 捕捉とし、stream=MUSIC・flags=0を
  L2の実スライダ駆動で検証（L1はJVMでContextを生成できないため純粋関数のみ）。
- M3 SliderのSetProgressはstepsにスナップする（0.5×15=7.5→7へ丸められたため、
  厳密検証には0.6→9のようなジャストステップ値を使用）。
- `HomeNavigation.externalFlowArmed`（internal probe）を追加し、UI遷移に依存せず
  arm状態を直接検証可能にした（arm維持・rollback・1回消費の全ケースで使用）。

## 検証結果（head: 実装完了commit、emulator-5556 API36）

| Test ID | 検証 | 結果 |
|---|---|---|
| I49-L1 | `system/VolumeScaleTest` 6件: 線形変換・clamp(両方向)・往復0..max一致・max<=0で0ガード | PASS |
| I49-L2a | `VolumeIntegrationTest.sliderSetProgressWritesTheStreamIndex`: SetProgress(0.6)→writerに(MUSIC,9,flags=0)、writer後の再読でStateFlow更新 | PASS |
| I49-L2b | `volumeBroadcastWhileOpenRefreshesTheState`: MUSIC broadcast駆動→volume=11、STREAM_RINGはフィルタで無視 | PASS |
| I49-L2c | `receiverIsOpenScopedAndEachOpenRereads`: close後のbroadcast無効化＋再open毎にreader再読 | PASS |
| I49-L2d | `TodayPanelStatesTest.volumeBlockIsPresentEvenForMinimalToday`: 最小TodayUiで「音量」見出し＋SetProgressノード＋「出力先」行 | PASS |
| I49-L2e | `outputRowTapArmsTheExternalFlow`＋`ExternalFlowLifecycleTest.mediaOutputRowOpensOsPanelAndKeepsTheArm`: 行タップ→`com.android.settings.panel.action.MEDIA_OUTPUT`厳密一致（blocking monitor）・arm維持・TODAY残留 | PASS |
| I49-L2f | `unresolvedMediaOutputLaunchRollsBackArmedFlow`/`unresolvedExternalLaunchesRollBackArmedFlow`（bogus intent追記）＋`dispatchedSoundSettingsFallbackKeepsTheArmedFlow`（fallback dispatchでarm維持） | PASS |
| I49-L2g | `TodayPanelStatesTest.outputRowExposesButtonRoleAndClickLabel`: マージ行がRole.Button＋onClickLabel「出力先を変更」 | PASS |
| I49-L3 | `sliderSetProgressChangesTheRealStreamVolume`: seam無しでSetProgress→実STREAM_MUSIC変化＋復元。`realStreamChangeRepaintsTheSliderViaTheOsBroadcast`: 外部`setStreamVolume`（ハードキー相当）→実broadcastで追従 | PASS（emulator実施） |
| build/lint | `:app:testDebugUnitTest` 全PASS（VolumeScaleTest含む）、`:app:lintDebug` エラー0、`:app:connectedDebugAndroidTest` 195件全PASS | PASS |

証跡: `docs/handoffs/pr<PR番号>/evidence/`（unit XML群・instrumentation-results.xml・lint結果）

既知の非本件flake: `WeatherServiceTest`（実時間依存のタイミングテスト）が
同一suite内で断続的にFAILすることがある。本変更とは無関係（当該領域に非接触）。
