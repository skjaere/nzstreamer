package io.skjaere.usenet.app.search

import io.skjaere.usenet.core.formatSize
import io.skjaere.usenet.releases.episodeShare
import io.skjaere.usenet.search.GroupKind
import io.skjaere.usenet.search.RankedItem
import io.skjaere.usenet.search.ResultGroup

/** What a release list is for: playing a release ([Play]), as it always was, or downloading one ([Download]). */
sealed interface ReleaseListMode {
    data object Play : ReleaseListMode

    /**
     * Choose release for a download (W112, L355, L386): the releases ranked for downloading, a tap downloading the one
     * tapped at once ([onDownload]), whose caller closes the list and the sheet. [free]: what a new download may take on
     * this device (DownloadSpace.free), against which each release's [need] is checked: one that won't fit stays in its
     * place, greyed and not tappable. [startsOnWifi]: this phone is on mobile data while downloads wait for Wi-Fi or
     * Ethernet. [seasonEpisodes]: the season's episode count (TMDB's) for an episode's list, which a season pack's share of
     * the one episode is reckoned by, as the ranker reckons its bitrate. [onChooseFile]: Choose file on a pack's card, which
     * opens the pack to choose the one file to download ([fileChoice], L403); none where the list offers it on no card.
     */
    class Download(
        val free: Long,
        val startsOnWifi: Boolean,
        val seasonEpisodes: Int? = null,
        val onChooseFile: ((RankedItem) -> Unit)? = null,
        val onDownload: (RankedItem) -> Unit,
    ) : ReleaseListMode {
        /** What [item] needs on this device for the one episode or film: a season pack's share where its season is counted, else its size. */
        fun need(item: RankedItem): Long = need(item, seasonEpisodes)

        fun fits(item: RankedItem): Boolean = need(item) <= free

        /** "Won't fit: needs about 4.3 GB", in the failure line's place, for a release that doesn't [fit][fits]; null for one that does. */
        fun wontFit(item: RankedItem): String? = if (fits(item)) null else "Won't fit: needs about ${formatSize(need(item))}"

        /** "Season pack · this episode about 1.4 GB": what a season pack's one episode comes to, where its season is counted. */
        fun packLine(item: RankedItem): String? = packShare(item)?.let { "Season pack · this episode about ${formatSize(it)}" }

        /**
         * The line under the sorts, in [RankedForLine]'s place: how the list is ranked; the space free and what a tap does,
         * or, with a release of [group] too big, that those are greyed; on mobile data, that it starts on Wi-Fi.
         */
        fun lines(group: ResultGroup): List<String> = listOfNotNull(
            RANKED_FOR_DOWNLOADING,
            "${formatSize(free)} free on this device. " + if (group.playable.all(::fits)) "A tap downloads the release." else "Releases that won't fit are greyed.",
            STARTS_ON_WIFI.takeIf { startsOnWifi },
        )

        /**
         * The bar's title and subtitle: "Download S02E04" over "Harbor Lights · 7 releases from 3 indexers"; a film's,
         * "Download Copper Crown" over its year and its releases.
         */
        fun bar(group: ResultGroup, title: String): Pair<String, String> = if (group.kind == GroupKind.Movie) {
            "Download $title" to listOfNotNull(group.year?.toString(), releasesFrom(group)).joinToString(" · ")
        } else {
            "Download ${group.label}" to "$title · ${releasesFrom(group)}"
        }

        private fun packShare(item: RankedItem): Long? = episodeShare(item.item.sizeBytes, item.parsed, seasonEpisodes)

        /**
         * Choose file on [item]'s card (L403), where the list offers it ([onChooseFile]): on a Usenet release named as
         * several episodes ([offersFileChoice]) that fits; greyed, [FileChoiceOffer.NeedsWifi], on mobile data while
         * downloads wait for Wi-Fi or Ethernet, as opening its pack reads tens of MB (W110). Null on any other card: a single
         * episode, one greyed as it won't fit, a torrent.
         */
        fun fileChoice(item: RankedItem): FileChoiceOffer? = when {
            onChooseFile == null || item.item.infoHash != null || !item.offersFileChoice || !fits(item) -> null
            startsOnWifi -> FileChoiceOffer.NeedsWifi
            else -> FileChoiceOffer.Offered
        }

        /** How a card offers Choose file: [Offered], or greyed as opening its pack [NeedsWifi]. */
        enum class FileChoiceOffer { Offered, NeedsWifi }

        companion object {
            /**
             * What [item] needs on this device for one episode of a season of [seasonEpisodes] (TMDB's count), or for a film
             * (none): a season pack's share of the episode where the season is counted, as the ranker reckons its bitrate, else
             * its size.
             */
            fun need(item: RankedItem, seasonEpisodes: Int?): Long = episodeShare(item.item.sizeBytes, item.parsed, seasonEpisodes) ?: item.item.sizeBytes

            const val RANKED_FOR_DOWNLOADING = "Ranked for downloading: smaller first"
            const val STARTS_ON_WIFI = "Starts on Wi-Fi: this phone is on mobile data."

            /** Under a greyed Choose file, on mobile data while downloads wait for Wi-Fi or Ethernet. */
            const val NEEDS_WIFI = "Needs Wi-Fi"
        }
    }
}

/** "7 releases from 3 indexers": how many releases [group] holds, and how many indexers listed them. */
internal fun releasesFrom(group: ResultGroup): String {
    val indexers = (group.playable + group.hidden).map { it.item.indexerId }.distinct().size
    return "${group.size} release${if (group.size == 1) "" else "s"} from $indexers indexer${if (indexers == 1) "" else "s"}"
}
