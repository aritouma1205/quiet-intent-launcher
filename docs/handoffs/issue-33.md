# Issue #33 — Quiet画面でステータスバー自動非表示のたびにエッジバーが跳ねる

## 原因（Issue記載どおり確認）

`HomeUi.kt` のQuiet画面ジオメトリが `WindowInsets.systemBars`
（visibility連動）から `topPx`/`bottomPx`/`leftPx`/`rightPx` を計算し、
`usableTop`/`usableHeight`/`insetSidePx` として両EdgeBarへ渡していた。
没入モード中にステータスバーが一時表示→自動非表示するたび
`systemBars.getTop()` が N ↔ 0 と変化し、バーのトラックが跳ねていた。

## 変更

**採用案**: Issue推奨の `systemBarsIgnoringVisibility` 切替え —
表示状態に連動しないinsetで、常にステータスバー高分のクリアランスを
維持するため跳ねが起きず、一時表示時にバーとステータスバーが
重ならない利点もある。

- `HomeUi.kt`: `WindowInsets.systemBars` → `systemBarsIgnoringVisibility`
  （`topPx`/`bottomPx`/`leftPx`/`rightPx` の全4方向が対象 —
   横方向の理論上の跳ねも同時に解消）。
  `systemGestures`（スワイプ開始ゲーティング用）はvisibility非連動の
  別insetのため据置。
- `HomeUi.kt` 小時計: `statusBarsPadding()` →
  `windowInsetsPadding(statusBarsIgnoringVisibility)`。
  同じQuiet画面上で一時表示のたび上下に跳ねていた同型バグ。
- `Panels.kt` `PanelLayer` / `GlanceOverlay`: 同じく `statusBarsPadding()`
  → `windowInsetsPadding(statusBarsIgnoringVisibility)`。
  パネル表示中もQuietのままなので同じ跳ねが発生し得た（Issueの
  「他要素の点検」項目に対応）。
- 各所 `@OptIn(ExperimentalLayoutApi::class)` を付与（既存パターン
  `SearchScreen.kt` と同型）。

## 対象外（確認済み・変更なし）

- `navigationBarsPadding`: Quietではnav barを隠していないため
  insetは安定。RecoveryScreen / EdgeSettingsUnavailable /
  EditSheet / 非Quiet各画面の `statusBarsPadding` は
  `show(statusBars)` 経路で安定のため据置（Issue記載どおり）。
- `EdgeBar` のヒット領域は `usableTopPx`/`usableHeightPx` と同一
  ジオメトリから派生するため、見た目と入力は自動的に一致したまま
  （Issue #16の入出力整合要件）。

## 視覚上の差分（意図的）

休止状態で各要素が従来よりステータスバー高分だけ下に位置する
（従来は隠れている間クリアランス0だった）。バー・時計・パネル・
GLANCEいずれも一時表示中に重ならなくなるトレードオフで、
Issueの最有力案どおり。

## 検証

- `compileDebugKotlin compileDebugAndroidTestKotlin assembleDebug
  testDebugUnitTest lintDebug`: BUILD SUCCESSFUL —
  unit **302件 0失敗** / lint **0 errors / 4 warnings**（ベースライン維持）
- inset値の注入単体テスト: `WindowInsets.systemBars` はプラットフォーム
  のルートinsetを読むため注入シームがなく、Issue記載どおり実機確認で
  代替。計装では「既存EdgeBar/HomeInput系が緑」で回帰なしを担保。
- `connectedDebugAndroidTest` 全量（qil_test / API 36, 8m20s）:
  **140件 0失敗・一回完走**
- 実機確認項目（ユーザー）: Quietで端スワイプ→ステータスバー一時表示
  →自動非表示の前後でバー位置・長さが不変であること、回転・cutout
  構成、パネル/GLANCE/小時計も跳ねないこと
