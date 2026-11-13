package io.skjaere.usenet.handoff

/**
 * A kind of card on the setup page, in the edit page's order: a Usenet provider, an indexer, a debrid service account, a
 * torrent indexer (those two only while the app shows debrid, [SetupSeed.debrid]) or the TMDB key.
 */
enum class SetupSection { Provider, Indexer, Service, TorrentIndexer, Tmdb }

/**
 * A field of a card: [key] is its name in the form (the open card's fields go by these, the server
 * knowing which card is open), [maxLength] the most it may hold (as a browser's maxlength counts, in
 * UTF-16 units), [label] what the page calls it.
 */
enum class SetupField(val key: String, val section: SetupSection, val maxLength: Int, internal val kind: Kind, val label: String) {
    ProviderName("p.name", SetupSection.Provider, 100, Kind.Text, "Name"),
    ProviderHost("p.host", SetupSection.Provider, 253, Kind.Text, "Host"),
    ProviderPort("p.port", SetupSection.Provider, NUMBER_MAX, Kind.Number, "Port"),
    ProviderTls("p.tls", SetupSection.Provider, NUMBER_MAX, Kind.Checkbox, "Use TLS"),
    ProviderUsername("p.user", SetupSection.Provider, 256, Kind.Text, "Username"),
    ProviderPassword("p.pass", SetupSection.Provider, 256, Kind.Password, "Password"),
    ProviderConnections("p.conns", SetupSection.Provider, NUMBER_MAX, Kind.Number, "Connections"),
    IndexerName("i.name", SetupSection.Indexer, 100, Kind.Text, "Name"),
    IndexerUrl("i.url", SetupSection.Indexer, 2048, Kind.Text, "API URL"),
    IndexerKey("i.key", SetupSection.Indexer, 512, Kind.Key, "API key"),
    /** Which service the account is: one of the page's choices ([SetupSeed.serviceKinds]), by its value. */
    ServiceKind("s.kind", SetupSection.Service, CHOICE_MAX, Kind.Choice, "Service"),
    ServiceKey("s.key", SetupSection.Service, 512, Kind.Key, "API key"),
    TorrentIndexerName("r.name", SetupSection.TorrentIndexer, 100, Kind.Text, "Name"),
    TorrentIndexerUrl("r.url", SetupSection.TorrentIndexer, 2048, Kind.Text, "API URL"),
    TorrentIndexerKey("r.key", SetupSection.TorrentIndexer, 512, Kind.Key, "API key"),
    /** "Use with your debrid service" (W75): ticked by default, as the torrent indexer form's switch is on. */
    TorrentIndexerDebrid("r.debrid", SetupSection.TorrentIndexer, NUMBER_MAX, Kind.Checkbox, "Use with your debrid service"),
    TmdbKey("t.key", SetupSection.Tmdb, 1024, Kind.Key, "TMDB API key or read access token"),
    ;

    /**
     * How a value is checked: what it may hold and whether it is trimmed. A [Choice] is a radio button's value, one of
     * those the open card offers ([SetupLayout.choices]); a [Checkbox] sends "on" or nothing.
     */
    internal enum class Kind { Text, Password, Key, Number, Checkbox, Choice }
}

/** A number's or the checkbox's bound as received; the number's own rule (at most nine digits) is checked after. */
private const val NUMBER_MAX = 16

/** A choice's value as the page writes it: a short word. */
private const val CHOICE_MAX = 32
