# Issue #25 対応メモ — v1.2 TODAY/DOパネル情報階層・密度・質感

対象ブランチ: `feature/issue-25-panels`（base `main` `29c36e8`）
設計書: `docs/handoffs/design-panels-v1_2.md`（確定仕様）

## 実施内容

### パネルクローム（`Panels.kt` PanelLayer）

- 4辺全周 `.border(1.dp, PanelHairline)` → **内側エッジのみ**のhairlineへ。
  `.drawBehind` + `drawLine` で Right パネルは左辺（x=0）、Left パネルは
  右辺（x=width-1dp）に1px線を描画。scrim `0.72f`・paneTitle・close button・
  Switch Access経路は不変。「浮いた板」ではなく端から滑り出るシートの見た目に。

### TODAYパネル

- 日付+曜日を同一Textへ連結（`"${dateText} ${weekdayText}"`、headlineMedium維持）。
- 外枠の一様 `spacedBy(16.dp)` を廃止し**時間距離クラスタ化**:
  - Nowクラスタ: 日付行+天気を `spacedBy(4.dp)` で密接配置（region/provider・
    stale更新時刻の行構造は不変）
  - 予定クラスタ: `PanelDivider` → 「これから」見出し（labelMedium・alpha 0.6・
    `heading()` semantics付与）→ 既存EventRowItem群（行構造・タップ・48dp・
    フォールバック文言不変、events非空時のみ）
  - battery: `PanelDivider` → **bodyLarge→bodySmall、alpha 0.6** へ降格
- `PanelDivider` = `HorizontalDivider(color=PanelHairline, 1.dp,
  padding(vertical=12.dp))` — カード化せず境界線のみ。

### DOパネル

- 「今の条件」見出しの上余白を 16dp→**24dp**、ついでに `heading()` semantics付与
  （「これから」と同じセクション見出し役割のため。A-1のAllApps見出し規約に揃えた）。
- TOOLS展開: 全幅 `OutlinedButton` → **静かなrow**へ:
  `PanelDivider` + Row（`clickable(onClickLabel=tools_expand, role=Button)`・
  `defaultMinSize(minHeight=48.dp)`）。表示は「TOOLS | N件 ›」。
  - `tools_count` = `%1$d件` をstringsに新規追加（toolRows.size＝表示中ツール数）。
  - `›`は装飾として`clearAndSetSemantics`で除外 → マージ済み行の読み上げは
    「TOOLS N件」＋onClickLabel。
  - `clickable`自体がmergeDescendantsを持つため、行全体が1つのセマンティクス
    項目になり `onNodeWithText(tools_expand).performClick()` の既存テスト
    契約を維持。
- toolsExpanded時のUI（TOOLS titleMedium・DOに戻る・自動スクロール・tool row
  状態表示）は不変。

### strings.xml

- 新規: `today_upcoming` = これから、`tools_count` = %1$d件

### 非対象（設計書どおり未変更）

ジェスチャー判定・開閉機構・パネル幅・ヒット領域・GLANCE・All Apps・設定画面・
long-pressメニュー仕様・状態表示/フォールバック文言・48dpタップ領域・
accessibility契約。時刻表示はTODAYに追加しない（GLANCEと役割分離）。

## 検証

- コンパイル: `compileDebugKotlin` + `compileDebugAndroidTestKotlin` OK
- `assembleDebug testDebugUnitTest lintDebug`: BUILD SUCCESSFUL
  - unit **302件 0失敗**（mainと同数 — 変更はView層のみで新規unitテストなし）
  - lint **0 errors / 4 warnings**（ベースライン維持）
- 計装（qil_test / API 36）: **134件 全緑**
  - 実機 Pixel 9 Pro が同時接続中だったため `connectedDebugAndroidTest`
    （全デバイス対象・実機への無断インストール禁止に抵触）は使わず、
    `adb -s emulator-5554 shell am instrument` で手動実行
  - 全量ランは InfoIntegrationTest 途中でエミュレータの system_server が
    クラッシュし `INSTRUMENTATION_ABORTED: System has crashed` で中断。
    `fingerHoldPausesTheAutoDismiss` が teardown `DESTROYED` 待ちで1件失敗
    （変更範囲外の既知フレーク型）
  - エミュレータ冷ブート後に残り10クラスを逐次再実行 → 全合格
    （Info 16/16 で失敗分も緑確認）。証跡:
    `docs/handoffs/pr27/evidence/instrumentation/`

## PR

- Draft PR #27 — head `6bab36b`（base `main@29c36e8`）
- 証跡: `docs/handoffs/pr27/evidence/`（lint XML/SARIF・unit XML・
  計装ログ・HEAD.txt 計49件）

## 残課題・判断メモ

- 「今の条件」への`heading()`付与は設計書明示要件ではないが、同パネル内の
  「これから」との一貫性＋A-1規約に合わせて実施（追加のみ、表示不変）。
- 大きな文字設定の崩れは実機確認項目（pending-device-checks.mdへ追記予定）。
