package com.example.bt_president_game.di

import android.content.Context
import com.example.bt_president_game.data.GameRepository
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object AppModule {
    
    @Provides
    @Singleton
    fun provideGameRepository(): GameRepository {
        return GameRepository()
    }
}
