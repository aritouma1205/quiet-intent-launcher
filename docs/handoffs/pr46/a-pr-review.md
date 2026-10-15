# PR #46 A 独立レビュー記録（Issue #40）

- 対象: https://github.com/aritouma1205/quiet-intent-launcher/pull/46
- base: `8f47354`（feature/issue-38-app-uninstall 先端、Depends on PR #39）
- head（レビュー時点）: `e3cdc4a`（実装 `57438c9` + docs）
- 結果: **APPROVE**（GitHub comment 投稿済み https://github.com/aritouma1205/quiet-intent-launcher/pull/46#issuecomment-6197518992）

## 受入条件判定
1. onResume で軽量経路のみ解除 — PASS（`MainActivity.kt:106-116` `viewModel.nav.onForegrounded()` のみ。副作用二重化なし・冪等）
2. ActivityResult コールバックへ明示クリアを入れない — PASS（`roleRequest` は `refreshHomeRole()` のみ）
3. 残存 arm 経路へロールバック拡大 — PASS（arm サイト全8箇所網羅、フォールバック分岐正しい、非arm経路への誤適用なし）
4. design 3 不変条件の回復 — PASS（pause-only→Quiet、onStop往復→復元、recreate保持、HOME→Quiet、プロセス再起→Quiet）

## 指摘（すべて情報レベル）
- MINOR-1（既知制限）: マルチウィンドウでのフラグ早期消費 → design-v1.md §3 および issue-40.md に明記済み（commit `7da57b6`）
- MINOR-2（情報）: `roleRequest.launch` ANFE 入口リンクは計装未検証（遷移先両腕は網羅済み）
- MINOR-3（情報）: pause-only 復帰で `refreshHomeRole` 非実行（ベースから同一の露出）
- 副次的改善: ベースで未 catch だった入れ子 fallback の二重 ANFE 未捕捉例外経路を閉鎖

## 未確認範囲
- 実機・マルチウィンドウ挙動は未検証。ラウンド7に項目記録済み
