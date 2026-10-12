package io.github.sunflower2333.dsh

internal enum class MobileOperation {
    STATUS, OBSERVE, CLICK, TYPE, SWIPE, SCROLL, BACK, STOP, LIST_APPS, OPEN_APP,
    ADB_STATUS, ADB_SHELL, ROOT_SHELL, UI_XML
}

internal sealed interface MobileCommand {
    object Status : MobileCommand

    data class Observe(val sessionId: String, val screenshot: Boolean) : MobileCommand

    data class Click(
        val sessionId: String,
        val observationId: String,
        val nodeId: String?,
        val x: Double?,
        val y: Double?
    ) : MobileCommand

    data class Type(
        val sessionId: String,
        val observationId: String,
        val nodeId: String,
        val text: String
    ) : MobileCommand

    data class Swipe(
        val sessionId: String,
        val observationId: String,
        val fromX: Double,
        val fromY: Double,
        val toX: Double,
        val toY: Double,
        val durationMs: Long
    ) : MobileCommand

    data class Back(val sessionId: String, val observationId: String) : MobileCommand
    data class Scroll(val sessionId: String, val observationId: String, val nodeId: String,
        val direction: String) : MobileCommand
    data class ListApps(val sessionId: String) : MobileCommand
    data class OpenApp(val sessionId: String, val observationId: String?, val packageName: String) : MobileCommand
    object AdbStatus : MobileCommand
    data class AdbShell(val sessionId: String, val serial: String?, val argv: List<String>, val timeoutMs: Long) : MobileCommand
    data class RootShell(val sessionId: String, val argv: List<String>, val timeoutMs: Long) : MobileCommand
    data class UiXml(val sessionId: String, val source: String, val serial: String?) : MobileCommand

    object Stop : MobileCommand
}

internal class MobileProtocolException(val code: String, message: String) :
    IllegalArgumentException(message)

/** Validates decoded JSON values without coercing strings, booleans or nulls. */
internal object MobileProtocol {
    private const val PATH_PREFIX = "/v1/mobile/"
    private const val MAX_COORDINATE = 100_000.0
    private const val MAX_TEXT_LENGTH = 4096
    private val identifier = Regex("[A-Za-z0-9._:-]{1,128}")
    private val forbiddenShellCharacters = Regex("[;&|$`()<>\\\"'\\\\\\u0000-\\r\\n]")
    private val sessionFields = setOf("sessionId", "observationId")

    fun operation(path: String): MobileOperation = when (path) {
        "${PATH_PREFIX}status" -> MobileOperation.STATUS
        "${PATH_PREFIX}observe" -> MobileOperation.OBSERVE
        "${PATH_PREFIX}click" -> MobileOperation.CLICK
        "${PATH_PREFIX}type" -> MobileOperation.TYPE
        "${PATH_PREFIX}swipe" -> MobileOperation.SWIPE
        "${PATH_PREFIX}scroll" -> MobileOperation.SCROLL
        "${PATH_PREFIX}back" -> MobileOperation.BACK
        "${PATH_PREFIX}stop" -> MobileOperation.STOP
        "${PATH_PREFIX}list_apps" -> MobileOperation.LIST_APPS
        "${PATH_PREFIX}open_app" -> MobileOperation.OPEN_APP
        "${PATH_PREFIX}adb_status" -> MobileOperation.ADB_STATUS
        "${PATH_PREFIX}adb_shell" -> MobileOperation.ADB_SHELL
        "${PATH_PREFIX}root_shell" -> MobileOperation.ROOT_SHELL
        "${PATH_PREFIX}ui_xml" -> MobileOperation.UI_XML
        else -> throw MobileProtocolException("unknown_operation", "Unknown mobile operation.")
    }

    fun parse(operation: MobileOperation, fields: Map<String, Any?>): MobileCommand {
        val allowedFields = when (operation) {
            MobileOperation.STATUS, MobileOperation.STOP -> emptySet()
            MobileOperation.OBSERVE -> setOf("sessionId", "screenshot")
            MobileOperation.CLICK -> sessionFields + setOf("nodeId", "x", "y")
            MobileOperation.TYPE -> sessionFields + setOf("nodeId", "text")
            MobileOperation.SWIPE -> sessionFields +
                setOf("fromX", "fromY", "toX", "toY", "durationMs")
            MobileOperation.SCROLL -> sessionFields + setOf("nodeId", "direction")
            MobileOperation.BACK -> sessionFields
            MobileOperation.LIST_APPS -> setOf("sessionId")
            MobileOperation.OPEN_APP -> sessionFields + "packageName"
            MobileOperation.ADB_STATUS -> emptySet()
            MobileOperation.ADB_SHELL -> setOf("sessionId", "serial", "argv", "timeoutMs")
            MobileOperation.ROOT_SHELL -> setOf("sessionId", "argv", "timeoutMs")
            MobileOperation.UI_XML -> setOf("sessionId", "source", "serial")
        }
        if (fields.keys.any { it !in allowedFields }) {
            invalid("Request contains unknown fields.")
        }

        return when (operation) {
            MobileOperation.STATUS -> MobileCommand.Status
            MobileOperation.STOP -> MobileCommand.Stop
            MobileOperation.OBSERVE -> MobileCommand.Observe(
                requireIdentifier(fields, "sessionId"),
                if (fields.containsKey("screenshot")) {
                    fields["screenshot"] as? Boolean
                        ?: invalid("screenshot must be a boolean.")
                } else {
                    false
                }
            )
            MobileOperation.CLICK -> parseClick(fields)
            MobileOperation.TYPE -> MobileCommand.Type(
                requireIdentifier(fields, "sessionId"),
                requireIdentifier(fields, "observationId"),
                requireIdentifier(fields, "nodeId"),
                requireText(fields)
            )
            MobileOperation.SWIPE -> MobileCommand.Swipe(
                requireIdentifier(fields, "sessionId"),
                requireIdentifier(fields, "observationId"),
                requireCoordinate(fields, "fromX"),
                requireCoordinate(fields, "fromY"),
                requireCoordinate(fields, "toX"),
                requireCoordinate(fields, "toY"),
                requireDuration(fields)
            )
            MobileOperation.BACK -> MobileCommand.Back(
                requireIdentifier(fields, "sessionId"),
                requireIdentifier(fields, "observationId")
            )
            MobileOperation.SCROLL -> MobileCommand.Scroll(requireIdentifier(fields, "sessionId"),
                requireIdentifier(fields, "observationId"), requireIdentifier(fields, "nodeId"),
                (fields["direction"] as? String)?.takeIf { it == "forward" || it == "backward" }
                    ?: invalid("direction must be forward or backward."))
            MobileOperation.LIST_APPS -> MobileCommand.ListApps(requireIdentifier(fields, "sessionId"))
            MobileOperation.OPEN_APP -> MobileCommand.OpenApp(requireIdentifier(fields, "sessionId"),
                if (fields.containsKey("observationId")) requireIdentifier(fields, "observationId") else null,
                MobileLauncherPolicy.requirePackage(fields["packageName"]))
            MobileOperation.ADB_STATUS -> MobileCommand.AdbStatus
            MobileOperation.ADB_SHELL -> MobileCommand.AdbShell(
                requireIdentifier(fields, "sessionId"), optionalIdentifier(fields, "serial"),
                requireArgv(fields), requireShellTimeout(fields)
            )
            MobileOperation.ROOT_SHELL -> MobileCommand.RootShell(
                requireIdentifier(fields, "sessionId"), requireArgv(fields), requireShellTimeout(fields)
            )
            MobileOperation.UI_XML -> {
                val source = fields["source"] as? String
                    ?: invalid("source must be accessibility_xml or adb_uiautomator.")
                if (source != "accessibility_xml" && source != "adb_uiautomator")
                    invalid("source must be accessibility_xml or adb_uiautomator.")
                val serial = optionalIdentifier(fields, "serial")
                if (serial != null && source != "adb_uiautomator") invalid("serial is only valid for adb_uiautomator.")
                MobileCommand.UiXml(requireIdentifier(fields, "sessionId"), source, serial)
            }
        }
    }

    /** The right and bottom display edges are outside the touchable rectangle. */
    fun requireCoordinates(x: Double, y: Double, width: Int, height: Int) {
        if (width <= 0 || height <= 0 || !validCoordinate(x) || !validCoordinate(y) ||
            x >= width.toDouble() || y >= height.toDouble()
        ) {
            invalid("Coordinates must be within the current display.")
        }
    }

    private fun parseClick(fields: Map<String, Any?>): MobileCommand.Click {
        val sessionId = requireIdentifier(fields, "sessionId")
        val observationId = requireIdentifier(fields, "observationId")
        val hasNode = fields.containsKey("nodeId")
        val hasX = fields.containsKey("x")
        val hasY = fields.containsKey("y")
        if ((hasNode && (hasX || hasY)) || (!hasNode && !(hasX && hasY))) {
            invalid("Click requires either nodeId or both coordinates.")
        }
        return if (hasNode) {
            MobileCommand.Click(
                sessionId, observationId, requireIdentifier(fields, "nodeId"), null, null
            )
        } else {
            MobileCommand.Click(
                sessionId, observationId, null,
                requireCoordinate(fields, "x"), requireCoordinate(fields, "y")
            )
        }
    }

    private fun requireIdentifier(fields: Map<String, Any?>, name: String): String {
        val value = fields[name] as? String ?: invalid("$name must be an identifier string.")
        if (!identifier.matches(value)) invalid("$name must be a valid identifier.")
        return value
    }

    private fun requireCoordinate(fields: Map<String, Any?>, name: String): Double {
        val value = number(fields[name]) ?: invalid("$name must be a finite number.")
        if (!validCoordinate(value)) invalid("$name is outside the coordinate range.")
        return value
    }

    private fun validCoordinate(value: Double): Boolean =
        value.isFinite() && value >= 0.0 && value <= MAX_COORDINATE

    private fun number(value: Any?): Double? = when (value) {
        is Byte -> value.toDouble()
        is Short -> value.toDouble()
        is Int -> value.toDouble()
        is Long -> value.toDouble()
        is Float -> value.toDouble()
        is Double -> value
        else -> null
    }

    private fun requireDuration(fields: Map<String, Any?>): Long {
        if (!fields.containsKey("durationMs")) return 300L
        val value = number(fields["durationMs"])
            ?: invalid("durationMs must be an integer number.")
        if (!value.isFinite() || value < 100.0 || value > 1000.0 || value % 1.0 != 0.0) {
            invalid("durationMs must be an integer from 100 to 1000.")
        }
        return value.toLong()
    }

    private fun optionalIdentifier(fields: Map<String, Any?>, name: String): String? {
        if (!fields.containsKey(name)) return null
        return requireIdentifier(fields, name)
    }

    private fun requireArgv(fields: Map<String, Any?>): List<String> {
        val value = fields["argv"] as? List<*> ?: invalid("argv must be an array of strings.")
        if (value.isEmpty() || value.size > AndroidCapabilityManager.MAX_ARGV) invalid("argv must contain 1 to 32 strings.")
        return value.map {
            val item = it as? String ?: invalid("argv must contain only strings.")
            if (item.isEmpty() || item.length > AndroidCapabilityManager.MAX_ARG_LENGTH ||
                forbiddenShellCharacters.containsMatchIn(item))
                invalid("argv contains an invalid argument.")
            item
        }
    }

    private fun requireShellTimeout(fields: Map<String, Any?>): Long {
        if (!fields.containsKey("timeoutMs")) return 5_000L
        val value = number(fields["timeoutMs"]) ?: invalid("timeoutMs must be an integer number.")
        if (!value.isFinite() || value % 1.0 != 0.0 || value < AndroidCapabilityManager.MIN_TIMEOUT_MS ||
            value > AndroidCapabilityManager.MAX_TIMEOUT_MS) invalid("timeoutMs must be an integer from 100 to 15000.")
        return value.toLong()
    }

    private fun requireText(fields: Map<String, Any?>): String {
        val value = fields["text"] as? String ?: invalid("text must be a string.")
        if (value.length > MAX_TEXT_LENGTH || '\u0000' in value || !wellFormedUnicode(value)) {
            invalid("text must contain at most 4096 valid characters without NUL.")
        }
        return value
    }

    private fun wellFormedUnicode(value: String): Boolean {
        var index = 0
        while (index < value.length) {
            val character = value[index]
            if (character.isHighSurrogate()) {
                if (index + 1 >= value.length || !value[index + 1].isLowSurrogate()) return false
                index += 2
            } else {
                if (character.isLowSurrogate()) return false
                index++
            }
        }
        return true
    }

    private fun invalid(message: String): Nothing =
        throw MobileProtocolException("invalid_request", message)
}
