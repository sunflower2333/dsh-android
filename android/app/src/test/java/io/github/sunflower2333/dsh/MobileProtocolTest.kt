package io.github.sunflower2333.dsh

import java.math.BigDecimal
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.fail
import org.junit.Test

class MobileProtocolTest {
    private val session = "session-17_4:local"
    private val observation = "observation.42"
    private val base: Map<String, Any?> = mapOf(
        "sessionId" to session,
        "observationId" to observation
    )

    @Test fun acceptsOnlyExactVersionedMobilePaths() {
        MobileOperation.entries.forEach { operation ->
            assertEquals(operation, MobileProtocol.operation("/v1/mobile/${operation.name.lowercase()}"))
        }
        listOf(
            "/status", "/v1/mobile/status/", "/v1/mobile/STATUS",
            "/v1/mobile/status?sessionId=ignored", "/v1/mobile/../status",
            "/v1/mobile/start", "/v1/mobile/key", "/v1/mobile/%73tatus"
        ).forEach { path ->
            expectFailure("unknown_operation") { MobileProtocol.operation(path) }
        }
    }

    @Test fun statusAndStopAcceptOnlyEmptyObjects() {
        assertSame(MobileCommand.Status, MobileProtocol.parse(MobileOperation.STATUS, emptyMap()))
        assertSame(MobileCommand.Stop, MobileProtocol.parse(MobileOperation.STOP, emptyMap()))
        listOf(MobileOperation.STATUS, MobileOperation.STOP).forEach { operation ->
            expectFailure { MobileProtocol.parse(operation, mapOf("sessionId" to session)) }
            expectFailure { MobileProtocol.parse(operation, mapOf("force" to true)) }
        }
    }

    @Test fun capabilityOperationsUseStrictBoundedSchemas() {
        assertSame(MobileCommand.AdbStatus, MobileProtocol.parse(MobileOperation.ADB_STATUS, emptyMap()))
        assertEquals(MobileCommand.AdbShell(session, "usb-1", listOf("shell", "id"), 100L),
            MobileProtocol.parse(MobileOperation.ADB_SHELL, mapOf("sessionId" to session, "serial" to "usb-1", "argv" to listOf("shell", "id"), "timeoutMs" to 100)))
        assertEquals(MobileCommand.RootShell(session, listOf("id", "-u"), 5_000L),
            MobileProtocol.parse(MobileOperation.ROOT_SHELL, mapOf("sessionId" to session, "argv" to listOf("id", "-u"))))
        assertEquals(MobileCommand.UiXml(session, "accessibility_xml", null),
            MobileProtocol.parse(MobileOperation.UI_XML, mapOf("sessionId" to session, "source" to "accessibility_xml")))
        assertEquals(MobileCommand.UiXml(session, "adb_uiautomator", "usb-1"),
            MobileProtocol.parse(MobileOperation.UI_XML, mapOf("sessionId" to session, "source" to "adb_uiautomator", "serial" to "usb-1")))
        listOf(
            mapOf("sessionId" to session, "argv" to emptyList<String>()),
            mapOf("sessionId" to session, "argv" to listOf("ok", 2)),
            mapOf("sessionId" to session, "argv" to List(33) { "arg" }),
            mapOf("sessionId" to session, "argv" to listOf("x".repeat(4097))),
            mapOf("sessionId" to session, "serial" to "bad serial", "argv" to listOf("id")),
            mapOf("sessionId" to session, "serial" to null, "argv" to listOf("id")),
            mapOf("sessionId" to session, "argv" to listOf("id;whoami")),
            mapOf("sessionId" to session, "argv" to listOf("id\\\nwhoami")),
            mapOf("sessionId" to session, "argv" to listOf("id"), "timeoutMs" to 99),
            mapOf("sessionId" to session, "argv" to listOf("id"), "timeoutMs" to 100.5),
            mapOf("sessionId" to session, "source" to "accessibility_xml", "serial" to "usb-1"),
        ).forEach { fields ->
            expectFailure { MobileProtocol.parse(if (fields.containsKey("source")) MobileOperation.UI_XML else MobileOperation.ADB_SHELL, fields) }
        }
    }

    @Test fun observeDefaultsToAccessibilityTextWithoutCoercingScreenshotConsent() {
        assertEquals(
            MobileCommand.Observe(session, false),
            MobileProtocol.parse(MobileOperation.OBSERVE, mapOf("sessionId" to session))
        )
        assertEquals(
            MobileCommand.Observe(session, false),
            MobileProtocol.parse(MobileOperation.OBSERVE, mapOf("sessionId" to session, "screenshot" to false))
        )
        assertEquals(MobileCommand.Observe(session, true),
            MobileProtocol.parse(MobileOperation.OBSERVE, mapOf("sessionId" to session, "screenshot" to true)))
        listOf(null, "true", 1, emptyList<Any>()).forEach { value ->
            expectFailure {
                MobileProtocol.parse(MobileOperation.OBSERVE, mapOf("sessionId" to session, "screenshot" to value))
            }
        }
    }

    @Test fun requiresConservativeBoundedSessionAndObservationIdentifiers() {
        listOf<Any?>(null, true, 42, "", "id with spaces", "id/other", "id\n", "中文", "x".repeat(129))
            .forEach { value ->
                expectFailure {
                    MobileProtocol.parse(MobileOperation.OBSERVE, mapOf("sessionId" to value))
                }
                expectFailure {
                    MobileProtocol.parse(MobileOperation.BACK, base + ("observationId" to value))
                }
            }
        expectFailure { MobileProtocol.parse(MobileOperation.OBSERVE, emptyMap()) }
        expectFailure { MobileProtocol.parse(MobileOperation.BACK, mapOf("sessionId" to session)) }
        assertEquals(
            MobileCommand.Back("x".repeat(128), "a._:-0"),
            MobileProtocol.parse(MobileOperation.BACK, mapOf("sessionId" to "x".repeat(128), "observationId" to "a._:-0"))
        )
    }

    @Test fun everyOperationRejectsUnknownFieldsBeforePerformingAnAction() {
        MobileOperation.entries.forEach { operation ->
            expectFailure {
                MobileProtocol.parse(operation, validFields(operation) + ("arbitraryAction" to "launch"))
            }
        }
        expectFailure {
            MobileProtocol.parse(MobileOperation.TYPE, validFields(MobileOperation.TYPE) + ("append" to true))
        }
    }

    @Test fun clickAcceptsExactlyOneOfNodeOrCoordinateTarget() {
        assertEquals(
            MobileCommand.Click(session, observation, "node:2", null, null),
            MobileProtocol.parse(MobileOperation.CLICK, base + ("nodeId" to "node:2"))
        )
        assertEquals(
            MobileCommand.Click(session, observation, null, 0.0, 100_000.0),
            MobileProtocol.parse(MobileOperation.CLICK, base + mapOf("x" to 0, "y" to 100_000L))
        )
        listOf(
            emptyMap(), mapOf("x" to 1), mapOf("y" to 1),
            mapOf("nodeId" to "node:2", "x" to 1),
            mapOf("nodeId" to "node:2", "y" to 1),
            mapOf("nodeId" to "node:2", "x" to 1, "y" to 2),
            mapOf("nodeId" to null), mapOf("nodeId" to ""),
            mapOf("x" to null, "y" to 2)
        ).forEach { target ->
            expectFailure { MobileProtocol.parse(MobileOperation.CLICK, base + target) }
        }
    }

    @Test fun coordinatesRejectNonfiniteOutOfRangeAndHostileTypes() {
        listOf<Any?>(
            Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY,
            -0.1, 100_000.01, Long.MAX_VALUE, null, "12", true,
            BigDecimal("12"), listOf(12)
        ).forEach { value ->
            expectFailure {
                MobileProtocol.parse(MobileOperation.CLICK, base + mapOf("x" to value, "y" to 0))
            }
            expectFailure {
                MobileProtocol.parse(MobileOperation.CLICK, base + mapOf("x" to 0, "y" to value))
            }
        }
        assertEquals(
            MobileCommand.Click(session, observation, null, 12.5, 7.0),
            MobileProtocol.parse(MobileOperation.CLICK, base + mapOf("x" to 12.5f, "y" to 7.toShort()))
        )
    }

    @Test fun currentDisplayCoordinatesUseExclusiveRightAndBottomBounds() {
        MobileProtocol.requireCoordinates(0.0, 0.0, 720, 1280)
        MobileProtocol.requireCoordinates(719.9, 1279.9, 720, 1280)
        listOf(
            Pair(720.0, 0.0), Pair(0.0, 1280.0), Pair(-1.0, 0.0),
            Pair(0.0, Double.NaN), Pair(Double.POSITIVE_INFINITY, 0.0)
        ).forEach { (x, y) ->
            expectFailure { MobileProtocol.requireCoordinates(x, y, 720, 1280) }
        }
        expectFailure { MobileProtocol.requireCoordinates(0.0, 0.0, 0, 1280) }
        expectFailure { MobileProtocol.requireCoordinates(0.0, 0.0, 720, -1) }
    }

    @Test fun textSupportsEmptyReplacementAndValidUnicodeWithinItsBound() {
        listOf("", "中文\nDSH \uD83D\uDC0B", "x".repeat(4096), "\uD83D\uDC0B".repeat(2048))
            .forEach { text ->
                assertEquals(
                    MobileCommand.Type(session, observation, "node:3", text),
                    MobileProtocol.parse(MobileOperation.TYPE, base + mapOf("nodeId" to "node:3", "text" to text))
                )
            }
    }

    @Test fun textRejectsOversizeNulMalformedUnicodeAndNonstringValues() {
        listOf<Any?>(
            "x".repeat(4097), "a\u0000b", "\uD800", "\uDC00",
            "\uD800x", "\uD800\uD800", "\uDC00\uD800", null, 1, true, listOf("text")
        ).forEach { text ->
            expectFailure {
                MobileProtocol.parse(MobileOperation.TYPE, base + mapOf("nodeId" to "node:3", "text" to text))
            }
        }
        expectFailure {
            MobileProtocol.parse(MobileOperation.TYPE, base + ("nodeId" to "node:3"))
        }
        expectFailure {
            MobileProtocol.parse(MobileOperation.TYPE, base + mapOf("nodeId" to "../other", "text" to "ok"))
        }
    }

    @Test fun swipeDefaultsDurationAndAcceptsBoundedIntegralNumbers() {
        val fields = validFields(MobileOperation.SWIPE)
        assertEquals(
            MobileCommand.Swipe(session, observation, 0.0, 10.0, 20.0, 30.0, 300),
            MobileProtocol.parse(MobileOperation.SWIPE, fields)
        )
        listOf<Any>(100, 300.0, 1000L).forEach { duration ->
            val command = MobileProtocol.parse(
                MobileOperation.SWIPE, fields + ("durationMs" to duration)
            ) as MobileCommand.Swipe
            assertEquals((duration as Number).toLong(), command.durationMs)
        }
    }

    @Test fun swipeRejectsFractionalOversizeAndCoercedDurations() {
        listOf<Any?>(
            99, 1001, 300.5, Long.MAX_VALUE, Double.NaN, Double.POSITIVE_INFINITY,
            "300", true, null, BigDecimal("300")
        ).forEach { duration ->
            expectFailure {
                MobileProtocol.parse(
                    MobileOperation.SWIPE,
                    validFields(MobileOperation.SWIPE) + ("durationMs" to duration)
                )
            }
        }
    }

    @Test fun swipeRequiresEachEndpointAndFreshObservationIdentifier() {
        val fields = validFields(MobileOperation.SWIPE)
        listOf("fromX", "fromY", "toX", "toY", "sessionId", "observationId").forEach { field ->
            expectFailure { MobileProtocol.parse(MobileOperation.SWIPE, fields - field) }
        }
        listOf("fromX", "fromY", "toX", "toY").forEach { field ->
            expectFailure { MobileProtocol.parse(MobileOperation.SWIPE, fields + (field to -1)) }
        }
    }

    @Test fun errorsNeverEchoUntrustedPathsFieldNamesOrText() {
        val secret = "sensitive-user-value"
        val errors = listOf(
            expectFailure("unknown_operation") { MobileProtocol.operation("/v1/mobile/$secret") },
            expectFailure { MobileProtocol.parse(MobileOperation.STATUS, mapOf(secret to secret)) },
            expectFailure {
                MobileProtocol.parse(MobileOperation.TYPE, base + mapOf("nodeId" to "node:3", "text" to "$secret\u0000"))
            },
            expectFailure {
                MobileProtocol.parse(MobileOperation.BACK, base + ("sessionId" to "$secret/invalid"))
            }
        )
        errors.forEach { assertFalse(it.message.orEmpty().contains(secret)) }
    }

    @Test fun nodeScrollCannotSmuggleCoordinatesOrOperateWithoutAnObservedTarget() {
        for (direction in listOf("forward", "backward")) {
            assertEquals(MobileCommand.Scroll(session, observation, "node:2", direction),
                MobileProtocol.parse(MobileOperation.SCROLL, base + mapOf("nodeId" to "node:2", "direction" to direction)))
        }
        val valid = validFields(MobileOperation.SCROLL)
        for (key in listOf("sessionId", "observationId", "nodeId", "direction")) {
            expectFailure { MobileProtocol.parse(MobileOperation.SCROLL, valid - key) }
        }
        for (direction in listOf(null, true, 1, "up", "FORWARD", "forward\n")) {
            expectFailure { MobileProtocol.parse(MobileOperation.SCROLL, valid + ("direction" to direction)) }
        }
        expectFailure { MobileProtocol.parse(MobileOperation.SCROLL, valid + ("x" to 10)) }
    }

    private fun validFields(operation: MobileOperation): Map<String, Any?> = when (operation) {
        MobileOperation.STATUS, MobileOperation.STOP -> emptyMap()
        MobileOperation.OBSERVE -> mapOf("sessionId" to session)
        MobileOperation.CLICK -> base + ("nodeId" to "node:2")
        MobileOperation.TYPE -> base + mapOf("nodeId" to "node:3", "text" to "fixture")
        MobileOperation.SWIPE -> base + mapOf("fromX" to 0, "fromY" to 10, "toX" to 20, "toY" to 30)
        MobileOperation.SCROLL -> base + mapOf("nodeId" to "node:2", "direction" to "forward")
        MobileOperation.BACK -> base
        MobileOperation.LIST_APPS -> mapOf("sessionId" to session)
        MobileOperation.OPEN_APP -> base + ("packageName" to "com.android.settings")
        MobileOperation.ADB_STATUS -> emptyMap()
        MobileOperation.ADB_SHELL -> mapOf("sessionId" to session, "argv" to listOf("shell"))
        MobileOperation.ROOT_SHELL -> mapOf("sessionId" to session, "argv" to listOf("id"))
        MobileOperation.UI_XML -> mapOf("sessionId" to session, "source" to "accessibility_xml")
    }

    private fun expectFailure(
        code: String = "invalid_request",
        action: () -> Unit
    ): MobileProtocolException {
        try {
            action()
        } catch (error: MobileProtocolException) {
            assertEquals(code, error.code)
            return error
        }
        fail("Expected a mobile protocol validation failure")
        throw AssertionError("Unreachable")
    }
}
