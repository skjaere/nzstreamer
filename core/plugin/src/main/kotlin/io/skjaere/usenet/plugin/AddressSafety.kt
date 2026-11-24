package io.skjaere.usenet.plugin

/** How exposed an address leaves a key sent to it ([PluginHost.addressSafety]), as NZStreamer judges its indexers'. */
enum class AddressSafety {
    /** https: encrypted. */
    Encrypted,

    /** http to an address on the local network (a private address, localhost, .local, .lan): unencrypted, but only there. */
    LocalHttp,

    /** http over the internet: anyone on the way can read what is sent. NZStreamer warns of it for its own indexers. */
    PublicHttp,

    /** Not an http or https address the host can read. */
    Invalid,
}
