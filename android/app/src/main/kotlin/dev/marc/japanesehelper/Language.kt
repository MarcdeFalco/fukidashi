package dev.marc.japanesehelper

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import dev.marc.japanesehelper.core.text.Lang

/** Langue du contenu pédagogique, alignée sur celle des ressources (values/ ou values-fr/). */
@Composable
fun contentLang(): Lang = langOf(stringResource(R.string.content_lang))

fun Context.contentLang(): Lang = langOf(getString(R.string.content_lang))

private fun langOf(code: String) = if (code == "fr") Lang.FR else Lang.EN
