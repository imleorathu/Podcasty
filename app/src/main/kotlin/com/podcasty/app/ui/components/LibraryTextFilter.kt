/*
 * Podcasty — Android Podcast Player
 * Adapted from Airmedy (https://github.com/misa198/airmedy), licensed under GNU GPL v3.0.
 */
package com.podcasty.app.ui.components

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.podcasty.app.R

@Composable
fun LibraryTextFilter(
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String = stringResource(R.string.filter_placeholder_search),
    showPlaceholderAndLeadingSymbol: Boolean = true,
    onSubmit: () -> Unit = {},
    modifier: Modifier = Modifier,
) {
    var inputValue by rememberSaveable { mutableStateOf(value) }
    LaunchedEffect(value) {
        if (value != inputValue) inputValue = value
    }
    PodcastyTextField(
        value = inputValue,
        onValueChange = { newValue ->
            inputValue = newValue
            onValueChange(newValue)
        },
        placeholder = placeholder,
        size = PodcastyTextFieldSize.Medium,
        leadingSymbol = MaterialSymbols.Search,
        showPlaceholderAndLeadingSymbol = showPlaceholderAndLeadingSymbol,
        onDone = onSubmit,
        modifier = modifier
            .fillMaxWidth()
            .padding(start = 24.dp, top = 4.dp, end = 24.dp, bottom = 10.dp),
    )
}
