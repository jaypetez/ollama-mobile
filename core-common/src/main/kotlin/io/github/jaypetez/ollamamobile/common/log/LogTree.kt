package io.github.jaypetez.ollamamobile.common.log

import android.content.Context
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import java.io.File
import javax.inject.Singleton
import timber.log.Timber

/**
 * The Timber tree that feeds both the in-memory [LogRing] and the on-disk
 * [FileLogSink].
 *
 * One tree rather than two: Timber calls every planted tree for every log, so
 * two trees would double the string formatting Timber has already done. The
 * `LogRecord` is built once here and handed to both sinks.
 *
 * This tree does not write to logcat. `Timber.DebugTree` does that and is
 * planted alongside this one in debug builds only — production logs stay inside
 * the app, where the user can see and export them, rather than in a system
 * buffer any other app with the right permission could read.
 */
class LogTree(
    private val ring: LogRing,
    private val fileSink: FileLogSink? = null,
    private val minimumLevel: LogLevel = LogLevel.DEBUG,
    private val clock: () -> Long = System::currentTimeMillis,
) : Timber.Tree() {
    override fun isLoggable(tag: String?, priority: Int): Boolean = priority >= minimumLevel.priority

    override fun log(priority: Int, tag: String?, message: String, t: Throwable?) {
        val record = LogRecord(
            timestampMillis = clock(),
            level = LogLevel.fromPriority(priority),
            tag = tag,
            message = message,
            throwable = t,
        )
        ring.add(record)
        fileSink?.write(record)
    }
}

@Module
@InstallIn(SingletonComponent::class)
object LoggingModule {
    @Provides
    @Singleton
    fun provideLogRing(): LogRing = LogRing()

    @Provides
    @Singleton
    fun provideFileLogSink(
        @ApplicationContext context: Context,
    ): FileLogSink = FileLogSink(File(context.filesDir, LOG_DIRECTORY))

    @Provides
    @Singleton
    fun provideLogTree(ring: LogRing, fileSink: FileLogSink): LogTree = LogTree(ring, fileSink)

    private const val LOG_DIRECTORY = "logs"
}
