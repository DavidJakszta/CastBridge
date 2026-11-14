package dev.castbridge.core.discovery.roku

import java.io.StringReader
import javax.xml.parsers.DocumentBuilderFactory
import org.w3c.dom.Element
import org.xml.sax.InputSource

/** The subset of `/query/device-info` used by discovery and the device details screen. */
data class RokuDeviceInfo(
    val friendlyName: String? = null,
    val modelName: String? = null,
    val serialNumber: String? = null,
    val vendorName: String? = null,
    val deviceId: String? = null,
    val udn: String? = null,
)

object RokuDeviceInfoParser {
    private const val MAX_DOCUMENT_CHARS = 128 * 1024

    fun parse(xml: String): RokuDeviceInfo? {
        if (xml.isBlank() || xml.length > MAX_DOCUMENT_CHARS) return null

        val factory = secureFactory()
        val document = runCatching {
            factory.newDocumentBuilder().parse(InputSource(StringReader(xml)))
        }.getOrNull() ?: return null

        val root = document.documentElement as? Element ?: return null
        if (!root.nodeName.equals("device-info", ignoreCase = true)) return null

        fun text(tag: String): String? = root
            .getElementsByTagName(tag)
            .item(0)
            ?.textContent
            ?.trim()
            ?.takeIf { it.isNotEmpty() }

        fun firstText(vararg tags: String): String? = tags.firstNotNullOfOrNull(::text)

        return RokuDeviceInfo(
            friendlyName = firstText("friendly-device-name", "user-device-name", "default-device-name"),
            modelName = text("model-name"),
            serialNumber = text("serial-number"),
            vendorName = text("vendor-name"),
            deviceId = text("device-id"),
            udn = text("udn"),
        )
    }

    private fun secureFactory(): DocumentBuilderFactory {
        val factory = DocumentBuilderFactory.newInstance()
        factory.isNamespaceAware = false
        // Android's built-in parser reports an unknown spec version and throws for some of these
        // setters, so hardening is applied best-effort. The secure defaults are false already.
        runCatching { factory.isXIncludeAware = false }
        runCatching { factory.isExpandEntityReferences = false }

        listOf(
            "http://apache.org/xml/features/disallow-doctype-decl" to true,
            "http://xml.org/sax/features/external-general-entities" to false,
            "http://xml.org/sax/features/external-parameter-entities" to false,
            "http://apache.org/xml/features/nonvalidating/load-external-dtd" to false,
        ).forEach { (feature, value) ->
            runCatching { factory.setFeature(feature, value) }
        }

        return factory
    }
}
