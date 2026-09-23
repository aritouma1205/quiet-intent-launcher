package io.github.aritouma1205.quietintentlauncher.settings

import java.util.UUID
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Fixed set of auxiliary icon identifiers for DO actions (design 6). The icon
 * is decorative — the action name stays the primary element. Mapped to the
 * small core icon set in ui/ActionIcons.kt; the extended icon library is
 * intentionally not a dependency.
 */
@Serializable
enum class ActionIcon {
    Star,
    Face,
    Call,
    Play,
    Place,
    Search,
    Pen,
    Heart,
}

/**
 * A stored launch target (design 6, 14.1). Self-describing and serializable:
 * an app target keeps the flattened component of the personal profile only
 * (the current user is implied; UserHandle is never persisted). Arbitrary
 * intent strings and shell commands are not representable. System actions
 * arrive with the system-action stage and are deliberately not assignable
 * yet.
 */
@Serializable
sealed interface StoredTarget {
    /** Flattened component string (ComponentName.flattenToShortString). */
    @Serializable
    @SerialName("app")
    data class App(val component: String) : StoredTarget

    /** A public app shortcut: package + stable shortcut id (design 6). */
    @Serializable
    @SerialName("shortcut")
    data class Shortcut(val packageName: String, val shortcutId: String) : StoredTarget

    /** A user-entered https:// link, validated at save time. */
    @Serializable
    @SerialName("https")
    data class HttpsLink(val url: String) : StoredTarget
}

/** One derived operation of an action (design 6): label + target. */
@Serializable
data class DerivedOp(
    val id: String = UUID.randomUUID().toString(),
    val label: String = "",
    val target: StoredTarget? = null,
)

/**
 * One DO action (design 6, 14.1). The list position inside
 * [SettingsData.actions] is the display order; the id is a stable UUID.
 * Stored data is self-contained — the name is the persisted Japanese name,
 * free to diverge from the seeded defaults.
 */
@Serializable
data class DoAction(
    val id: String = UUID.randomUUID().toString(),
    val name: String = "",
    val icon: ActionIcon = ActionIcon.Star,
    val visible: Boolean = true,
    val target: StoredTarget? = null,
    val aliases: List<String> = emptyList(),
    val derivedOps: List<DerivedOp> = emptyList(),
)

/** Model-level validation failures; the UI maps them to explanations. */
enum class ActionValidationError {
    TooManyActions,
    InvalidName,
    InvalidAlias,
    TooManyDerivedOps,
}

/**
 * Pure constraints for the action list (design 6): max 12 actions, names
 * 1–24 chars and never blank-only, aliases 1–32 chars each and at most 8,
 * derived ops at most 6. Enforced on save and usable from unit tests.
 */
object ActionRules {
    const val MAX_ACTIONS = 12
    const val NAME_MIN_LENGTH = 1
    const val NAME_MAX_LENGTH = 24
    const val ALIAS_MIN_LENGTH = 1
    const val ALIAS_MAX_LENGTH = 32
    const val MAX_ALIASES = 8
    const val MAX_DERIVED_OPS = 6

    fun isValidName(name: String): Boolean =
        name.length in NAME_MIN_LENGTH..NAME_MAX_LENGTH && name.isNotBlank()

    fun isValidAlias(alias: String): Boolean =
        alias.length in ALIAS_MIN_LENGTH..ALIAS_MAX_LENGTH && alias.isNotBlank()

    fun validateAction(action: DoAction): ActionValidationError? = when {
        !isValidName(action.name) -> ActionValidationError.InvalidName
        action.aliases.size > MAX_ALIASES ||
            action.aliases.any { !isValidAlias(it) } ->
            ActionValidationError.InvalidAlias
        action.derivedOps.size > MAX_DERIVED_OPS ->
            ActionValidationError.TooManyDerivedOps
        else -> null
    }

    /** First violation in the list, or null when the list is saveable. */
    fun validateActions(actions: List<DoAction>): ActionValidationError? {
        if (actions.size > MAX_ACTIONS) return ActionValidationError.TooManyActions
        actions.forEach { action ->
            validateAction(action)?.let { return it }
        }
        return null
    }

    fun canAdd(actions: List<DoAction>): Boolean = actions.size < MAX_ACTIONS

    fun newAction(): DoAction = DoAction(name = "行動")

    /** Moves the entry at [index] one position up; a no-op at the top. */
    fun moveUp(actions: List<DoAction>, index: Int): List<DoAction> =
        swap(actions, index, index - 1)

    /** Moves the entry at [index] one position down; a no-op at the bottom. */
    fun moveDown(actions: List<DoAction>, index: Int): List<DoAction> =
        swap(actions, index, index + 1)

    private fun swap(
        actions: List<DoAction>,
        from: Int,
        to: Int,
    ): List<DoAction> {
        if (from !in actions.indices || to !in actions.indices) return actions
        val result = actions.toMutableList()
        val moved = result.removeAt(from)
        result.add(to, moved)
        return result
    }
}

/**
 * The six seeded actions in their designed order (design 6:
 * 撮る・話す・聴く・見る・移動する・調べる). Ids are fixed UUIDs so the
 * defaults stay deterministic — a fresh SettingsData() and a decoded seed
 * compare equal, and a v3 file holding an empty list is never re-seeded.
 */
object DoActionDefaults {
    private const val ID_TAKE = "00000000-0000-4000-8000-000000000001"
    private const val ID_TALK = "00000000-0000-4000-8000-000000000002"
    private const val ID_LISTEN = "00000000-0000-4000-8000-000000000003"
    private const val ID_WATCH = "00000000-0000-4000-8000-000000000004"
    private const val ID_GO = "00000000-0000-4000-8000-000000000005"
    private const val ID_LOOKUP = "00000000-0000-4000-8000-000000000006"

    fun defaults(): List<DoAction> = listOf(
        DoAction(id = ID_TAKE, name = "撮る", icon = ActionIcon.Face),
        DoAction(id = ID_TALK, name = "話す", icon = ActionIcon.Call),
        DoAction(id = ID_LISTEN, name = "聴く", icon = ActionIcon.Play),
        DoAction(id = ID_WATCH, name = "見る", icon = ActionIcon.Star),
        DoAction(id = ID_GO, name = "移動する", icon = ActionIcon.Place),
        DoAction(id = ID_LOOKUP, name = "調べる", icon = ActionIcon.Search),
    )
}
