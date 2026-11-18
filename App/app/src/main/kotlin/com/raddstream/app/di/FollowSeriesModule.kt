package com.raddstream.app.di

import com.raddstream.app.data.follow.ApiFollowSeriesCatalog
import com.raddstream.app.data.follow.FollowSeriesCatalog
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent

/**
 * The follow check's catalog: the production implementation reads the
 * existing `GET /api/v1/posts` through [ApiFollowSeriesCatalog]; the
 * worker takes the interface, so its test can stand in a fake.
 */
@Module
@InstallIn(SingletonComponent::class)
abstract class FollowSeriesModule {

    @Binds
    abstract fun bindFollowSeriesCatalog(
        impl: ApiFollowSeriesCatalog,
    ): FollowSeriesCatalog
}
