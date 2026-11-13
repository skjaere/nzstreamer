package io.skjaere.usenet.app.collection

import io.skjaere.usenet.app.collection.model.Empty

/*
 * The TV grid's lines above its posters, as one description (TV-4's L2): the grid emits these, in this order, each the
 * whole width of a line; its scrolling, paging and letter jump count a poster's place after them. What shows is what is
 * counted, so the two can't drift apart (#221's hint, counted on a phone whether it showed or not).
 */

/**
 * The lines above the posters for [ui], by key: the top line ("Collection", Find, Select), the switch and the chips, the
 * header line (the count, Jump to letter, the sort); in select mode its bar in their place. Then, when they show, the
 * TMDB key's hint (not over an empty side, which says it itself), the refresher's note and an empty state.
 */
internal fun tvHeaderItems(ui: CollectionUi): List<String> = buildList {
    if (ui.selection != null) add(TV_SELECT) else addAll(listOf(TV_TOP, TV_SIDES, TV_HEADER))
    if (!ui.hasKey && ui.view.empty != Empty.NoTitles) add(TV_HINT)
    if (ui.note != null) add(TV_NOTE)
    if (ui.view.empty != null) add(TV_EMPTY)
}

/** The items before the TV grid's first poster as [ui] shows it: every line the header emits. A title's item is its row after these. */
internal fun tvItemsBefore(ui: CollectionUi): Int = tvHeaderItems(ui).size

/** The header's lines' keys, which no title's is (a title's is "show:123", "movie:456"). */
internal const val TV_TOP = "top"
internal const val TV_SIDES = "sides"
internal const val TV_HEADER = "header"
internal const val TV_HINT = "hint"
internal const val TV_NOTE = "note"
internal const val TV_EMPTY = "empty"
internal const val TV_SELECT = "select"
