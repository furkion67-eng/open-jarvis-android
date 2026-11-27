package com.openjarvis.commands

import org.junit.Assert.assertEquals
import org.junit.Test

class RecipientSourcePolicyTest {
    @Test
    fun contactNameWinsWhenNameAndNumberAreBothParsed() {
        assertEquals(
            RecipientSource.ContactName("Ayşe Yılmaz"),
            RecipientSourcePolicy.choose("  Ayşe Yılmaz  ", "+905551112233")
        )
    }

    @Test
    fun explicitNumberIsUsedOnlyWhenNoContactNameWasSupplied() {
        assertEquals(
            RecipientSource.ExplicitNumber("+905551112233"),
            RecipientSourcePolicy.choose("   ", " +905551112233 ")
        )
    }

    @Test
    fun missingNameAndNumberRemainMissing() {
        assertEquals(RecipientSource.Missing, RecipientSourcePolicy.choose(null, "  "))
    }
}
