package io.skjaere.usenet.app.play

import androidx.media3.common.PlaybackException
import io.skjaere.nntp.NntpConnectionException
import io.skjaere.nntp.NntpConnectionLostException
import io.skjaere.usenet.core.UsenetError
import io.skjaere.usenet.core.toUsenetError
import io.skjaere.usenet.player.LinkFailure
import io.skjaere.usenet.player.LinkUnavailable
import io.skjaere.usenet.player.UsenetDataSourceException
import org.junit.jupiter.api.Test
import java.io.IOException
import java.net.ConnectException
import java.net.SocketException
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * What the player's "Playback stopped" panel says, in words, and whether the release itself is to blame; and what a paired
 * phone is told of it (L37), in the TV's words: from the failure's kind alone, never a library's or a server's own.
 */
class PlaybackFailureTest {
    private val io = PlaybackException.ERROR_CODE_IO_UNSPECIFIED

    /**
     * Said for any number of providers (S9-14): an article is missing only once every provider was
     * asked, and a login or connection failure is one provider's, which isn't known by name here.
     */
    @Test
    fun `usenet failures name what went wrong`() {
        assertEquals(
            PlaybackFailure(
                "Part of this release is missing from every Usenet provider you use.",
                releaseAtFault = true,
                remote = "Part of this release is missing from every Usenet provider on the TV.",
            ),
            PlaybackFailure.of(UsenetError.MissingArticle("<a@b> not found on any pool"), PlaybackException.ERROR_CODE_IO_FILE_NOT_FOUND),
        )
        assertEquals(
            PlaybackFailure("Part of this release arrived damaged.", releaseAtFault = true, remote = "Part of this release arrived damaged."),
            PlaybackFailure.of(UsenetError.Corrupt("CRC"), io),
        )
        assertEquals(
            PlaybackFailure(
                "A Usenet provider rejected the login (Settings › Usenet providers).",
                releaseAtFault = false,
                remote = "A Usenet provider on the TV rejected the login.",
            ),
            PlaybackFailure.of(UsenetError.AuthFailed("481"), io),
        )
        assertEquals(
            PlaybackFailure("A Usenet provider can't be reached.", releaseAtFault = false, remote = "The TV can't reach a Usenet provider."),
            PlaybackFailure.of(UsenetError.ConnectionFailed("refused"), io),
        )
        assertEquals(
            PlaybackFailure(
                "The connection to a Usenet provider was lost.",
                releaseAtFault = false,
                remote = "The TV lost the connection to a Usenet provider.",
            ),
            PlaybackFailure.of(UsenetError.Network("reset"), io),
        )
        assertEquals(
            PlaybackFailure("A Usenet provider stopped answering.", releaseAtFault = false, remote = "A Usenet provider on the TV stopped answering."),
            PlaybackFailure.of(UsenetError.Timeout(null), io),
        )
        assertEquals(
            PlaybackFailure(
                "A Usenet provider sent an unexpected answer.",
                releaseAtFault = false,
                remote = "A Usenet provider on the TV sent an unexpected answer.",
            ),
            PlaybackFailure.of(UsenetError.Protocol("500"), io),
        )
        assertEquals(
            PlaybackFailure("No Usenet provider is set up.", releaseAtFault = false, remote = "No Usenet provider is set up on the TV."),
            PlaybackFailure.of(UsenetError.NotConfigured, io),
        )
        assertEquals(
            PlaybackFailure("Reading the release failed.", releaseAtFault = false, remote = "Reading the release failed on the TV."),
            PlaybackFailure.of(UsenetError.Unknown("boom"), io),
        )
    }

    /**
     * A connection the server or the network dropped says it was lost, as before ktor-nntp-client wrapped such failures
     * (#256); a provider out of reach says it can't be reached. From what the library throws for each.
     */
    @Test
    fun `a dropped connection says it was lost, a provider out of reach that it can't be reached`() {
        val lost = NntpConnectionLostException("Connection to news.example.net:563 failed: Connection reset", SocketException("Connection reset"))
        assertEquals(
            PlaybackFailure(
                "The connection to a Usenet provider was lost.",
                releaseAtFault = false,
                remote = "The TV lost the connection to a Usenet provider.",
            ),
            PlaybackFailure.of(lost.toUsenetError(), io),
        )
        val refused = NntpConnectionException("Could not connect to news.example.net:563: Connection refused", ConnectException("Connection refused"))
        assertEquals(
            PlaybackFailure("A Usenet provider can't be reached.", releaseAtFault = false, remote = "The TV can't reach a Usenet provider."),
            PlaybackFailure.of(refused.toUsenetError(), io),
        )
    }

    /**
     * While a provider is left out (its login rejected, or out of reach), an article missing on the
     * others may be on it: not the release's fault, which stays the one Resume plays (#172).
     */
    @Test
    fun `an article missing while a provider is left out is not the release's fault`() {
        assertEquals(
            PlaybackFailure(
                "Part of this release is missing from the Usenet providers in use. A provider left out because it rejected the login or couldn't be reached may have it (Settings › Usenet providers).",
                releaseAtFault = false,
                remote = "Part of this release is missing from the Usenet providers in use on the TV.",
            ),
            PlaybackFailure.of(UsenetError.MissingArticle("<a@b> not found on any pool"), PlaybackException.ERROR_CODE_IO_FILE_NOT_FOUND, providersLeftOut = true),
        )
        // Anything else reads as without one left out.
        assertEquals(PlaybackFailure.of(UsenetError.Corrupt("CRC"), io), PlaybackFailure.of(UsenetError.Corrupt("CRC"), io, providersLeftOut = true))
    }

    @Test
    fun `a file the device can't decode or read is the release's fault`() {
        val decoder = listOf(
            PlaybackException.ERROR_CODE_DECODING_FAILED, PlaybackException.ERROR_CODE_DECODING_FORMAT_EXCEEDS_CAPABILITIES,
            PlaybackException.ERROR_CODE_DECODING_FORMAT_UNSUPPORTED,
        )
        decoder.forEach { code ->
            assertEquals(
                PlaybackFailure(
                    "This device can't decode this release's video or audio.",
                    releaseAtFault = true,
                    remote = "The TV can't decode this release's video or audio.",
                ),
                PlaybackFailure.of(null, code), "$code",
            )
        }
        listOf(
            PlaybackException.ERROR_CODE_PARSING_CONTAINER_MALFORMED, PlaybackException.ERROR_CODE_PARSING_MANIFEST_MALFORMED,
            PlaybackException.ERROR_CODE_PARSING_CONTAINER_UNSUPPORTED, PlaybackException.ERROR_CODE_PARSING_MANIFEST_UNSUPPORTED,
        ).forEach { code ->
            assertEquals(
                PlaybackFailure(
                    "The file is damaged or in a format the player can't read.",
                    releaseAtFault = true,
                    remote = "The file is damaged or in a format the TV's player can't read.",
                ),
                PlaybackFailure.of(null, code), "$code",
            )
        }
    }

    /**
     * The device's decoder or sound output failing says nothing of the release: a decoder that
     * wouldn't start or that the system took back, sound that stopped when an AV receiver was
     * switched off. The panel still shows, but the release stays the one Resume plays.
     */
    @Test
    fun `a decoder or sound output the device lost is not the release's fault`() {
        listOf(
            PlaybackException.ERROR_CODE_DECODER_INIT_FAILED, PlaybackException.ERROR_CODE_DECODER_QUERY_FAILED,
            PlaybackException.ERROR_CODE_DECODING_RESOURCES_RECLAIMED,
        ).forEach { code ->
            assertEquals(
                PlaybackFailure(
                    "This device's video or audio decoder stopped working.",
                    releaseAtFault = false,
                    remote = "The TV's video or audio decoder stopped working.",
                ),
                PlaybackFailure.of(null, code), "$code",
            )
        }
        listOf(
            PlaybackException.ERROR_CODE_AUDIO_TRACK_INIT_FAILED, PlaybackException.ERROR_CODE_AUDIO_TRACK_WRITE_FAILED,
            PlaybackException.ERROR_CODE_AUDIO_TRACK_OFFLOAD_WRITE_FAILED, PlaybackException.ERROR_CODE_AUDIO_TRACK_OFFLOAD_INIT_FAILED,
        ).forEach { code ->
            assertEquals(
                PlaybackFailure("This device's sound output stopped working.", releaseAtFault = false, remote = "The TV's sound output stopped working."),
                PlaybackFailure.of(null, code), "$code",
            )
        }
    }

    /**
     * Video the player selected none of, as no decoder takes it (the probe passed it): the release's fault, so Resume
     * doesn't choose it again and Try another release is offered, its video named as the play sheet names it.
     */
    @Test
    fun `video no decoder takes is the release's fault, named`() {
        assertEquals(
            PlaybackFailure(
                "This device can't play this release's video (2160p Dolby Vision profile 7).",
                releaseAtFault = true,
                remote = "The TV can't play this release's video (2160p Dolby Vision profile 7).",
            ),
            PlaybackFailure.noVideo(listOf("2160p Dolby Vision profile 7")),
        )
    }

    /**
     * A debrid play's file no longer to be had (L204, W81), as DbPhoneFailures words it: the app asked the service once, by
     * itself, for a fresh link before any of these shows. Only a refused key names the service, and only a removal is the
     * release's fault; a paired phone is told it in the TV's words.
     */
    @Test
    fun `a debrid play's failures say what the service did, only a refused key naming it`() {
        fun failure(reason: String, remote: String, atFault: Boolean = false) = PlaybackFailure(reason, releaseAtFault = atFault, remote = remote)
        fun of(link: LinkFailure) = PlaybackFailure.of(link)
        assertEquals(failure("No longer available from the service.", "No longer available from the TV's debrid service."), of(LinkFailure.NoWorkingLink))
        assertEquals(failure("This release is no longer cached.", "This release is no longer cached on the TV's debrid service."), of(LinkFailure.NotCached))
        assertEquals(
            failure("The debrid service removed this release, so it can’t be played from there.", "The TV's debrid service removed this release.", atFault = true),
            of(LinkFailure.Removed),
        )
        assertEquals(
            failure("The debrid service’s limit was reached. Try again in about 20 minutes.", "The TV's debrid service's limit was reached."),
            of(LinkFailure.Limit(20 * 60_000L)),
        )
        assertEquals("The debrid service’s limit was reached. Try again in about 1 minute.", of(LinkFailure.Limit(20_000L)).reason)
        assertEquals("The debrid service’s limit was reached. Try again in about 2 hours.", of(LinkFailure.Limit(2 * 3_600_000L)).reason)
        assertEquals("The debrid service’s limit was reached. Try again later.", of(LinkFailure.Limit(null)).reason)
        assertEquals(
            failure("TorBox refused your API key. Replace it in Settings › Debrid services.", "The TV's debrid service refused its API key."),
            of(LinkFailure.KeyRefused("TorBox")),
        )
        assertEquals("Premiumize refused your API key. Replace it in Settings › Debrid services.", of(LinkFailure.KeyRefused("Premiumize")).reason)
        assertEquals(
            failure("The debrid service stopped answering. Try again in a few minutes.", "The TV's debrid service stopped answering."),
            of(LinkFailure.Down),
        )
        assertEquals(failure("Too large for your debrid plan.", "Too large for the TV's debrid plan."), of(LinkFailure.Refused(tooLarge = true)))
        assertEquals(failure("The debrid service won’t play this release.", "The TV's debrid service won't play this release."), of(LinkFailure.Refused()))
        val unnamed = listOf(LinkFailure.NoWorkingLink, LinkFailure.NotCached, LinkFailure.Removed, LinkFailure.Limit(60_000), LinkFailure.Down, LinkFailure.Refused(true), LinkFailure.Refused())
        unnamed.forEach { link ->
            listOf("TorBox", "Premiumize").forEach { name -> assertFalse(name in of(link).reason || name in of(link).remote, "$name in $link's words") }
            assertEquals(link == LinkFailure.Removed, of(link).releaseAtFault, "$link")
        }
    }

    /** The player's error, read for its source's failure wherever it sits in the causes, ahead of the code it came with. */
    @Test
    fun `the player's error is read for a debrid link's failure, else a Usenet one, else its code`() {
        assertEquals(PlaybackFailure.of(LinkFailure.NoWorkingLink), PlaybackFailure.of(IOException("load failed", LinkUnavailable(LinkFailure.NoWorkingLink)), io))
        assertEquals(PlaybackFailure.of(LinkFailure.KeyRefused("TorBox")), PlaybackFailure.of(LinkUnavailable(LinkFailure.KeyRefused("TorBox")), PlaybackException.ERROR_CODE_DECODING_FAILED))
        val missing = UsenetDataSourceException(UsenetError.MissingArticle("<a@b>"), null, PlaybackException.ERROR_CODE_IO_FILE_NOT_FOUND)
        assertEquals(PlaybackFailure.of(UsenetError.MissingArticle("<a@b>"), io), PlaybackFailure.of(IOException("load failed", missing), io))
        assertEquals(PlaybackFailure.of(null, PlaybackException.ERROR_CODE_DECODER_INIT_FAILED), PlaybackFailure.of(IOException("plain"), PlaybackException.ERROR_CODE_DECODER_INIT_FAILED))
    }

    @Test
    fun `anything else says that playback failed`() {
        val other = PlaybackFailure.of(null, PlaybackException.ERROR_CODE_UNSPECIFIED)
        assertEquals("Something went wrong while playing.", other.reason)
        assertEquals("Something went wrong while playing on the TV.", other.remote)
        assertFalse(other.releaseAtFault)
        assertEquals(
            PlaybackFailure("Reading the release failed.", releaseAtFault = false, remote = "Reading the release failed on the TV."),
            PlaybackFailure.of(null, PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED),
        )
        // A Usenet failure's own words win over the code it was reported with.
        assertTrue(PlaybackFailure.of(UsenetError.MissingArticle(null), PlaybackException.ERROR_CODE_DECODING_FAILED).reason.startsWith("Part of this release"))
    }
}
