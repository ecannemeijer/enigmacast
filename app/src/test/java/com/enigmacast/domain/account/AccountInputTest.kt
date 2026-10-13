package com.enigmacast.domain.account

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AccountInputTest {
    @Test
    fun checksTheForm() {
        assertEquals(AccountInput.Problem.EMAIL, AccountInput.check("nope", "password1", "password1", creating = true))
        assertEquals(AccountInput.Problem.PASSWORD_SHORT, AccountInput.check("a@b.nl", "short", "short", creating = true))
        assertEquals(AccountInput.Problem.MISMATCH, AccountInput.check("a@b.nl", "password1", "password2", creating = true))
        assertNull(AccountInput.check(" A@B.nl ", "password1", "password1", creating = true))
        // Logging in: no confirmation and no length rule beyond "not empty".
        assertNull(AccountInput.check("a@b.nl", "x", null, creating = false))
        assertEquals(AccountInput.Problem.PASSWORD_SHORT, AccountInput.check("a@b.nl", "", null, creating = false))
        assertEquals("a@b.nl", AccountInput.normalizeEmail(" A@B.nl "))
    }
}
