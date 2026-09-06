package com.smslink.network.security

import com.smslink.security.DeviceIdentityStore
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AuthProtocolTest {

    @Test
    fun `signed hello survives encoding and verifies with peer verifier`() {
        val signer = DeviceIdentityStore.forTests()
        val verifier = DeviceIdentityStore.forTests()
        val now = 1_700_000_000_000L
        val hello = AuthProtocol.createHello(
            identity = signer,
            targetDevice = "target-device",
            sourceName = "Phone",
            pairingToken = null,
            now = now
        )

        val decoded = requireNotNull(AuthProtocol.decodeHello(AuthProtocol.encodeHello(hello)))

        assertTrue(AuthProtocol.verifyHello(decoded, verifier, now))
        assertFalse(AuthProtocol.verifyHello(decoded.copy(sourceName = "attacker"), verifier, now))
        assertFalse(AuthProtocol.verifyHello(decoded, verifier, now + AuthProtocol.MAX_CLOCK_SKEW_MS + 1))
    }

    @Test
    fun `response is pinned to nonce identities and public key`() {
        val responder = DeviceIdentityStore.forTests()
        val verifier = DeviceIdentityStore.forTests()
        val now = 1_700_000_000_000L
        val nonce = "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA="
        val response = AuthProtocol.createResponse(
            identity = responder,
            targetDevice = "requester",
            nonce = nonce,
            accepted = true,
            now = now
        )

        assertTrue(
            AuthProtocol.verifyResponse(
                response = response,
                expectedSource = responder.deviceId(),
                expectedTarget = "requester",
                expectedNonce = nonce,
                expectedPublicKey = responder.publicKeyBase64(),
                identity = verifier,
                now = now
            )
        )
        assertFalse(
            AuthProtocol.verifyResponse(
                response = response,
                expectedSource = responder.deviceId(),
                expectedTarget = "requester",
                expectedNonce = nonce.replace('A', 'B'),
                expectedPublicKey = responder.publicKeyBase64(),
                identity = verifier,
                now = now
            )
        )
        assertFalse(
            AuthProtocol.verifyResponse(
                response = response.copy(accepted = false),
                expectedSource = responder.deviceId(),
                expectedTarget = "requester",
                expectedNonce = nonce,
                expectedPublicKey = responder.publicKeyBase64(),
                identity = verifier,
                now = now
            )
        )
    }

    @Test
    fun `decoder rejects empty and oversized auth frames`() {
        assertTrue(AuthProtocol.decodeHello(ByteArray(0)) == null)
        assertTrue(AuthProtocol.decodeResponse(ByteArray(AuthProtocol.MAX_AUTH_FRAME_BYTES + 1)) == null)
    }
}
