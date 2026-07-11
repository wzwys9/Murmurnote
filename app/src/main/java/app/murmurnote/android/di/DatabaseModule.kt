package app.murmurnote.android.di

import android.content.Context
import androidx.room.Room
import app.murmurnote.android.data.local.MurmurnoteDatabase
import app.murmurnote.android.data.local.dao.ApiLogDao
import app.murmurnote.android.data.local.dao.CorrectionDao
import app.murmurnote.android.data.local.dao.ItemDao
import app.murmurnote.android.data.local.dao.PersonalCorrectionDao
import app.murmurnote.android.data.local.dao.RecordingDao
import app.murmurnote.android.data.local.dao.TranscriptDao
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
            .addMigrations(
                MurmurnoteDatabase.MIGRATION_1_2,
                MurmurnoteDatabase.MIGRATION_2_3,
                MurmurnoteDatabase.MIGRATION_3_4,
                MurmurnoteDatabase.MIGRATION_4_5,
                MurmurnoteDatabase.MIGRATION_5_6,
                MurmurnoteDatabase.MIGRATION_6_7,
                MurmurnoteDatabase.MIGRATION_7_8,
            )
            .build()
    }

    @Provides fun provideRecordingDao(db: MurmurnoteDatabase): RecordingDao = db.recordingDao()
    @Provides fun provideTranscriptDao(db: MurmurnoteDatabase): TranscriptDao = db.transcriptDao()
    @Provides fun provideCorrectionDao(db: MurmurnoteDatabase): CorrectionDao = db.correctionDao()
    @Provides
    fun providePersonalCorrectionDao(db: MurmurnoteDatabase): PersonalCorrectionDao =
        db.personalCorrectionDao()
    @Provides fun provideItemDao(db: MurmurnoteDatabase): ItemDao = db.itemDao()
    @Provides fun provideApiLogDao(db: MurmurnoteDatabase): ApiLogDao = db.apiLogDao()
}
