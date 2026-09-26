# Issue #26 対応メモ — v1.2 起動アプリ選択ピッカーの分類グリッド＋検索化

対象ブランチ: `feature/issue-26-apppicker`（base `main` `fd8060d`）
設計書: `docs/handoffs/design-apppicker-v1_2.md`（確定仕様）

## 実施内容

### 共有セル抽出（`apps/AppGridCell.kt` 新規）

- AllAppsScreenのセル構成（icon 48dp + 装飾placeholder + label 2行省略 +
  重複label時のみpackage行）を `internal fun AppGridCell` として抽出。
- セマンティクス（onClickLabel / 48dp / long-press）は呼び出し側の
  `modifier` に委ねる設計 — AllAppsは combinedClickable+long-press、
  ピッカーは clickable+onClickLabel。
- `AllAppsScreen` は同セルへ差し替え（見た目・semantics不変）。

### `AppPickList` 刷新（`settings/TargetPicker.kt`）

- 旧 `Column + forEach` 全件縦リスト → **検索フィールド + 分類グリッド**:
  - 先頭に `OutlinedTextField`（`picker_search_hint` = アプリを検索）
  - `SearchNormalize.normalize` で正規化し label 部分一致フィルタ
    （前方一致も包含）。`recents` も同じフィルタに通す
  - `AppCategories.sections` 再利用で「最近」→固定順カテゴリの
    `LazyVerticalGrid(Adaptive 88dp)`、見出しに `heading()` semantics
  - `picker_no_results` = 一致するアプリがありません（フィルタ0件時）
  - グリッドは `heightIn(max = 420.dp)` で**高さ制限付き** — 呼び出し元が
    全て `Column.verticalScroll` 等のスクロールコンテナ内のため、無限高
    制約でのクラッシュを防ぐと同時に「ページ全体がアプリ数に比例して
    伸びる」問題自体を解消（ピッカーが内部スクロールを持つ領域になる）
- `recents: List<AppEntry> = emptyList()` を追加（design: 最近セクション）
- モードチップ・`onPick`/`target_clear`・HttpsLinkEditor・ShortcutPickList
  本体は不変（ショートカット元アプリ選択も同じ改善を共有）

### recents の配線

- `TargetPicker` / `ShortcutPickList` / `DoSettingsScreen` /
  `ActionEditor` / `DerivedOpEditor` / `ToolEditor` に
  `recents: List<AppEntry>` を追加（後方互換のため公開関数はデフォルト
  `emptyList()`）
- `HomeUi` → `DoSettingsScreen` へ `viewModel.recentRows` から
  `mapNotNull { it.appEntry }.distinctBy { it.key }`（AllAppsと同一変換）
- `IntroScreen` は signature 不変（初回実行で履歴ゼロのためデフォルト）。
  ダイアログ内の外側 `verticalScroll` は除去（グリッド自身がスクロール
  するため二重スクロール回避。`heightIn(400)` 境界は維持）

### strings.xml

- 新規: `picker_search_hint` = アプリを検索、`picker_no_results` =
  一致するアプリがありません

## 受入条件の対応

| 条件 | 状態 |
| --- | --- |
| カテゴリ別グリッド＋最近セクション | AppCategories.sections共有 |
| label 前方/部分一致フィルタ | SearchNormalize共有・contains |
| package行省略（icon+label） | 同名重複時のみ表示（AllApps同等） |
| onPick/target_clear 契約 | 不変 |
| TalkBack onClickLabel | AppGridCell+clickable onClickLabel |
| 48dp | minimumInteractiveComponentSize |

## 検証

- `assembleDebug testDebugUnitTest lintDebug`: BUILD SUCCESSFUL
  - unit **302件 0失敗** / lint **0 errors / 4 warnings**（ベースライン維持）
- 計装（qil_test / API 36, `connectedDebugAndroidTest` 8m38s）:
  **135件 0失敗 0スキップ 一回完走**
  - `DoSettingsDraftTest` に新規 `pickerSearchFiltersGridAndPickSetsTarget`
    追加（検索フィールド・フィルタ・pick→draft反映のe2e、13件全緑）
  - 実機未接続のため通常の gradle タスクで実行可能だった

## 判断メモ

- 「最近」は設定画面でも表示（design明示）。イントロは履歴が空なので
  signatureを変えず自動的に非表示。
- 「検索のみ」「分類のみ」却下は設計書の代替案欄どおり両採用。
- LazyVerticalGrid の高さ制限 420dp は設定画面内ネストスクロールの
  実装上の制約＋縦長解消の要件を両立させる値。
