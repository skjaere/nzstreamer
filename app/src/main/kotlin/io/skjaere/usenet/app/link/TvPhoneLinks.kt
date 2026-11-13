package io.skjaere.usenet.app.link

import io.skjaere.usenet.app.AppContainer
import io.skjaere.usenet.link.KeyId
import io.skjaere.usenet.link.LinkMessage.Hello

/**
 * The TV's own hearing of its paired phones' links (L33, L34): its status goes out on each ([StatusPump]), and what a phone
 * is owed of where its plays stopped ([TvFinals]). A link coming up says nothing on the player in front: L35's "Galaxy
 * phone is connected" over a film was taken away (#320), as a phone's app links to its TVs each time it comes to the front.
 */
class TvPhoneLinks(private val status: StatusPump, private val finals: TvFinals) : PhoneLinks {
    override fun linked(link: PhoneLink) {
        status.linked(link)
        finals.linked(link)
    }

    override fun closed(link: PhoneLink) {
        status.closed(link)
        finals.closed(link)
    }

    override fun session(phone: KeyId): Hello.Session? = status.session(phone)

    override fun acked(phone: KeyId, ids: List<String>) = finals.acked(phone, ids)

    companion object {
        fun of(c: AppContainer) = TvPhoneLinks(StatusPump(c.appScope, StatusPumpDeps.of(c)), c.tvFinals)
    }
}
