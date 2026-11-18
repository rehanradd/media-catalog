package com.raddstream.app.di

import com.raddstream.app.work.ApiCatalogPreloadCatalog
import com.raddstream.app.work.CatalogPreloadCatalog
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent

/**
 * The preload workers' catalog: the production implementation reads the
 * existing repository through [ApiCatalogPreloadCatalog]; the workers take
 * the interface, so their tests can stand in a fake.
 */
@Module
@InstallIn(SingletonComponent::class)
abstract class CatalogPreloadModule {

    @Binds
    abstract fun bindCatalogPreloadCatalog(
        impl: ApiCatalogPreloadCatalog,
    ): CatalogPreloadCatalog
}
