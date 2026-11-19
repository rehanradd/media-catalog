package com.raddstream.app.di

import com.raddstream.app.data.local.LocalFilesRepository
import com.raddstream.app.data.local.LocalVideosDataSource
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent

/**
 * The phone's own videos: the production implementation reads MediaStore
 * through [LocalFilesRepository]; the ViewModel takes the interface, so its
 * state tests can stand in a fake.
 */
@Module
@InstallIn(SingletonComponent::class)
abstract class LocalVideosModule {

    @Binds
    abstract fun bindLocalVideosDataSource(
        impl: LocalFilesRepository,
    ): LocalVideosDataSource
}
