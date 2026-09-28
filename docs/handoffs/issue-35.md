# Issue #35 / #36 — 行動設定: 各行動を折りたたみ可能にする

Issue #35 と #36 は同一タイトル・同一本文・21秒差の重複（ダブルサブミット疑い）。
どちらを正本にするか・片方のクローズはユーザー判断のため、本記録は両番号を併記する。

対象: 「行動・道具」設定（`DoSettingsScreen`）。Context Slots 画面はスコープ外。

## 実装（`settings/DoSettingsScreen.kt` + `res/values/strings.xml`）

### 折りたたみ行（閉）

- `CollapsedActionRow`: アイコン + 行動名(bodyLarge, 1行省略) + 起動先サマリ
  (`targetSummary`、bodySmall, 1行省略) + 上へ/下へ IconButton + chevron
  （`ArrowDropDown` を展開時180°回転）。
- `CollapsedToolRow`: ツール名 + 起動先サマリ（TARGETABLEのみ、Timer未設定は
  `tool_timer_list`）+ 表示Switch + 上へ/下へ + chevron — Issueの実装判断委譲
  どおり Switch を閉じた行内に残す（design 11.2 のスイッチ到達を維持）。
- 行タップでトグル。削除は展開時のみ（誤タップ防止）。

### アコーディオン状態

- `expandedId: String?` を `rememberSaveable` — 行動UUIDと `"tool:<id>"` が
  同一キー空間で衝突しない。別行を開くと前行は畳む（単一展開）。
- `pickerSlot` は従来どおり `remember`（復元で古いpickerが蘇らないよう意図的に
  非saveable — 既存コメント維持）。

### focus 導線の維持

- `expandedId` 初期値は `focusActionId ?: focusToolId?.let { "tool:$it" }` —
  フォーカス行は初期から展開され、内部のpickerが `pickerSlot` 指定でマウント
  できる。
- `LaunchedEffect(Unit)`: focus/toolFocus から `pickerSlot` を設定し
  `onEditFocusConsumed()`、スクロール対象は `scrollTargetKey` へ。
- `scrollTargetKey` → `LaunchedEffect` で `snapshotFlow { itemOffsets[key] }` が
  非nullになるまで待って `animateScrollTo` — 展開によるレイアウト完了を
  待つ遅延スクロール（従来の `onPositioned` コールバックを画面側に移管し、
  行コンテナの `onGloballyPositioned` で位置取得）。
- 行動追加: `expandedId = added.id` + `scrollTargetKey = added.id` で
  自動展開＋スクロール。

### アクセシビリティ

- 折りたたみ先頭領域: `semantics(mergeDescendants = true)` +
  `stateDescription`（展開中/折りたたみ）+ `clickable(onClickLabel =
  展開する/折りたたむ)` — TalkBack が状態と操作を読む。
- chevron IconButton の contentDescription も同ラベル。
- ↑↓・Switch は先頭領域の外側に独立配置（merge対象外、スイッチ操作可能）。

### ToolEditor の調整

- ツール名Textは閉じた行に移ったため、展開エディタ側の重複表示を除去。

## 追加/更新テスト（`DoSettingsDraftTest`, `DoActionIntegrationTest`）

新規5件:

- `collapsedRowShowsNameAndExpandsToEditor`: 初期はsetTextノード0件、
  行tap→編集フィールド出現→改名→保存で `saved.actions[0].name == "写真"`。
- `accordionCollapsesPreviousRow`: 撮る展開→話すtapで撮るのフィールド消滅。
- `collapsedReorderMovesActionWithoutExpanding`: 閉じた行の「下へ」で順序が
  入れ替わり、エディタは開かず、保存で [話す, 撮る, …] を確認。
- `focusActionIdAutoExpandsAndOpensPicker`: `focusActionId=…004(見る)` で
  picker検索欄がマウントされ、見るのエディタのみ展開・他行は畳まれたまま。
- `addedActionAutoExpands`: 「行動を追加」で新行（名前 "行動"）が展開される。

既存テスト更新:

- `expandAction(name)` ヘルパー追加 — merged clickable行をtapして展開。
- 全展開前提だった各テストに `expandAction("撮る")` を挿入
  （blank/validation/save/dirtyBack/osBack/cancel/picker系）。
- `DoActionIntegrationTest` 3件にも同ヘルパーで展開を挿入。
- `SearchContextIntegrationTest`（削除回帰）は `openActionEditor(actionId)` の
  自動展開で無修正のまま動作する構成。

## 試行錯誤（透明記録）

- `focusActionId` テスト初回失敗: `apps = emptyList()` では `AppPickList` が
  `all_apps_empty` 分岐で検索欄を描画しない。アプリ一覧を渡す形に修正
  （テスト側の問題、製品バグではない）。
- `derivedOpPicker…` で pick が届かない挙動を2度観測 — セマンティクスダンプで
  セルがwindow下端外（t=2454-2691px）に配置されるのを確認。pickerグリッドは
  `heightIn(420dp)` で内容が収まると `ScrollAxisRange.maxValue=0` となり、
  cellへの `performScrollTo` がno-opになる。対策: ピッカー直下の
  「派生操作を追加」を `performScrollTo` してページ側を引き上げてから
  セルをclick — 以後安定して合格。ソフトキーボード（検索欄focus）も
  `InputMethodManager.hideSoftInputFromWindow` で明示的に閉じる。
- `failedSaveKeepsToolVisibilityDraft` で teardown の
  `ActivityScenario` DESTROYED待ちタイムアウトを1度観測 — 既知のエミュレータ
  フレーク（テスト本体は合格、再実行で緑）。

## 検証

- `compileDebugKotlin` / `assembleDebug` / `assembleDebugAndroidTest`:
  BUILD SUCCESSFUL
- `testDebugUnitTest`: **302件 0失敗**
- `lintDebug`: **0 errors / 4 warnings**（ベースライン維持）
- 計装（qil_test / API 36）: 対象3クラス個別 — DoSettingsDraftTest **20/20**、
  DoActionIntegrationTest **8/8**、ToolsIntegrationTest **35/35**
- `connectedDebugAndroidTest` 全量: **全145件カバー・全緑**（2段階） —
  初回全量実行は50件消化後にエミュレータ system_server がクラッシュして中断
  （`IActivityManager` NPE・PackageManager死亡を確認）。その際
  InfoIntegrationTest で2件の失敗を記録したが、いずれもテスト本体ではなく
  teardown系の環境フレーク（`ActivityScenario` DESTROYED待ちタイムアウト/
  `<failure/>` 空要素）。冷ブート後に残11クラスをクラス単位で再実行し
  **106件 0失敗**（失敗2件を含むInfoIntegrationTestも16/16で緑）。
  部分実行のAllApps 8・DoAction 8・HomeInput 23合格と併せ全件緑を確認。
- Draft PR #37: head `aed11a3`（feat）、base `main@78c84c3`（PR #34マージ後）

## 実機確認（ラウンド5候補・pending-device-checks.md）

- 行動数が多い状態で閉じた一覧が1〜2行/行動に収まりスクロール短縮
- 行tap→展開/折りたたみの操作感、chevron向き
- DOパネル未設定tap・「この行動を編集」・TOOLSからのfocus経路で
  対象展開+スクロール+pickerオープン
- 閉じた行での↑↓並び替え・TOOLS表示Switch
- TalkBackでの展開状態読み上げ・行tap操作
- 行動を追加→自動展開＋スクロール
