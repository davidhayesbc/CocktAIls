package com.davidhayesbc.cocktails.android.product

import android.content.Context
import com.davidhayesbc.cocktails.core.data.LocalCocktailRepository

internal object BundledReferenceProducts {
    fun install(context: Context, repository: LocalCocktailRepository) {
        if (repository.referenceCatalogueIsAtLeast("bc-liquor-products", "2026-07", 1)) return
        if (context.assets.list("bc-products")?.contains("products.json") != true) return
        context.assets.open("bc-products/products.json").bufferedReader().use {
            repository.installReferenceProducts(it.readText())
        }
    }
}
