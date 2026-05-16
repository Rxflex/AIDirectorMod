package dev.aidirector.tools.impl

import dev.aidirector.actions.ServerActionsHolder
import dev.aidirector.config.ItemRarityCap
import dev.aidirector.tools.Schemas
import dev.aidirector.tools.Tool
import dev.aidirector.tools.ToolContext
import dev.aidirector.tools.ToolResult
import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject

/**
 * Hands the player an item. Rarity-capped via config to keep the director from
 * trivializing progression. The rarity check is intentionally simple — we
 * compare against a static heuristic table, not the game's actual rarity tag,
 * because mods often override that without setting the registry flag.
 */
class GiveItemTool(private val rarityCapProvider: () -> ItemRarityCap) : Tool<GiveItemTool.Args>() {

    override val name: String = "give_item"

    override val description: String = """
        Give the player an item as a small reward or plot device. Stay in-fiction:
        prefer flavor items (a worn map, a written note, a torch) over raw gear.
        Cannot exceed the configured rarity cap.
    """.trimIndent()

    override val parametersSchema: JsonObject = Schemas.obj(
        required = listOf("item_id"),
    ) {
        string(
            name = "item_id",
            description = "Namespaced item id, e.g. 'minecraft:bread', 'minecraft:written_book'.",
            maxLength = 128,
        )
        integer(name = "count", description = "Stack count, 1..64.", min = 1, max = 64)
        string(
            name = "custom_name",
            description = "Optional display name, max 60 chars. Leave null for the item's default name.",
            maxLength = 60,
        )
    }

    override val serializer: KSerializer<Args> = Args.serializer()

    override suspend fun execute(args: Args, ctx: ToolContext): ToolResult {
        val actions = ServerActionsHolder.require()
        val itemId = args.itemId.trim()
        if (!actions.isItemRegistered(itemId)) {
            return ToolResult.Refused("Item '$itemId' is not registered")
        }
        // These items LOOK like lore props but are blank/useless when handed over
        // raw. Redirect the director to the tool that actually produces a
        // readable artifact, instead of giving the player junk.
        BLANK_PROP_REDIRECTS[itemId]?.let { redirect ->
            return ToolResult.Refused(
                "'$itemId' handed over raw is blank and useless to the player. $redirect",
            )
        }
        val cap = rarityCapProvider()
        val severity = estimateRaritySeverity(itemId)
        if (severity > cap.severity) {
            return ToolResult.Refused(
                "Item '$itemId' exceeds rarity cap (severity=$severity, cap=${cap.severity})",
            )
        }
        val count = args.count?.coerceIn(1, 64) ?: 1
        return actions.giveItem(ctx.playerUuid, itemId, count, args.customName).toToolResult()
    }

    /**
     * Conservative heuristic — we'd rather refuse a borderline item than hand
     * out a netherite block. Anything not on the list defaults to severity 2
     * (rare) so unknown modded items are gated.
     */
    private fun estimateRaritySeverity(itemId: String): Int = when {
        itemId in EPIC_ITEMS -> ItemRarityCap.EPIC.severity
        itemId in RARE_ITEMS -> ItemRarityCap.RARE.severity
        itemId in UNCOMMON_ITEMS -> ItemRarityCap.UNCOMMON.severity
        itemId in COMMON_ITEMS -> ItemRarityCap.COMMON.severity
        itemId.startsWith("minecraft:") -> ItemRarityCap.UNCOMMON.severity
        else -> ItemRarityCap.RARE.severity // unknown modded item — treat as rare
    }

    @Serializable
    data class Args(
        @SerialName("item_id") val itemId: String,
        @SerialName("count") val count: Int? = null,
        @SerialName("custom_name") val customName: String? = null,
    )

    companion object {
        /**
         * Items the director keeps trying to hand over as "lore props" but
         * which are blank/non-functional raw. Each maps to the correct tool.
         */
        private val BLANK_PROP_REDIRECTS = mapOf(
            "minecraft:map" to "Use give_treasure_map for a real, readable map.",
            "minecraft:filled_map" to "Use give_treasure_map — it produces a properly centred map.",
            "minecraft:paper" to "Use give_lore_note for a scroll/letter/note (a real written book).",
            "minecraft:writable_book" to "Use give_lore_note — it produces a finished, readable book.",
        )

        private val COMMON_ITEMS = setOf(
            "minecraft:bread", "minecraft:apple", "minecraft:cooked_beef", "minecraft:cooked_chicken",
            "minecraft:wheat", "minecraft:carrot", "minecraft:potato", "minecraft:torch", "minecraft:stick",
            "minecraft:paper", "minecraft:book", "minecraft:string", "minecraft:feather", "minecraft:cobblestone",
            "minecraft:dirt", "minecraft:sand", "minecraft:bone", "minecraft:rotten_flesh",
        )
        private val UNCOMMON_ITEMS = setOf(
            "minecraft:iron_ingot", "minecraft:iron_pickaxe", "minecraft:iron_sword", "minecraft:iron_axe",
            "minecraft:bow", "minecraft:arrow", "minecraft:shield", "minecraft:saddle", "minecraft:compass",
            "minecraft:map", "minecraft:written_book", "minecraft:lantern", "minecraft:ender_pearl",
            "minecraft:experience_bottle", "minecraft:cake",
        )
        private val RARE_ITEMS = setOf(
            "minecraft:diamond", "minecraft:diamond_pickaxe", "minecraft:diamond_sword", "minecraft:diamond_axe",
            "minecraft:enchanted_book", "minecraft:golden_apple", "minecraft:totem_of_undying",
            "minecraft:elytra", "minecraft:trident", "minecraft:music_disc_pigstep",
        )
        private val EPIC_ITEMS = setOf(
            "minecraft:netherite_ingot", "minecraft:netherite_pickaxe", "minecraft:netherite_sword",
            "minecraft:enchanted_golden_apple", "minecraft:nether_star", "minecraft:dragon_egg",
            "minecraft:beacon", "minecraft:command_block",
        )
    }
}
