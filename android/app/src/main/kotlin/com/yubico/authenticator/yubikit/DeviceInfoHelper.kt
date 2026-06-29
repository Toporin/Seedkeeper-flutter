/*
 * Copyright (C) 2022-2025 Yubico.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *       http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.yubico.authenticator.yubikit

import com.yubico.authenticator.compatUtil
import com.yubico.authenticator.device.Info
import com.yubico.authenticator.device.restrictedNfcDeviceInfo
import com.yubico.authenticator.device.unknownDeviceWithCapability
import com.yubico.yubikit.android.transport.nfc.NfcYubiKeyDevice
import com.yubico.yubikit.android.transport.usb.UsbYubiKeyDevice
import com.yubico.yubikit.core.Version
import com.yubico.yubikit.core.YubiKeyDevice
import com.yubico.yubikit.core.application.ApplicationNotAvailableException
import com.yubico.yubikit.core.application.SessionVersionOverride
import com.yubico.yubikit.core.fido.FidoConnection
import com.yubico.yubikit.core.otp.OtpConnection
import com.yubico.yubikit.core.smartcard.Apdu
import com.yubico.yubikit.core.smartcard.SmartCardConnection
import com.yubico.yubikit.core.smartcard.SmartCardProtocol
import com.yubico.yubikit.fido.ctap.Ctap2Session
import com.yubico.yubikit.management.Capability
import com.yubico.yubikit.oath.OathSession
import com.yubico.yubikit.support.DeviceUtil
import org.slf4j.LoggerFactory

class DeviceInfoHelper {
    companion object {
        private val logger = LoggerFactory.getLogger("DeviceInfoHelper")
        private val nfcTagReaderAid = byteArrayOf(0xD2.toByte(), 0x76, 0, 0, 0x85.toByte(), 1, 1)
        private val seedkeeperAid =
            byteArrayOf(0x53, 0x65, 0x65, 0x64, 0x4b, 0x65, 0x65, 0x70, 0x65, 0x72, 0x00)
        private val uri = "yubico.com/getting-started".toByteArray()
        private val restrictedNfcBytes =
            byteArrayOf(0x00, 0x1F, 0xD1.toByte(), 0x01, 0x1b, 0x55, 0x04) + uri

        fun getDeviceInfo(device: YubiKeyDevice): Info {
            SessionVersionOverride.set(null)
            var deviceInfo = readDeviceInfo(device)
            if (deviceInfo.version.major == 0.toByte()) {
                SessionVersionOverride.set(Version(5, 7, 2))
                deviceInfo = readDeviceInfo(device)
            }
            return deviceInfo
        }

        private fun readDeviceInfo(device: YubiKeyDevice): Info {
            val pid = (device as? UsbYubiKeyDevice)?.pid

            val deviceInfo = runCatching {
                device.openConnection(SmartCardConnection::class.java)
                    .use { DeviceUtil.readInfo(it, pid) }
            }.recoverCatching { t ->
                logger.debug("SmartCard connection not available: {}", t.message)
                device.openConnection(FidoConnection::class.java)
                    .use { DeviceUtil.readInfo(it, pid) }
            }.recoverCatching { t ->
                logger.debug("FIDO connection not available: {}", t.message)
                device.openConnection(OtpConnection::class.java)
                    .use { DeviceUtil.readInfo(it, pid) }
            }.recoverCatching { t ->
                logger.debug("OTP connection not available: {}", t.message)
                return SkyHelper(compatUtil).getDeviceInfo(device)
            }.getOrElse {
                // this is not a YubiKey
                logger.debug("Probing unknown device")
                try {
                    device.openConnection(SmartCardConnection::class.java)
                        .use { smartCardConnection ->
                            // A device may expose several applets (e.g. a Seedkeeper
                            // PRO has both OATH and FIDO2). Probe each applet
                            // independently and combine the capabilities instead of
                            // returning on the first match, otherwise FIDO2 stays
                            // hidden behind OATH.
                            var capabilities = 0
                            try {
                                OathSession(smartCardConnection)
                                logger.debug("Device supports OATH")
                                capabilities = capabilities or Capability.OATH.bit
                            } catch (_: ApplicationNotAvailableException) {
                                // OATH applet not present
                            }
                            try {
                                Ctap2Session(smartCardConnection)
                                logger.debug("Device supports FIDO2")
                                capabilities = capabilities or Capability.FIDO2.bit
                            } catch (_: ApplicationNotAvailableException) {
                                // FIDO2/CTAP2 applet not present
                            }
                            try {
                                // Probe last so it doesn't disturb the OATH/FIDO2
                                // session constructors above.
                                SmartCardProtocol(smartCardConnection).select(seedkeeperAid)
                                logger.debug("Device supports Seedkeeper")
                                capabilities = capabilities or Capability.SEEDKEEPER.bit
                            } catch (_: ApplicationNotAvailableException) {
                                // Seedkeeper applet not present
                            }

                            if (capabilities != 0) {
                                val hasSeedkeeper =
                                    (capabilities and Capability.SEEDKEEPER.bit) != 0
                                val hasFido2 = (capabilities and Capability.FIDO2.bit) != 0
                                val name = when {
                                    hasSeedkeeper && hasFido2 -> "Seedkeeper PRO"
                                    hasSeedkeeper -> "Seedkeeper"
                                    capabilities == Capability.OATH.bit -> "OATH device"
                                    capabilities == Capability.FIDO2.bit -> "FIDO2 device"
                                    else -> "Security Key"
                                }
                                return unknownDeviceWithCapability(
                                    device.transport,
                                    capabilities,
                                    name
                                )
                            }

                            // probe for NFC restricted device
                            if (isNfcRestricted(smartCardConnection)) {
                                logger.debug("Device has restricted NFC")
                                return restrictedNfcDeviceInfo(device.transport)
                            }
                            logger.debug("Device not recognized")
                            return unknownDeviceWithCapability(device.transport)
                        }
                } catch (e: Exception) {
                    // no smart card connectivity
                    logger.error("Failure getting device info: ", e)
                    throw e
                }
            }

            val name = DeviceUtil.getName(deviceInfo, pid?.type)
            return Info(name, device is NfcYubiKeyDevice, pid?.value, deviceInfo)
        }

        private fun isNfcRestricted(connection: SmartCardConnection): Boolean =
            restrictedNfcBytes.contentEquals(
                readNdef(connection).also {
                    logger.debug("ndef: {}", it)
                }
            )

        private fun readNdef(connection: SmartCardConnection): ByteArray? = try {
            with(SmartCardProtocol(connection)) {
                select(nfcTagReaderAid)
                sendAndReceive(Apdu(0x00, 0xA4, 0x00, 0x0C, byteArrayOf(0xE1.toByte(), 0x04)))
                sendAndReceive(Apdu(0x00, 0xB0, 0x00, 0x00, null))
            }
        } catch (e: Exception) {
            logger.debug("Failed to read ndef tag: ", e)
            null
        }
    }
}
