package io.skjaere.usenet.logging

import android.util.Log
import org.slf4j.ILoggerFactory
import org.slf4j.IMarkerFactory
import org.slf4j.Logger
import org.slf4j.Marker
import org.slf4j.event.Level
import org.slf4j.helpers.BasicMarkerFactory
import org.slf4j.helpers.LegacyAbstractLogger
import org.slf4j.helpers.MessageFormatter
import org.slf4j.helpers.NOPMDCAdapter
import org.slf4j.spi.MDCAdapter
import org.slf4j.spi.SLF4JServiceProvider
import java.util.concurrent.ConcurrentHashMap

/** Runtime knobs for library logging: what is logged, to logcat and [LogBuffer] alike. */
object AndroidLogConfig {
    @Volatile var minLevel: Level = Level.INFO

    /** Settings › Logs' "Detailed logging": DEBUG lines too ([minLevel] DEBUG), else INFO and up. */
    var detailed: Boolean
        get() = minLevel.toInt() <= Level.DEBUG.toInt()
        set(on) {
            minLevel = if (on) Level.DEBUG else Level.INFO
        }

    internal val systemLog: (priority: Int, tag: String, message: String) -> Unit = { priority, tag, message -> Log.println(priority, tag, message) }

    /** Where lines go besides [LogBuffer]: logcat, or what a JVM test captures. */
    @Volatile internal var logcat: (priority: Int, tag: String, message: String) -> Unit = systemLog
}

/** slf4j 2.x binding (ServiceLoader-based); `slf4j-android` 1.7 cannot bind 2.x. */
class AndroidSlf4jProvider : SLF4JServiceProvider {
    private lateinit var loggerFactory: ILoggerFactory
    private val markerFactory = BasicMarkerFactory()
    private val mdcAdapter = NOPMDCAdapter()

    override fun getLoggerFactory() = loggerFactory
    override fun getMarkerFactory(): IMarkerFactory = markerFactory
    override fun getMDCAdapter(): MDCAdapter = mdcAdapter
    override fun getRequestedApiVersion() = "2.0.99"
    override fun initialize() {
        loggerFactory = AndroidLoggerFactory()
    }
}

private class AndroidLoggerFactory : ILoggerFactory {
    private val loggers = ConcurrentHashMap<String, Logger>()
    override fun getLogger(name: String): Logger = loggers.getOrPut(name) { AndroidLogger(name) }
}

private class AndroidLogger(private val loggerName: String) : LegacyAbstractLogger() {
    private val tag = loggerName.substringAfterLast('.').ifEmpty { "slf4j" }

    init {
        name = loggerName
    }

    private fun enabled(level: Level) = level.toInt() >= AndroidLogConfig.minLevel.toInt()

    override fun isTraceEnabled() = enabled(Level.TRACE)
    override fun isDebugEnabled() = enabled(Level.DEBUG)
    override fun isInfoEnabled() = enabled(Level.INFO)
    override fun isWarnEnabled() = enabled(Level.WARN)
    override fun isErrorEnabled() = enabled(Level.ERROR)
    override fun getFullyQualifiedCallerName(): String? = null

    /** Every line, the stack trace with it, is redacted ([redactSecrets]) before it reaches logcat or [LogBuffer]. */
    override fun handleNormalizedLoggingCall(
        level: Level, marker: Marker?, messagePattern: String?, arguments: Array<out Any?>?, throwable: Throwable?,
    ) {
        if (!enabled(level)) return
        val message = redactSecrets(MessageFormatter.basicArrayFormat(messagePattern, arguments) ?: "")
        val priority = when (level) {
            Level.TRACE -> Log.VERBOSE
            Level.DEBUG -> Log.DEBUG
            Level.INFO -> Log.INFO
            Level.WARN -> Log.WARN
            Level.ERROR -> Log.ERROR
        }
        val full = if (throwable != null) "$message\n${redactSecrets(throwable.stackTraceToString())}" else message
        AndroidLogConfig.logcat(priority, tag, full)
        val summary = throwable?.let { message + redactSecrets(" (${it::class.java.simpleName}: ${it.message})") } ?: message
        LogBuffer.add(LogLine(System.currentTimeMillis(), level, tag, summary))
    }
}
