package com.raddstream.app.di

import com.raddstream.app.diagnostics.Diagnostics
import com.raddstream.app.diagnostics.debug.DebugDiagnostics
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/**
 * The diagnostics binding (APP-13, review B-2): Hilt provides the debug
 * implementation.
 *
 * This is the ONLY diagnostics binding, and it exists only in the debug
 * source set: the `src/main` twin was deleted — AGP merges the main and
 * debug source sets for a debug build instead of shadowing same-path
 * files, so two modules at the same path are a duplicate-class compile
 * error (caught by CI round 3). Release has no binding at all, which is
 * safe because the only `@Inject` consumer is the debug-only
 * `DiagnosticsViewModel`. The Application installs the implementation
 * into the bus reflectively, debug builds only.
 */
@Module
@InstallIn(SingletonComponent::class)
object DiagnosticsModule {

    @Provides
    @Singleton
    fun provideDiagnostics(): Diagnostics = DebugDiagnostics
}
