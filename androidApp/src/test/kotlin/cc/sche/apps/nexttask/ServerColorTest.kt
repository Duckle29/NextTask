package cc.sche.apps.nexttask

import androidx.compose.ui.graphics.Color
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ServerColorTest {

    @Test
    fun readsPlainRgb() {
        assertEquals(Color(0xFF0082C9), parseServerColor("#0082c9"))
    }

    @Test
    fun readsAlphaLastAndIgnoresIt() {
        assertEquals(Color(0xFF0082C9), parseServerColor("#0082c9FF"))
        assertEquals(Color(0xFF0082C9), parseServerColor("#0082c900"))
    }

    @Test
    fun rejectsAnythingElse() {
        assertNull(parseServerColor(""))
        assertNull(parseServerColor("blue"))
        assertNull(parseServerColor("#fff"))
        assertNull(parseServerColor("#zzzzzz"))
    }
}
