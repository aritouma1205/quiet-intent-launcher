# Issue #30 — UX文案改善（初回案内・用語補足・検索placeholder）

依頼: `docs/handoffs/implementer-request-29-30.md` ／ Issue #30 全件。
変更は `strings.xml` のみ。レイアウト・挙動の変更なし。

## 文案対応表（変更前 → 変更後）

### 1. 初回案内にタップ操作を追加

`intro_body`:

- 前: 「画面の右端のバーを引くと行動（DO）、左端のバーを引くと今日の
  情報（TODAY）が開きます。壁紙の空いているところを上にスワイプすると
  検索が開き、そこから全アプリと設定に進めます。」
- 後: 「画面の右端のバーをタップ、または内側へ引くと行動（DO）、左端の
  バーをタップ、または内側へ引くと今日の情報（TODAY）が開きます。
  壁紙をタップするとひと目の情報（GLANCE）が開き、上にスワイプすると
  検索が開き、そこから全アプリと設定に進めます。」

バーは実際にタップでも同パネルが開くため「タップ、または内側へ引く」に
変更。あわせて GLANCE の初出説明として「壁紙をタップするとひと目の情報
（GLANCE）」を本文に追加（提案2の「初回で説明」もここで担保）。

### 2. 独自用語に日本語説明

- `settings_section_context`:
  `Context Slots` → `時間や曜日で変わる行動（Context Slots）`
  （設定トップのセクション見出し。Issue例「時間や曜日で変わる行動 -
  Context Slots」を既存の括弧書き文体に合わせて（）表記に）
- `settings_context_entry`:
  `Context Slotsの条件` → `スロットの条件の設定`
  （セクション見出しが用語＋説明を担うため、行は他行と同じ「〜の設定」
  文体に整理）
- `settings_info_entry`:
  `GLANCE・天気・予定の設定` → `壁紙タップで出る情報（GLANCE）・天気・
  予定の設定`（Issue例「壁紙をタップしたときの情報表示」を圧縮）

変更しないもの（判断記録）:

- `context_settings_title`（Context Slots）: 編集画面タイトルと検索結果
  ラベル（`SettingsDestination.ContextSlots`）を兼ねる機能名。初出経路
  である設定一覧で説明済みのため、行き先名は用語のまま維持
- `context_now_heading`（いま）: DOパネル内の見出しは既に日本語
- `settings_context_note` / `settings_info_note` /
  `info_glance_position` / `action_delete_body_refs`: 説明済みの文脈内の
  参照なので用語のまま維持
- GLANCEの `paneTitle`（"GLANCE"）: semanticsのパネル名。DO/TODAY同様
  機能名であり、本Issueの対象外と判断

### 3. 検索placeholderの対象明示

- `search_hint`: `検索` → `行動・アプリ・設定を検索`
  （Universal Search の対象範囲を入力前に示す。`picker_search_hint`
  「アプリを検索」・`region_search_hint`「地名を入力」は既に対象明示済み）

## 検証

- `assembleDebug testDebugUnitTest lintDebug`: BUILD SUCCESSFUL
  - unit **302件 0失敗** / lint **0 errors / 4 warnings**（ベースライン維持）
- 計装（qil_test / API 36, `connectedDebugAndroidTest` 11m）:
  **135件 0失敗・一回完走**
  - 既存テストは全て `res(R.string.*)` 経由またはfixture文言のため
    文案変更への依存なし（事前に静的確認済み）
