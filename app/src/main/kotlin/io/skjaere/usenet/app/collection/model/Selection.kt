package io.skjaere.usenet.app.collection.model

/**
 * Select mode: whether it is on and the rows picked. Actions take only [effective], the selected
 * rows the list still shows: a filter or a search changed since can't make one act on a row out of
 * sight.
 */
data class Selection(val active: Boolean = false, val keys: Set<TitleKey> = emptySet()) {
    /** A long press starts with its row; Select with none. */
    fun start(with: TitleKey? = null) = Selection(true, setOfNotNull(with))

    fun toggle(key: TitleKey) = copy(keys = if (key in keys) keys - key else keys + key)

    fun exit() = Selection()

    /** The selected rows of [visible], in its order: what an action acts on. */
    fun effective(visible: List<TitleKey>): List<TitleKey> = visible.filter { it in keys }

    fun count(visible: List<TitleKey>): Int = visible.count { it in keys }

    fun allSelected(visible: List<TitleKey>): Boolean = visible.isNotEmpty() && visible.all { it in keys }

    /** Every row of [visible]; when they all are already, none of them ("Select none"). */
    fun selectAll(visible: List<TitleKey>): Selection =
        if (allSelected(visible)) copy(keys = keys - visible.toSet()) else Selection(true, keys + visible)

    /** The keys to keep across a process death; null past [MAX_SAVED] (a saved state that large risks TransactionTooLargeException). */
    fun saved(): List<String>? = keys.takeIf { active && it.size <= MAX_SAVED }?.map { it.value }

    companion object {
        const val MAX_SAVED = 300

        fun restore(saved: List<String>?): Selection = saved?.let { Selection(true, it.mapNotNull(TitleKey::parse).toSet()) } ?: Selection()
    }
}

/** What the list does next to bring a title just added into view. */
sealed interface RevealStep {
    data object SwitchSide : RevealStep

    /** Not in the rows yet: the library hasn't told of it. */
    data object Wait : RevealStep

    /** The filter or the search hides it. */
    data object ClearFilters : RevealStep

    data class ScrollTo(val index: Int) : RevealStep
}

object Reveal {
    /** [view] is what the list on [side] shows (null until known); [sideRows] every title of the target's side, null until known. */
    fun step(target: TitleKey, side: Side, view: SideView?, sideRows: Set<TitleKey>?): RevealStep {
        if ((side == Side.Shows) != target.isShow) return RevealStep.SwitchSide
        if (view == null || sideRows == null || target !in sideRows) return RevealStep.Wait
        val index = view.rows.indexOfFirst { it.key == target }
        return if (index < 0) RevealStep.ClearFilters else RevealStep.ScrollTo(index)
    }
}

/**
 * Where focus goes once [removed] rows leave [before] (the list's order): the row after the last one
 * removed, else the nearest one before it; null when none is left (the screen's top takes the focus
 * then: a TV's switch, its selected side; a phone's ⌕, or Select while the search is open). Home's
 * Up next goes by it too ([io.skjaere.usenet.app.home.UpNextEdit.focusAfter]).
 */
fun <K> focusAfterRemoval(before: List<K>, removed: Set<K>): K? {
    val last = before.indexOfLast { it in removed }
    if (last < 0) return null
    return before.drop(last + 1).firstOrNull { it !in removed } ?: before.take(last).lastOrNull { it !in removed }
}
