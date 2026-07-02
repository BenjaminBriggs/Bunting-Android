package com.bunting.model

import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import java.time.Instant

class FlagValueAccessorsTest {

    private val bool = FlagValue.BoolValue(true)
    private val int = FlagValue.IntValue(7L)
    private val double = FlagValue.DoubleValue(2.5)
    private val string = FlagValue.StringValue("free")
    private val date = FlagValue.DateValue(Instant.parse("2026-07-02T00:00:00.123Z"))
    private val jsonV = FlagValue.JsonValue("""{"a":1}""")

    @Test
    fun flagTypeMapsEachCase() {
        assertEquals(FlagType.BOOL, bool.flagType)
        assertEquals(FlagType.INT, int.flagType)
        assertEquals(FlagType.DOUBLE, double.flagType)
        assertEquals(FlagType.STRING, string.flagType)
        assertEquals(FlagType.DATE, date.flagType)
        assertEquals(FlagType.JSON, jsonV.flagType)
    }

    @Test
    fun boolValueOnlyFromBool() {
        assertEquals(true, bool.boolValue)
        assertNull(int.boolValue)
        assertNull(double.boolValue)
        assertNull(string.boolValue)
        assertNull(date.boolValue)
        assertNull(jsonV.boolValue)
    }

    @Test
    fun intValueOnlyFromInt() {
        assertEquals(7L, int.intValue)
        assertNull(bool.intValue)
        assertNull(double.intValue) // a Double is NOT coerced to Long
        assertNull(string.intValue)
        assertNull(date.intValue)
        assertNull(jsonV.intValue)
    }

    @Test
    fun doubleValueCoercesIntButNotOthers() {
        // The whole-number coercion: IntValue(2) reads as 2.0 for double-typed flags.
        assertEquals(2.0, FlagValue.IntValue(2L).doubleValue)
        assertEquals(2.5, double.doubleValue)
        assertEquals(7.0, int.doubleValue)
        assertNull(bool.doubleValue)
        assertNull(string.doubleValue)
        assertNull(date.doubleValue)
        assertNull(jsonV.doubleValue)
    }

    @Test
    fun stringValueOnlyFromString() {
        assertEquals("free", string.stringValue)
        assertNull(bool.stringValue)
        assertNull(int.stringValue)
        assertNull(double.stringValue)
        assertNull(date.stringValue)
        assertNull(jsonV.stringValue) // JSON is not a plain string value
    }

    @Test
    fun dateValueOnlyFromDate() {
        assertEquals(Instant.parse("2026-07-02T00:00:00.123Z"), date.dateValue)
        assertNull(bool.dateValue)
        assertNull(int.dateValue)
        assertNull(double.dateValue)
        assertNull(string.dateValue)
        assertNull(jsonV.dateValue)
    }

    @Test
    fun jsonBytesOnlyFromJson() {
        assertArrayEquals("""{"a":1}""".toByteArray(Charsets.UTF_8), jsonV.jsonBytes)
        assertNull(bool.jsonBytes)
        assertNull(int.jsonBytes)
        assertNull(double.jsonBytes)
        assertNull(string.jsonBytes)
        assertNull(date.jsonBytes)
    }
}
