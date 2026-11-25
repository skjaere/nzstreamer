package app.nzstreamer.plugin.sportarr

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
import java.util.concurrent.CopyOnWriteArrayList

/**
 * The plugin's tests' one slf4j provider (L653; mock-seed's slf4j-simple is left off their classpath for it), registered
 * in META-INF/services: it keeps every line any logger writes in the test's JVM, its words formatted as a real provider
 * would print them, with a throwable's class and message. A test reads what was logged while it ran through [Logged].
 */
class CapturedLogs : SLF4JServiceProvider {
    private val factory = Factory()
    private val markers = BasicMarkerFactory()
    private val mdc = NOPMDCAdapter()

    override fun getLoggerFactory(): ILoggerFactory = factory

    override fun getMarkerFactory(): IMarkerFactory = markers

    override fun getMDCAdapter(): MDCAdapter = mdc

    override fun getRequestedApiVersion(): String = "2.0.99"

    override fun initialize() = Unit

    /** One capturing logger per name. */
    class Factory : ILoggerFactory {
        private val loggers = ConcurrentHashMap<String, Logger>()

        override fun getLogger(name: String): Logger = loggers.getOrPut(name) { Captor(name) }
    }

    private class Captor(private val loggerName: String) : LegacyAbstractLogger() {
        override fun getName(): String = loggerName

        override fun isTraceEnabled() = true

        override fun isDebugEnabled() = true

        override fun isInfoEnabled() = true

        override fun isWarnEnabled() = true

        override fun isErrorEnabled() = true

        override fun getFullyQualifiedCallerName(): String? = null

        override fun handleNormalizedLoggingCall(
            level: Level?,
            marker: Marker?,
            messagePattern: String?,
            arguments: Array<out Any?>?,
            throwable: Throwable?,
        ) {
            val words = MessageFormatter.basicArrayFormat(messagePattern, arguments) +
                (throwable?.let { " ${it.javaClass.name}: ${it.message}" } ?: "")
            Logged.add(Logged.Line(loggerName, level ?: Level.INFO, words))
        }
    }
}

/** Every line logged in this JVM, in order. Test classes run one at a time in a JVM, so a mark and the lines after it are a test's own. */
object Logged {
    data class Line(val logger: String, val level: Level, val words: String)

    private val lines = CopyOnWriteArrayList<Line>()

    internal fun add(line: Line) {
        lines += line
    }

    /** Where the lines stand now. */
    fun mark(): Int = lines.size

    /** The lines the plugin's own loggers wrote since [mark]. */
    fun pluginLinesSince(mark: Int): List<Line> = lines.drop(mark).filter { it.logger.startsWith("app.nzstreamer.plugin.sportarr") }
}
