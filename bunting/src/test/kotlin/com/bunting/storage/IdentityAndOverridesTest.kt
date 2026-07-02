package com.bunting.storage

import com.bunting.identity.BuntingIdentity
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

class IdentityAndOverridesTest {

    @Test
    fun identityPersistsAcrossInstancesAndResets(@TempDir dir: File) {
        val store = FileKeyValueStore(File(dir, "identity.json"))
        val id1 = BuntingIdentity(store).getLocalId()
        // A fresh identity over the same store reads the same persisted UUID.
        val id2 = BuntingIdentity(FileKeyValueStore(File(dir, "identity.json"))).getLocalId()
        assertEquals(id1, id2)
        val reset = BuntingIdentity(store).resetIdentity()
        assertNotEquals(id1, reset)
    }

    @Test
    fun overridesRoundTripAndPersist(@TempDir dir: File) {
        val file = File(dir, "overrides.json")
        val store = OverridesStore(file)
        store.setOverride("f/a", OverrideValue.BoolValue(true))
        store.setOverride("f/b", OverrideValue.IntValue(7))
        assertEquals(OverrideValue.BoolValue(true), store.getOverride("f/a"))

        // Reload from disk.
        val reloaded = OverridesStore(file)
        assertEquals(OverrideValue.IntValue(7), reloaded.getOverride("f/b"))

        reloaded.clearOverride("f/a")
        assertNull(reloaded.getOverride("f/a"))
        reloaded.clearAll()
        assertNull(reloaded.getOverride("f/b"))
    }
}
