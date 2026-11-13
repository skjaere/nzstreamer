package io.skjaere.usenet.app

import io.skjaere.usenet.app.cast.GoogleCast

/** Every build has Google Cast (C2, L105): the release (Play) build and both betas, as debug and bench. */
val castExtras: CastExtras? = GoogleCast
