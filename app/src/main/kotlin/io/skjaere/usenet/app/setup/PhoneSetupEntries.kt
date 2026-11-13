package io.skjaere.usenet.app.setup

import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import io.skjaere.usenet.app.ui.FormFactor
import io.skjaere.usenet.app.ui.GroupCard
import io.skjaere.usenet.app.ui.LocalFormFactor
import io.skjaere.usenet.app.ui.NavRow

/** The status line of "Set up with your phone" under a form's typing tip: the page holds every source, not just this one. */
const val PHONE_SETUP_FORM_ROW = "Or set up every source from your phone"

/**
 * A form's way to "Set up with your phone", under its typing tip ([onOpen]; nothing without it, and nothing on a phone,
 * whose keyboard is at hand), titled [title] (a debrid service's key form: "Easier: …", DbTvSignIn 2). The page is the
 * same as from anywhere else.
 */
@Composable
fun FormPhoneSetupRow(onOpen: (() -> Unit)?, title: String = PHONE_SETUP_TITLE) {
    if (onOpen == null || LocalFormFactor.current != FormFactor.Tv) return
    GroupCard(Modifier.padding(bottom = 14.dp)) { NavRow(title, PHONE_SETUP_FORM_ROW, onClick = onOpen) }
}
