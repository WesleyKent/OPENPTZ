package com.opencapture.openpocketcine.visca

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertIs

class ViscaProtocolTest {
    @Test
    fun commandUsesSonyIpEnvelopeAndBigEndianSequence() {
        val payload = bytes(0x81, 0x01, 0x06, 0x04, 0xFF)
        val packet = ViscaIpPacketCodec.command(payload, 0x01020304)

        assertContentEquals(
            bytes(0x01, 0x00, 0x00, 0x05, 0x01, 0x02, 0x03, 0x04, 0x81, 0x01, 0x06, 0x04, 0xFF),
            packet,
        )
        val decoded = requireNotNull(ViscaIpPacketCodec.decode(packet))
        assertEquals(ViscaIpPacketCodec.COMMAND_TYPE, decoded.type)
        assertEquals(0x01020304L, decoded.sequence)
        assertContentEquals(payload, decoded.payload)
    }

    @Test
    fun sequenceResetUsesDeviceSettingPacket() {
        assertContentEquals(
            bytes(0x02, 0x00, 0x00, 0x01, 0x00, 0x00, 0x00, 0x00, 0x01),
            ViscaIpPacketCodec.sequenceReset(),
        )
    }

    @Test
    fun tail2CommandsMatchOfficialViscaExtension() {
        assertContentEquals(bytes(0x81, 0x01, 0x04, 0x66, 0x01, 0xFF), ViscaCommands.record(true))
        assertContentEquals(bytes(0x81, 0x01, 0x8E, 0x00, 0x02, 0xFF), ViscaCommands.tail2Tracking(true))
        assertContentEquals(bytes(0x81, 0x01, 0x8E, 0x01, 0x00, 0xFF), ViscaCommands.tail2TrackingSingle())
        assertContentEquals(
            bytes(0x81, 0x01, 0x04, 0x47, 0x00, 0x07, 0x0D, 0x00, 0xFF),
            ViscaCommands.tail2ZoomRatio(2.0),
        )
    }

    @Test
    fun joystickEncodesIndependentDirectionAndStop() {
        assertContentEquals(
            bytes(0x81, 0x01, 0x06, 0x01, 0x18, 0x17, 0x02, 0x01, 0xFF),
            ViscaCommands.panTilt(1f, -1f),
        )
        assertContentEquals(
            bytes(0x81, 0x01, 0x06, 0x01, 0x01, 0x01, 0x03, 0x03, 0xFF),
            ViscaCommands.panTilt(0f, 0f),
        )
    }

    @Test
    fun replyParserDistinguishesAckCompletionAndErrors() {
        assertIs<ViscaReply.Ack>(ViscaReply.from(bytes(0x90, 0x41, 0xFF)))
        assertIs<ViscaReply.Complete>(ViscaReply.from(bytes(0x90, 0x51, 0xFF)))
        val error = assertIs<ViscaReply.Error>(ViscaReply.from(bytes(0x90, 0x61, 0x41, 0xFF)))
        assertEquals(0x41, error.code)
    }

    private fun bytes(vararg values: Int): ByteArray = ByteArray(values.size) { values[it].toByte() }
}
