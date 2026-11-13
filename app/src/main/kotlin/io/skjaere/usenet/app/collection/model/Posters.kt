package io.skjaere.usenet.app.collection.model

/*
 * What a TV's poster of a collected title shows besides its picture, the collection grid's and TV Home's alike: the bar
 * and the one line under its title; and what the grid's menu for it marks it.
 */

/**
 * How far through the title its poster's bar says it is: a show's share watched, a movie's place in it; none before
 * anything of it is watched, nor once it is all watched (up to date, finished), where a full bar would only say what
 * the line under it says.
 */
val TitleRow.posterProgress: Float?
    get() = when (this) {
        is ShowRow -> watchedFraction
        is MovieRow -> progressFraction
    }?.takeIf { it > 0f && it < 1f }

/** The line under a poster: its [TitleRow.cardLine], or under Next to air ([nextToAir]) when a show's next episode airs, in place of the phone's date pill. */
fun TitleRow.posterLine(nextToAir: Boolean): String = (this as? ShowRow)?.airsLine?.takeIf { nextToAir } ?: cardLine

/**
 * What a poster's menu marks its title (TV-4's L13): watched (true) until all of it is, then unwatched (false). Null for a
 * show TMDB hasn't answered for: its aired episodes aren't known, so it can't be marked watched, and the menu offers
 * neither. A movie needs no facts to be marked.
 */
val TitleRow.menuMark: Boolean?
    get() = when (this) {
        is ShowRow -> if (details == null) null else state != ShowState.UpToDate && state != ShowState.Finished
        is MovieRow -> state != MovieState.Watched
    }
