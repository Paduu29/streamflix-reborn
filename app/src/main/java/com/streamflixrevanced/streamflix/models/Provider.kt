package com.streamflixrevanced.streamflix.models

import com.streamflixrevanced.streamflix.adapters.AppAdapter

open class Provider(
    val name: String,
    val logo: String,
    val language: String,

    val provider: com.streamflixrevanced.streamflix.providers.Provider,
    var isFavorite: Boolean = false,
) : AppAdapter.Item {


    override lateinit var itemType: AppAdapter.Type
}