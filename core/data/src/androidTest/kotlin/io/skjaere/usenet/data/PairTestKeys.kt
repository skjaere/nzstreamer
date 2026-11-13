package io.skjaere.usenet.data

import io.skjaere.usenet.link.LinkKey
import io.skjaere.usenet.link.SelfSigned
import java.security.KeyFactory
import java.security.KeyPair
import java.security.spec.PKCS8EncodedKeySpec
import java.security.spec.X509EncodedKeySpec
import java.util.Base64

/**
 * The two-emulator run's keys (emu-link-pair.sh, L14): a TV's and a phone's, fixed so that each side knows the other's
 * before they meet, as a pairing would have it, and the phone can find the TV's record by its `id`. Software P-256 keys
 * made once with openssl for these tests alone: they guard nothing. Only one pair run goes at a time (it holds tv36's
 * and phone34's locks), so no other run advertises this id.
 */
internal object PairTestKeys {
    fun tv(): LinkKey = key(TV_PKCS8, TV_SPKI)

    fun phone(): LinkKey = key(PHONE_PKCS8, PHONE_SPKI)

    private fun key(pkcs8: String, spki: String): LinkKey {
        val factory = KeyFactory.getInstance("EC")
        val private = factory.generatePrivate(PKCS8EncodedKeySpec(Base64.getDecoder().decode(pkcs8)))
        val public = factory.generatePublic(X509EncodedKeySpec(Base64.getDecoder().decode(spki)))
        return LinkKey(private, SelfSigned.certificate(KeyPair(public, private)))
    }

    private const val TV_PKCS8 = "MIGHAgEAMBMGByqGSM49AgEGCCqGSM49AwEHBG0wawIBAQQgUSEcE+G0ebiUvyPR1nrQ/4JhdKRjo53ic0JAFDd11vOhRANCAATRT7YP7PfD1b3wdxVViPnHckAKdrJgfGH4J59o4lTcNESE2KFJ0JXWUsQy02tbl//li++tNZ5laVo/ie1D10xr"
    private const val TV_SPKI = "MFkwEwYHKoZIzj0CAQYIKoZIzj0DAQcDQgAE0U+2D+z3w9W98HcVVYj5x3JACnayYHxh+CefaOJU3DREhNihSdCV1lLEMtNrW5f/5YvvrTWeZWlaP4ntQ9dMaw=="
    private const val PHONE_PKCS8 = "MIGHAgEAMBMGByqGSM49AgEGCCqGSM49AwEHBG0wawIBAQQghzw2HtFjRmZbAc+jBfUE4hTZWpCawHcuTQB6PsGlXe2hRANCAARZnuNQRGw5aLg7P/SoFoUPDecr0t8/ZYfUsCZbc3JQ7PbHkTKePR0Nq8cNoK4eLX71Prm75pxyd8x5tsAWN9pA"
    private const val PHONE_SPKI = "MFkwEwYHKoZIzj0CAQYIKoZIzj0DAQcDQgAEWZ7jUERsOWi4Oz/0qBaFDw3nK9LfP2WH1LAmW3NyUOz2x5Eynj0dDavHDaCuHi1+9T65u+accnfMebbAFjfaQA=="
}
