package me.rerere.rikkahub.data.container

import org.junit.Assert.assertArrayEquals
import org.junit.Test

class ControlInputTest {
    @Test
    fun controlInputBytesCoverReadlineAndVimControlCharacters() {
        assertArrayEquals(byteArrayOf(0x00), controlInputBytes(ControlInput.CTRL_SPACE))
        assertArrayEquals(byteArrayOf(0x01), controlInputBytes(ControlInput.CTRL_A))
        assertArrayEquals(byteArrayOf(0x02), controlInputBytes(ControlInput.CTRL_B))
        assertArrayEquals(byteArrayOf(0x06), controlInputBytes(ControlInput.CTRL_F))
        assertArrayEquals(byteArrayOf(0x08), controlInputBytes(ControlInput.CTRL_H))
        assertArrayEquals(byteArrayOf(0x0B), controlInputBytes(ControlInput.CTRL_K))
        assertArrayEquals(byteArrayOf(0x0D), controlInputBytes(ControlInput.CTRL_M))
        assertArrayEquals(byteArrayOf(0x0E), controlInputBytes(ControlInput.CTRL_N))
        assertArrayEquals(byteArrayOf(0x10), controlInputBytes(ControlInput.CTRL_P))
        assertArrayEquals(byteArrayOf(0x14), controlInputBytes(ControlInput.CTRL_T))
        assertArrayEquals(byteArrayOf(0x16), controlInputBytes(ControlInput.CTRL_V))
        assertArrayEquals(byteArrayOf(0x18), controlInputBytes(ControlInput.CTRL_X))
        assertArrayEquals(byteArrayOf(0x19), controlInputBytes(ControlInput.CTRL_Y))
        assertArrayEquals(byteArrayOf(0x1B), controlInputBytes(ControlInput.CTRL_LEFT_BRACKET))
        assertArrayEquals(byteArrayOf(0x1C), controlInputBytes(ControlInput.CTRL_BACKSLASH))
        assertArrayEquals(byteArrayOf(0x1D), controlInputBytes(ControlInput.CTRL_RIGHT_BRACKET))
        assertArrayEquals(byteArrayOf(0x1E), controlInputBytes(ControlInput.CTRL_CARET))
        assertArrayEquals(byteArrayOf(0x1F), controlInputBytes(ControlInput.CTRL_UNDERSCORE))
    }

    @Test
    fun controlInputBytesKeepExistingSpecialKeysStable() {
        assertArrayEquals(byteArrayOf('\t'.code.toByte()), controlInputBytes(ControlInput.TAB))
        assertArrayEquals("\u001B[Z".toByteArray(Charsets.UTF_8), controlInputBytes(ControlInput.BACK_TAB))
        assertArrayEquals(byteArrayOf(0x1B), controlInputBytes(ControlInput.ESC))
        assertArrayEquals("\u001B[A".toByteArray(Charsets.UTF_8), controlInputBytes(ControlInput.UP))
        assertArrayEquals("\u001B[D".toByteArray(Charsets.UTF_8), controlInputBytes(ControlInput.LEFT))
        assertArrayEquals("\u001B[3~".toByteArray(Charsets.UTF_8), controlInputBytes(ControlInput.DELETE))
        assertArrayEquals(byteArrayOf('\n'.code.toByte()), controlInputBytes(ControlInput.ENTER))
        assertArrayEquals(byteArrayOf(0x7F), controlInputBytes(ControlInput.BACKSPACE))
        assertArrayEquals("\u001BOP".toByteArray(Charsets.UTF_8), controlInputBytes(ControlInput.F1))
        assertArrayEquals("\u001B[24~".toByteArray(Charsets.UTF_8), controlInputBytes(ControlInput.F12))
    }

    @Test
    fun controlInputBytesSupportModifiedNavigationKeys() {
        assertArrayEquals("\u001B[1;2A".toByteArray(Charsets.UTF_8), controlInputBytes(ControlInput.SHIFT_UP))
        assertArrayEquals("\u001B[1;3B".toByteArray(Charsets.UTF_8), controlInputBytes(ControlInput.ALT_DOWN))
        assertArrayEquals("\u001B[1;5D".toByteArray(Charsets.UTF_8), controlInputBytes(ControlInput.CTRL_LEFT))
        assertArrayEquals("\u001B[1;6C".toByteArray(Charsets.UTF_8), controlInputBytes(ControlInput.SHIFT_CTRL_RIGHT))
        assertArrayEquals("\u001B[1;7A".toByteArray(Charsets.UTF_8), controlInputBytes(ControlInput.ALT_CTRL_UP))
        assertArrayEquals("\u001B[1;8D".toByteArray(Charsets.UTF_8), controlInputBytes(ControlInput.SHIFT_ALT_CTRL_LEFT))
        assertArrayEquals("\u001B[1;5H".toByteArray(Charsets.UTF_8), controlInputBytes(ControlInput.CTRL_HOME))
        assertArrayEquals("\u001B[1;3F".toByteArray(Charsets.UTF_8), controlInputBytes(ControlInput.ALT_END))
        assertArrayEquals("\u001B[5;2~".toByteArray(Charsets.UTF_8), controlInputBytes(ControlInput.SHIFT_PAGE_UP))
        assertArrayEquals("\u001B[6;5~".toByteArray(Charsets.UTF_8), controlInputBytes(ControlInput.CTRL_PAGE_DOWN))
        assertArrayEquals("\u001B[2;2~".toByteArray(Charsets.UTF_8), controlInputBytes(ControlInput.SHIFT_INSERT))
        assertArrayEquals("\u001B[3;5~".toByteArray(Charsets.UTF_8), controlInputBytes(ControlInput.CTRL_DELETE))
        assertArrayEquals("\u001B[3;3~".toByteArray(Charsets.UTF_8), controlInputBytes(ControlInput.ALT_DELETE))
    }

    @Test
    fun controlInputBytesSupportModifiedFunctionAndEditingKeys() {
        assertArrayEquals("\u001B[1;2P".toByteArray(Charsets.UTF_8), controlInputBytes(ControlInput.SHIFT_F1))
        assertArrayEquals("\u001B[1;3S".toByteArray(Charsets.UTF_8), controlInputBytes(ControlInput.ALT_F4))
        assertArrayEquals("\u001B[15;5~".toByteArray(Charsets.UTF_8), controlInputBytes(ControlInput.CTRL_F5))
        assertArrayEquals("\u001B[21;6~".toByteArray(Charsets.UTF_8), controlInputBytes(ControlInput.SHIFT_CTRL_F10))
        assertArrayEquals("\u001B[23;7~".toByteArray(Charsets.UTF_8), controlInputBytes(ControlInput.ALT_CTRL_F11))
        assertArrayEquals("\u001B[24;8~".toByteArray(Charsets.UTF_8), controlInputBytes(ControlInput.SHIFT_ALT_CTRL_F12))
        assertArrayEquals(byteArrayOf(0x1B, '\t'.code.toByte()), controlInputBytes(ControlInput.ALT_TAB))
        assertArrayEquals(byteArrayOf(0x1B, '\r'.code.toByte()), controlInputBytes(ControlInput.ALT_ENTER))
        assertArrayEquals(byteArrayOf(0x17), controlInputBytes(ControlInput.CTRL_BACKSPACE))
        assertArrayEquals(byteArrayOf(0x1B, 0x7F), controlInputBytes(ControlInput.ALT_BACKSPACE))
    }

    @Test
    fun controlInputBytesSupportAltPrintableMetaKeys() {
        assertArrayEquals(byteArrayOf(0x1B, ' '.code.toByte()), controlInputBytes(ControlInput.ALT_SPACE))
        assertArrayEquals(byteArrayOf(0x1B, 'b'.code.toByte()), controlInputBytes(ControlInput.ALT_B))
        assertArrayEquals(byteArrayOf(0x1B, 'f'.code.toByte()), controlInputBytes(ControlInput.ALT_F))
        assertArrayEquals(byteArrayOf(0x1B, 'd'.code.toByte()), controlInputBytes(ControlInput.ALT_D))
        assertArrayEquals(byteArrayOf(0x1B, '0'.code.toByte()), controlInputBytes(ControlInput.ALT_0))
        assertArrayEquals(byteArrayOf(0x1B, '9'.code.toByte()), controlInputBytes(ControlInput.ALT_9))
        assertArrayEquals(byteArrayOf(0x1B, '.'.code.toByte()), controlInputBytes(ControlInput.ALT_PERIOD))
        assertArrayEquals(byteArrayOf(0x1B, '/'.code.toByte()), controlInputBytes(ControlInput.ALT_SLASH))
        assertArrayEquals(byteArrayOf(0x1B, '\'.code.toByte()), controlInputBytes(ControlInput.ALT_BACKSLASH))
        assertArrayEquals(byteArrayOf(0x1B, "'".single().code.toByte()), controlInputBytes(ControlInput.ALT_APOSTROPHE))
        assertArrayEquals(byteArrayOf(0x1B, '`'.code.toByte()), controlInputBytes(ControlInput.ALT_GRAVE))
    }
}
