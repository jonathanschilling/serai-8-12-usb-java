"""
SERUSB.dll Python replacement
==============================

Drop-in replacement for the Delphi SERUSB.dll that communicates with a
Cypress/Anchor Chips EZ-USB (AN2131) device with a MAX186 12-bit 8-channel
ADC, providing ADC reading and digital output.

Original DLL interface (from SERUSB.BAS):
    Declare Sub INIT Lib "SERUSB" ()
    Declare Function AD Lib "SERUSB" (ByVal Eingang%) As Integer
    Declare Sub OUTC Lib "SERUSB" (ByVal Wert%)

Hardware:
    - Cypress AN2131 EZ-USB microcontroller (8051 core)
    - MAX186 8-channel 12-bit ADC, connected via bit-banged SPI on Port A:
        Port A bit 0 = SCLK
        Port A bit 2 = DIN  (MOSI → MAX186)
        Port A bit 4 = DOUT (MISO ← MAX186)
    - Port A (0x7F98) also used for 8-bit digital output (OUTC)

Protocol:
    - The EZ-USB device is accessed via USB vendor request 0xA0
      (the standard Cypress firmware upload/RAM access request).
    - INIT: Asserts 8051 reset (write 1 to 0x7F92), downloads 256-byte
      firmware to the device, then releases reset (write 0 to 0x7F92).
    - AD: Reads 32 bytes from device address 0x01F0.  The 8051 firmware
      stores each 12-bit ADC result as two bytes: R3 (upper 8 bits,
      D11..D4) and R4 (lower 4 bits, D3..D0).  The x86 code combines
      them as (R3 << 4) | R4 to produce a 12-bit value (0–4095).
    - OUTC: Writes one byte to device address 0x0210 for digital output.

    Firmware memory layout (at 0x0200–0x020F, read via USB from 0x01F0):
        Eingang 0 → [0x0200:0x0201] → MAX186 CH0  (ctrl 0x8E)
        Eingang 1 → [0x0202:0x0203] → MAX186 CH4  (ctrl 0xCE)
        Eingang 2 → [0x0204:0x0205] → MAX186 CH1  (ctrl 0x9E)
        Eingang 3 → [0x0206:0x0207] → MAX186 CH5  (ctrl 0xDE)
        Eingang 4 → [0x0208:0x0209] → MAX186 CH2  (ctrl 0xAE)
        Eingang 5 → [0x020A:0x020B] → MAX186 CH6  (ctrl 0xEE)
        Eingang 6 → [0x020C:0x020D] → MAX186 CH3  (ctrl 0xBE)
        Eingang 7 → [0x020E:0x020F] → MAX186 CH7  (ctrl 0xFE)

Requirements:
    pip install pyusb

On Linux, you may need a udev rule for the EZ-USB device, e.g.:
    SUBSYSTEM=="usb", ATTR{idVendor}=="0547", ATTR{idProduct}=="2131", MODE="0666"
"""

import time
import usb.core
import usb.util

# Cypress EZ-USB AN2131 default VID:PID (before firmware renumeration).
# Adjust if your hardware uses a different VID:PID.
EZUSB_VID = 0x0547
EZUSB_PID = 0x2131

# USB vendor request code for EZ-USB RAM access
EZUSB_VENDOR_REQUEST = 0xA0

# 8051 CPU reset register address
EZUSB_CPUCS_ADDR = 0x7F92

# Firmware extracted from the original SERUSB.dll (Intel HEX records).
# 256 bytes of 8051 code that performs ADC reads and DAC writes via
# the EZ-USB I/O ports.
_FIRMWARE_HEX_RECORDS = [
    ":1800000012001212000B12008480FB907F9E74FFF022907F9D7407F053",
    ":18001800907F977402F02279FFD9FE1470F922FB907F97E500F07400CA",
    ":180030007A08CB30E7027B04CBF004F014F07400CB23CBDAED7A1EDABA",
    ":18004800FE7B007A08CB23CB907F977401F07400F0907F9AE030E401DF",
    ":180060000BDAEA7C007A04CC23CC907F977401F07400F0907F9AE030DC",
    ":18007800E4010CDAEA907F977402F022748E120027EB900200F0ECA356",
    ":18009000F074CE120027EB900202F0ECA3F0749E120027EB900204F043",
    ":1800A800ECA3F074DE120027EB900206F0ECA3F074AE120027EB90026C",
    ":1800C00008F0ECA3F074019001F8F074EE120027EB90020AF0ECA3F032",
    ":1800D80074BE120027EB90020CF0ECA3F074FE120027EB90020EF0EC9B",
    ":1000F000A3F0900210E0907F98F0740112001F228C",
]


def _parse_firmware():
    """Parse Intel HEX records into a firmware binary image."""
    firmware = bytearray(256)
    max_addr = 0
    for line in _FIRMWARE_HEX_RECORDS:
        raw = bytes.fromhex(line[1:])  # skip leading ':'
        length = raw[0]
        addr = (raw[1] << 8) | raw[2]
        rec_type = raw[3]
        if rec_type == 0:  # data record
            for i in range(length):
                firmware[addr + i] = raw[4 + i]
            end = addr + length
            if end > max_addr:
                max_addr = end
    return bytes(firmware[:max_addr])


# Pre-parsed firmware image
_FIRMWARE = _parse_firmware()


class SERUSB:
    """Interface to the EZ-USB based ADC/DAC hardware."""

    def __init__(self):
        self._dev = None

    def _find_device(self):
        """Find and return the EZ-USB device."""
        dev = usb.core.find(idVendor=EZUSB_VID, idProduct=EZUSB_PID)
        if dev is None:
            raise RuntimeError(
                f"EZ-USB device not found (VID=0x{EZUSB_VID:04X}, "
                f"PID=0x{EZUSB_PID:04X}). Check USB connection and permissions."
            )
        return dev

    def _vendor_write(self, address, data):
        """Write data byte(s) to EZ-USB internal RAM via vendor request 0xA0.

        Uses bmRequestType=0x40 (host-to-device, vendor, device).
        """
        if isinstance(data, int):
            data = bytes([data])
        self._dev.ctrl_transfer(
            bmRequestType=0x40,       # USB_DIR_OUT | USB_TYPE_VENDOR | USB_RECIP_DEVICE
            bRequest=EZUSB_VENDOR_REQUEST,
            wValue=address,
            wIndex=0,
            data_or_wLength=data,
        )

    def _vendor_read(self, address, length):
        """Read data from EZ-USB internal RAM via vendor request 0xA0.

        Uses bmRequestType=0xC0 (device-to-host, vendor, device).
        Returns bytes object.
        """
        return self._dev.ctrl_transfer(
            bmRequestType=0xC0,       # USB_DIR_IN | USB_TYPE_VENDOR | USB_RECIP_DEVICE
            bRequest=EZUSB_VENDOR_REQUEST,
            wValue=address,
            wIndex=0,
            data_or_wLength=length,
        )

    def _reset_cpu(self, hold_reset):
        """Assert or release the 8051 CPU reset.

        Args:
            hold_reset: True to hold CPU in reset, False to release.
        """
        self._vendor_write(EZUSB_CPUCS_ADDR, 0x01 if hold_reset else 0x00)

    def _download_firmware(self):
        """Download firmware to EZ-USB 8051 internal RAM.

        The original DLL uses IOCTL_EZUSB_ANCHOR_DOWNLOAD (0x22206D) which
        bulk-writes the firmware. With pyusb, we use vendor request 0xA0 to
        write in chunks (max 64 bytes per control transfer for EZ-USB).
        """
        chunk_size = 64
        for offset in range(0, len(_FIRMWARE), chunk_size):
            chunk = _FIRMWARE[offset:offset + chunk_size]
            self._vendor_write(offset, chunk)

    def INIT(self):
        """Initialize the EZ-USB device: upload firmware and start the 8051.

        Equivalent to the original DLL's INIT export.
        """
        self._dev = self._find_device()

        # Detach kernel driver if active (Linux)
        try:
            if self._dev.is_kernel_driver_active(0):
                self._dev.detach_kernel_driver(0)
        except (usb.core.USBError, NotImplementedError):
            pass

        # Step 1: Hold 8051 in reset
        self._reset_cpu(hold_reset=True)
        time.sleep(0.010)  # 10 ms, matches original DLL's Sleep(10)

        # Step 2: Download firmware
        self._download_firmware()
        time.sleep(0.010)

        # Step 3: Release reset — 8051 starts executing firmware
        self._reset_cpu(hold_reset=False)

    def AD(self, eingang):
        """Read 12-bit ADC value for the given input channel.

        Args:
            eingang: Input channel number (0-7).  Note: this indexes the
                firmware's storage order, not the MAX186 channel directly.
                Eingang 0→CH0, 1→CH4, 2→CH1, 3→CH5, 4→CH2, 5→CH6, 6→CH3, 7→CH7.

        Returns:
            12-bit ADC value (0-4095).

        Equivalent to the original DLL's AD export.

        The 8051 firmware SPI-reads the MAX186, storing each 12-bit result
        as two bytes at 0x0200+:
            R3 = upper 8 bits (D11..D4), value 0-255
            R4 = lower 4 bits (D3..D0),  value 0-15
        The USB read fetches 32 bytes starting at 0x01F0; the ADC data
        begins at offset 0x10 within that buffer (= device address 0x0200).
        Result = (R3 << 4) | R4, giving the full 12-bit value.
        """
        if self._dev is None:
            raise RuntimeError("Device not initialized. Call INIT() first.")

        # Read 32 bytes from device address 0x01F0
        buf = self._vendor_read(0x01F0, 0x20)

        # Reconstruct 12-bit ADC value from two bytes
        idx = eingang * 2
        msb = buf[idx + 0x10]   # R3: upper 8 bits of 12-bit result (D11..D4)
        lsb = buf[idx + 0x11]   # R4: lower 4 bits of 12-bit result (D3..D0)
        result = (msb << 4) | lsb

        return result  # 0-4095 for 12-bit MAX186

    def OUTC(self, wert):
        """Write a value to the digital output port.

        Args:
            wert: Output value (integer, low byte used).

        Equivalent to the original DLL's OUTC export.

        Writes the low byte of wert to device address 0x0210, which the
        firmware reads and outputs to EZ-USB Port A (0x7F98).
        """
        if self._dev is None:
            raise RuntimeError("Device not initialized. Call INIT() first.")

        self._vendor_write(0x0210, wert & 0xFF)

    def close(self):
        """Release the USB device."""
        if self._dev is not None:
            usb.util.dispose_resources(self._dev)
            self._dev = None


# ── Module-level convenience API (matches original DLL calling convention) ──

_instance = None


def INIT():
    """Initialize the EZ-USB device (module-level wrapper)."""
    global _instance
    _instance = SERUSB()
    _instance.INIT()


def AD(eingang):
    """Read ADC value for channel `eingang` (module-level wrapper)."""
    if _instance is None:
        raise RuntimeError("INIT() has not been called.")
    return _instance.AD(eingang)


def OUTC(wert):
    """Write `wert` to the digital output (module-level wrapper)."""
    if _instance is None:
        raise RuntimeError("INIT() has not been called.")
    _instance.OUTC(wert)


# ── Main: self-test ──

if __name__ == "__main__":
    print("SERUSB Python replacement — self-test")
    print(f"Firmware size: {len(_FIRMWARE)} bytes")
    print(f"Looking for EZ-USB device VID=0x{EZUSB_VID:04X} PID=0x{EZUSB_PID:04X}...")

    try:
        INIT()
        print("INIT OK — firmware uploaded")

        for ch in range(8):
            val = AD(ch)
            print(f"  AD({ch}) = {val}")

        OUTC(0x00)
        print("OUTC(0x00) OK")

    except RuntimeError as e:
        print(f"Error: {e}")
    except usb.core.USBError as e:
        print(f"USB Error: {e}")
