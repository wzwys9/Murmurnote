package app.murmurnote.android.di

import android.content.Context
import androidx.room.Room
import app.murmurnote.android.data.local.MurmurnoteDatabase
import app.murmurnote.android.data.local.dao.ApiLogDao
import app.murmurnote.android.data.local.dao.ItemDao
import app.murmurnote.android.data.local.dao.RecordingDao
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object DatabaseModule {

    @Provides
    @Singleton
    fun provideDatabase(@ApplicationContext context: Context): MurmurnoteDatabase {
        return Room.databaseBuilder(
            context,
            MurmurnoteDatabase::class.java,
            MurmurnoteDatabase.DB_NAME
        )
            .fallbackToDestructiveMigration(false)
            .addMigrations(
                MurmurnoteDatabase.MIGRATION_1_2,
                MurmurnoteDatabase.MIGRATION_2_3,
                MurmurnoteDatabase.MIGRATION_3_4
            )
            .build()
    }

    @Provides fun provideRecordingDao(db: MurmurnoteDatabase): RecordingDao = db.recordingDao()
    @Provides fun provideItemDao(db: MurmurnoteDatabase): ItemDao = db.itemDao()
    @Provides fun provideApiLogDao(db: MurmurnoteDatabase): ApiLogDao = db.apiLogDao()
}
