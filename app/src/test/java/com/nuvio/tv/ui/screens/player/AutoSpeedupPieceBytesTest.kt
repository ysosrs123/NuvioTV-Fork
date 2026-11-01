package com.nuvio.tv.ui.screens.player

import org.junit.Assert.assertEquals
import org.junit.Test

class AutoSpeedupPieceBytesTest {

    @Test
    fun `the piece size is read as a plain long`() {
        val method = AutoSpeedupDataSource.PieceBytes::class.java.getMethod("invoke")
        assertEquals(java.lang.Long.TYPE, method.returnType)
    }

    @Test
    fun `a lambda gives the piece size it returns`() {
        var piece = 8L shl 20
        val bytes = AutoSpeedupDataSource.PieceBytes { piece }
        assertEquals(8L shl 20, bytes())
        piece = 16L shl 20
        assertEquals(16L shl 20, bytes())
    }
}
