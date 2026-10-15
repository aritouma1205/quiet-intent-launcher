# Issue #43 — レビューMINORまとめ: Edgeプレビュー位置・a11yラベル・requestIntent安全性・UIスレッドbinder低減

## 工程状態

- 現在工程: PR作成済み・レビュー待ち
- PR #47: OPEN、base=main、fix commit `24ee54d` + 本docsコミット群。
  本文に `Depends on PR #39` 明記（#39 が Issue #38 実装の前提）
- ベース: `feature/issue-38-app-uninstall` 先端 `8f47354`、作業ブランチ `feature/issue-43-review-minors`
- 作業ディレクトリ: `/home/aritouma/projects/qil-issue43-minors`（git worktree。親作業ツリーは不接触）

## 確定仕様（4項目）

1. **Edge設定プレビュー位置の一致** — 本番 EdgeBar の式
   `usableTopPx + bias * (usableHeightPx - effectiveLengthPx)` とプレビュー側の
   旧式 `110.dp * verticalBias - length / 2` がずれていた。
   両者で同一の pure 関数 `edgeBarEffectiveLength` / `edgeBarTopOffset`
   （`settings/EdgeBarGeometry.kt`）を使い、bias 0/0.5/1・複数高さ・clamp/coerce
   境界を L1 単体テストで固定する。
2. **a11yラベル** — カレンダー選択行は親行を `toggleable(role = Role.Checkbox)`
   とし、子のテキスト・Checkboxを1ノードにマージ（内側 Checkbox は
   `onCheckedChange=null` のまま非インタラクティブ）。検索結果行・SearchEntry・
   最近行には `onClickLabel` を付け、TalkBack が行名を操作ラベルとして読める
   ようにする。
3. **`HomeRole.requestIntent()` 安全性** — RoleManager 不在 / HOME role 利用不能 /
   OS例外の環境でもクラッシュしない。戻り値を `Intent?` にし、null/例外は
   `openDefaultHomeSettings()`（API≥33 `ACTION_HOME_SETTINGS`、<33
   `ACTION_MANAGE_DEFAULT_APPS_SETTINGS`、さらに失敗時 `ACTION_SETTINGS`）へ
   フォールバック。計装用シーム `requestRoleIntentOverride: (() -> Intent?)?`
   を設置（セット時はその値がそのまま答えになり、null ケースを確定的に作れる）。
4. **UIスレッドbinder低減** —
   - `ToolLauncher.canShowTimers()`（queryIntentActivities）を行タップ時に
     実行しない。IO経路の行生成 `resolveToolRows` で一度だけ解決し
     `ToolRow.timerListAvailable` に保持、タップは格納値を見るだけ。
     計測シーム `timersAvailableCheck` でクエリ回数を検証。
     ターゲット設定済みの Timer はターゲット起動が優先され、
     availability クエリ自体をスキップする。
   - `SystemActions.isEnabledInOs()` のジェスチャ経路に TTL 2秒キャッシュ
     （`ENABLED_CACHE_TTL_MS = 2_000`）を導入。注入可能な時計シーム
     `enabledCacheClock: () -> Long` で検証可能。
     `refreshEnabled()` と `setObserving(true)` は常に fresh read でキャッシュを
     バイパスし再アームする（setObserving(true) は観測中の再呼び出しでも
     必ず fresh read — フォアグラウンド復帰境界として機能）。OS設定変更は
     既存の ENABLED_ACCESSIBILITY_SERVICES ContentObserver → refreshEnabled
     経路で追従し、実行直前の revoke 検知（design 13）は弱めない。

## 実装サマリ

- `settings/EdgeBarGeometry.kt`（新規）: `edgeBarEffectiveLength(usableHeight,
  barLength)`（長さを usable 高さに clamp）と `edgeBarTopOffset(bias,
  usableHeight, barLength)`（bias も 0..1 に coerce）。単位中立の Float 関数で、
  本番は px、プレビューは dp の同一式を共有。
- `home/EdgeBar.kt`: 描画高さと top 位置を共通関数で計算
  （`topPx = usableTopPx + edgeBarTopOffset(...)`）。当たり判定矩形＝描画矩形の
  不変条件は維持。
- `settings/EdgeSettingsScreen.kt`: `PREVIEW_HEIGHT = 220.dp` を usable track と
  して同一関数を適用。バー描画高さも effective length に統一。
- `settings/InfoSettingsScreen.kt`: カレンダー行を `Modifier.toggleable(value,
  role = Role.Checkbox, onValueChange)` に変更。内側 Checkbox は
  `onCheckedChange = null` のまま。
- `search/SearchScreen.kt`: `SearchResultRow` に `onClickLabel = title`、
  `SearchEntry` に `onClickLabel = label` を追加。
- `home/HomeRole.kt`: `requestIntent(): Intent?` 化 + `requestRoleIntentOverride`
  シーム。`isHeld()` は既存どおり null 安全。
- `MainActivity.kt`: `requestHomeRole()` が null を受けて即 fallback。
  `roleRequest.launch` の `ActivityNotFoundException` も同一路線。
- `home/PanelItems.kt`: `ToolRow.timerListAvailable: Boolean = false` 追加。
- `home/HomeViewModel.kt`: `resolveToolRows` を `Dispatchers.IO` で実行し
  （refreshToolRows / refreshActionRows 両経路）、Timer行の availability を
  pass 内1回だけ解決。`onToolTapped` の Timer 分岐はターゲット優先、
  なければ格納 bit を参照して showTimers / NoTimerHandler+エディタ導線を分岐。
- `system/SystemActions.kt`: `ENABLED_CACHE_TTL_MS`、cache3変数、
  `enabledCacheClock`/`enabledOverride` のセッターで invalidate、
  `isEnabledInOs()` の TTL 判定、`refreshEnabled()` の fresh read＋再アーム、
  `setObserving(true)` の常時 fresh read 化。

## 検証結果

対象: `feature/issue-43-review-minors` head `24ee54d`（PR #47）、
emulator-5556（ops_emu AVD / API 36）。証跡一式は `docs/handoffs/pr47/evidence/`。

JUnit XML: `docs/handoffs/pr47/evidence/unit/TEST-*.xml`（単体316件）。
計装結果: `docs/handoffs/pr47/evidence/instrumentation-results.xml`（172件）。
lint: `docs/handoffs/pr47/evidence/lint-results.txt`。

| ID | 確認内容（期待） | 実測 | 結果 | 実行方法 | 証跡 |
| --- | --- | --- | --- | --- | --- |
| I43-T01 | 共通式 bias=0/0.5/1・複数 H/L・clamp/coerce 境界 | 全ケース一致 | PASS | 単体 | `EdgePreviewMathTest` 6 tests |
| I43-T03 | カレンダー行が Role.Checkbox + ToggleableState + OnClick を1ノードに持つ | 両行で一致・クリックで Off→On | PASS | 計装 | `InfoSettingsA11yTest` 2 tests |
| I43-T04 | 検索結果行・最近行・SearchEntry の OnClick.label が非空白 | 全 clickable ノードでラベルあり（SetText 所有の TextField を除く） | PASS | 計装 | `SearchScreenA11yTest` 3 tests |
| I43-T05 | requestIntent null → 設定へフォールバック・クラッシュなし・Settings 維持 | HOME_SETTINGS 傍受 hits=1・画面維持 | PASS | 計装 | `HomeRoleFallbackTest.nullRoleRequestIntentFallsBackToHomeSettings` |
| I43-T06 | requestIntent 例外相当 → 同上。launch 時 ActivityNotFoundException も同一路線 | hits=1・画面維持（例外・未解決 Intent の2ケース） | PASS | 計装 | `HomeRoleFallbackTest.throwing…` + `unresolvable…` |
| I43-T07 | タイマー行タップで `launches==1`・タップ後 availability クエリ増分 0 | build 時 ≥1・タップ後増分 0 | PASS | 計装 | `ToolsIntegrationTest.timerTapRunsOnTheAvailabilityResolvedAtRowBuild` |
| I43-T07b | ターゲット設定済み Timer は availability クエリ 0 回・TargetGone 表示 | queries=0 | PASS | 計装 | `ToolsIntegrationTest.timerWithATargetNeverQueriesTheTimerList` |
| I43-T08 | TTL 内2回目の isEnabledInOs は実 read 0 増・TTL 超過で再 read | reads 1→1→2 | PASS | 計装 | `SystemActionsCacheTest.gesturePathReadsReuseOneQueryInsideTheTtl` |
| I43-T09 | refreshEnabled / setObserving(true)（観測中再呼出含む）が必ず fresh read・再アーム | reads 2→3→4・enabled 反映 | PASS | 計装 | `SystemActionsCacheTest.refreshAndSetObservingAlwaysReadFreshInsideTheTtl` |
| I43-T09b | OS設定変更が ContentObserver→refreshEnabled 経路で追従（実 Secure 書換） | enabled=true 到達 | PASS | 計装 | `SystemActionsCacheTest.osSettingsChangeReachesEnabledThroughTheObserver` |
| I43-T10 | 既存 AssistiveServiceTracker/SystemActions 関連テスト全件 | 全件PASS | PASS | 計装 | ToolsIntegrationTest assistive 系4件 + fixture 2件 + SystemActionsCacheTest 3件 |

### 検証コマンド

- `compileDebugKotlin` / `assembleDebug` / `assembleDebugAndroidTest`: BUILD SUCCESSFUL
- `testDebugUnitTest`: 316件 0失敗（2回目実行。下記「試行錯誤」のフレーク1件は
  孤立再実行・再実行ともにPASS）
- `lintDebug`: 0 errors / 7 warnings（全件既存・本差分外: EmptySuperCall・
  ObsoleteSdkInt・UnusedResources(info_weather_needs_region)・版数通知4件）
- `connectedDebugAndroidTest`（flock直列化・emulator-5556）: 172件 0失敗

### テスト計画との差分（記録）

- I43-T06 は「例外」ケースを seam の throw で再現（RoleManager 内部例外の等価
  経路）。さらに「Intent は構築できるが launch で ActivityNotFoundException」
  の3rdケース `unresolvableRoleRequestIntentFallsBackToHomeSettings` を追加し、
  `roleRequest.launch` の catch 経路も固定。
- `requestRoleIntentOverride` は「セット済みならその値が答え」方式。初期実装は
  `override ?: real` で null ケースを作れず T05 初回失敗 → let-return 方式に修正
  （試行錯誤として記録）。
- `setObserving(true)` は従来 `observe == observing` で早期 return していたが、
  仕様「常に fresh read」に合わせ観測中でも fresh read する構成に変更
  （フォアグラウンド復帰の再実行でも背景 revoke を見逃さない）。

### 試行錯誤（透明記録）

- `testDebugUnitTest` 初回: `RecentStoreTest.recording the same target keeps a
  single entry` が 1件失敗（期待 1700000000001 / 実測 1700000000000）。
  エントリ競合のタイムスタンプ競合で本差分無関係。孤立再実行・全体再実行とも
  PASS の既知フレークとして記録（将来の別Issue候補）。
- `HomeRoleFallbackTest.nullRoleRequestIntentFallsBackToHomeSettings` 初回失敗:
  seam が null を返しても `?:` で実 RoleManager 経路に落ちていた。seam の意味論を
  「セット時は完全な答え」に修正して解消。

## 追記: レビュー指摘 F-1（プレビューフレームの高さ不整合）

### 指摘内容

PR #47 レビューで、旧実装 `Modifier.height(PREVIEW_HEIGHT).padding(top = 8.dp)`
では修飾順によりフレーム内容ボックスが 212dp に縮み、`edgeBarTopOffset` には
220dp が渡るため bias=1 のバー下端が枠を 8dp はみ出す指摘（visible artifact
あり）。共有幾何関数自体は正しいため、修正はプレビュー側レイアウトのみ
（実描画 `home/EdgeBar.kt` は不変更）。

### 対応

- `BarPreview` の修飾順を `.fillMaxWidth().padding(top = 8.dp).height(PREVIEW_HEIGHT)`
  に変更。padding が外側マージンとなり、内容ボックス＝トラックが
  `PREVIEW_HEIGHT` と一致（幾何入力と実トラックの同値を回復）。
- 検証タグを追加: `edgePreviewFrame`（frame Box）、
  `edgePreviewBarLeft` / `edgePreviewBarRight`（各 PreviewBar）。
- 弁別テスト `settings/EdgeSettingsPreviewTest.kt`（計装・新規）:
  bias=0/1 の2バーを設定し、bias=0 バー上端＝フレーム上端・bias=1 バー下端＝
  フレーム下端・両バーがフレーム内に収まることを実レイアウト bounds で固定。
- `EdgePreviewMathTest` に不変条件 `offset + effectiveLength <= track` を追加
  （複数トラック高・長さ・bias の総当たり）。

### F-1 の試行錯誤（透明記録）

- 修正編集時に `.fillMaxWidth()` を誤って脱落させ、フレームが子幅（12dp）に
  ラップして左右バーが同一 x に重なった。計装テスト初回失敗の bounds
  （frame が 32px 幅）と `printToLog` のセマンティクスツリーで検出・復元。
- Compose のセマンティクス bounds は修飾チェーン内の testTag 位置の
  座標を報告する。`edgePreviewFrame` タグは `.padding().height()` の内側に
  あるため内容ボックス（マージン除いたトラック本体）を報告し、バーのタグを
  `.offset()` の内側へ移して初めて bias による並進後の位置が観測できた
  （offset より外側のタグは整列済み・並進前の位置を報告していた）。

### F-1 検証結果

| ID | 確認内容（期待） | 実測 | 結果 | 実行方法 | 証跡 |
| --- | --- | --- | --- | --- | --- |
| I43-F1 | bias=0 バー上端＝フレーム内容ボックス上端・bias=1 バー下端＝フレーム下端・両バー格納・左右分離 | 全一致 | PASS | 計装 | `EdgeSettingsPreviewTest.previewBarsStayInsideTheFrameAndBiasOneFlushesTheBottom` |
| I43-F1b | `offset + effectiveLength <= track` 不変条件（トラック220/212/500/1dp × 長さ48/96/160/300 × bias 0/0.25/0.5/1） | 全ケース成立 | PASS | 単体 | `EdgePreviewMathTest.offset plus effective length never exceeds the track` |

F-1 対応後の再実行（emulator-5556 / API 36、flock 直列化）:
`testDebugUnitTest` 317件 0失敗、`connectedDebugAndroidTest` 173件 0失敗、
`lintDebug` 0 errors / 7 warnings（全件既存・本差分外）。
証跡は `docs/handoffs/pr47/evidence/` に最新化済み。

## 実機確認（ラウンド7候補・pending-device-checks.md）

- TalkBack実機でカレンダー行の読み上げ（行名＋チェックボックス役割＋選択状態＋
  ダブルタップ操作）
- TalkBack実機で検索結果行・最近行の読み上げ（行名が操作ラベルとして読まれる）
- タイマー行タップから実タイマーアプリ起動まで（ハンドラ有無両経路）
- Edge設定プレビューで bias=1 のバーが枠下端に収まること（F-1 の目視確認）
