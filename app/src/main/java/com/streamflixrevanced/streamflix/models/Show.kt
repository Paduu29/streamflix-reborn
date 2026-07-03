package com.streamflixrevanced.streamflix.models

import com.streamflixrevanced.streamflix.adapters.AppAdapter

sealed interface Show : AppAdapter.Item {
    var isFavorite: Boolean
}
