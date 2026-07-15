package com.example.flightlog.maps

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MapApiKeyStoreTest {
    @Test fun acceptsThunderforestKeyCharacters() {
        assertTrue(MapApiKeyStore.isValid("abcDEF0123_-"))
    }

    @Test fun trimsKeyBeforeValidation() {
        assertTrue(MapApiKeyStore.isValid("  abc123  "))
    }

    @Test fun rejectsUrlAndControlCharacters() {
        assertFalse(MapApiKeyStore.isValid("https://example.com/key"))
        assertFalse(MapApiKeyStore.isValid("abc\n123"))
        assertFalse(MapApiKeyStore.isValid(""))
    }
}
