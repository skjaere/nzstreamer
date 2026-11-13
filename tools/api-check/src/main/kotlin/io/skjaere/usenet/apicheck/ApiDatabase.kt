package io.skjaere.usenet.apicheck

import java.io.InputStream
import javax.xml.stream.XMLInputFactory
import javax.xml.stream.XMLStreamConstants

/**
 * The SDK's `platforms/android-N/data/api-versions.xml` (the database lint's NewApi uses): the
 * API level at which each public class, method and field appeared. Class names are internal
 * (`java/io/InputStream`), methods are keyed by name + descriptor (`readNBytes([BII)I`), fields by
 * name. The file lists a member only where it is declared and drops overrides, so lookups have
 * to walk [ApiClass.supertypes].
 */
class ApiDatabase(private val classes: Map<String, ApiClass>) {

    data class ApiClass(
        val since: Int,
        val supertypes: List<Supertype>,
        /** API level per member; members without their own `since` inherit the class's. */
        val methods: Map<String, Int>,
        val fields: Map<String, Int>,
    )

    /** An `<extends>` or `<implements>` edge; [since] is when the class gained it. */
    data class Supertype(val name: String, val since: Int)

    operator fun get(className: String): ApiClass? = classes[className]

    companion object {
        fun parse(input: InputStream): ApiDatabase {
            val factory = XMLInputFactory.newInstance().apply {
                setProperty(XMLInputFactory.SUPPORT_DTD, false)
                setProperty(XMLInputFactory.IS_SUPPORTING_EXTERNAL_ENTITIES, false)
            }
            val reader = factory.createXMLStreamReader(input)
            val classes = mutableMapOf<String, ApiClass>()
            var current: ClassBuilder? = null
            fun since(default: Int) = reader.getAttributeValue(null, "since")?.let(::apiLevel) ?: default
            while (reader.hasNext()) {
                val event = reader.next()
                if (event == XMLStreamConstants.START_ELEMENT) {
                    val name = reader.getAttributeValue(null, "name")
                    when (reader.localName) {
                        "class" -> current = ClassBuilder(name, since(1))
                        "extends", "implements" -> current?.supertypes?.add(Supertype(name, since(1)))
                        "method" -> current?.let { it.methods[name] = since(it.since) }
                        "field" -> current?.let { it.fields[name] = since(it.since) }
                    }
                } else if (event == XMLStreamConstants.END_ELEMENT && reader.localName == "class") {
                    current?.let { classes[it.name] = it.build() }
                    current = null
                }
            }
            return ApiDatabase(classes)
        }

        private class ClassBuilder(val name: String, val since: Int) {
            val supertypes = mutableListOf<Supertype>()
            val methods = mutableMapOf<String, Int>()
            val fields = mutableMapOf<String, Int>()
            fun build() = ApiClass(since, supertypes, methods, fields)
        }

        /** `since` is an API level, or `major.minor` for the minor SDK releases from 36.1 on. */
        private fun apiLevel(value: String) = value.substringBefore('.').toInt()
    }
}
