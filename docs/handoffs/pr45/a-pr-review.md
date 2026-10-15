# PR #45 A 独立レビュー記録（Issue #42）

- 対象: https://github.com/aritouma1205/quiet-intent-launcher/pull/45
- base: `e391cd6`（main）
- head（レビュー時点）: `cbf9136`（実装 `721fa83` + docs）
- 結果: **APPROVE**（GitHub comment 投稿済み https://github.com/aritouma1205/quiet-intent-launcher/pull/45#issuecomment-6197518629）

## 受入条件判定
1. CancellationException の伝播 — PASS（SettingsStore/RecentStore/WeatherCache の全列挙catch＋追加箇所で CE 先出し、catch順序も正しい）
2. open前書込みの待機 — PASS（`awaitStore()` 経由を全書込み経路が通過、契約: 未start即false・open失敗即false・await中キャンセル伝播）
3. 範囲遵守・回帰防止 — PASS（SettingsStore.update open前write不変、regionガード維持、Corruption分岐不変、配線無破壊）

## 指摘
- MINOR-1: `start()` で置き換えられたジョブが初回ディスパッチ前にキャンセルされると旧 `openSignal` が永久未完了→waiter hang → `openSignal?.complete(null)` で解放済み（commit `9f34b0c`、回帰テスト `a writer superseded before dispatch returns false instead of hanging` を両ストアに追加）
- MINOR-2（情報）: テスト未カバー領域（await中キャンセル・restart時解放等）。必須ではない
- MINOR-3（観察）: restart競合で掴んだ旧storeへの書込みがCEを返し得る（現状到達不能）。issue-42.md に記録

## 未確認範囲
- L4実機は未検証（ストア層のため unit/計装で十分）。ラウンド7に項目記録済み
- 既存flake2件（WeatherServiceTest backoff・RecentStoreTest 収集競合）は base でも再現する既存問題と判断
