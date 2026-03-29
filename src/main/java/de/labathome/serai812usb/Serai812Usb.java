package de.labathome.serai812usb;

import java.nio.ByteBuffer;

import org.usb4java.Context;
import org.usb4java.Device;
import org.usb4java.DeviceDescriptor;
import org.usb4java.DeviceHandle;
import org.usb4java.DeviceList;
import org.usb4java.LibUsb;
import org.usb4java.LibUsbException;

/**
 * Java driver for the Serai 8-12 ADC hardware.
 *
 * <p>Communicates with a Cypress/Anchor Chips EZ-USB (AN2131) device equipped
 * with a MAX186 12-bit 8-channel ADC. Provides ADC reading and digital output,
 * equivalent to the original SERUSB.dll interface:
 * <ul>
 *   <li>{@link #init()} — upload firmware and start the 8051</li>
 *   <li>{@link #ad(int)} — read a 12-bit ADC channel (0–7)</li>
 *   <li>{@link #outc(int)} — write to the digital output port</li>
 * </ul>
 *
 * <p>Hardware details:
 * <ul>
 *   <li>MAX186 connected via bit-banged SPI on Port A (SCLK=bit0, DIN=bit2, DOUT=bit4)</li>
 *   <li>Firmware memory layout at 0x0200–0x020F stores 8 channels × 2 bytes each</li>
 *   <li>Digital output at 0x0210 → Port A (0x7F98)</li>
 * </ul>
 */
public class Serai812Usb implements AutoCloseable {

	/** Cypress EZ-USB AN2131 default VID. */
	private static final short VENDOR_ID = 0x0547;

	/** Cypress EZ-USB AN2131 default PID. */
	private static final short PRODUCT_ID = 0x2131;

	/** USB vendor request code for EZ-USB RAM access. */
	private static final byte VENDOR_REQUEST = (byte) 0xA0;

	/** 8051 CPU reset register address. */
	private static final short CPUCS_ADDR = (short) 0x7F92;

	/** Timeout for USB control transfers in milliseconds. */
	private static final long TIMEOUT = 1000;

	/**
	 * Firmware extracted from the original SERUSB.dll (Intel HEX records).
	 * 256 bytes of 8051 code that performs ADC reads and DAC writes via
	 * the EZ-USB I/O ports.
	 */
	private static final String[] FIRMWARE_HEX_RECORDS = {
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
	};

	/** Pre-parsed firmware binary image. */
	private static final byte[] FIRMWARE;

	static {
		FIRMWARE = parseFirmware();
	}

	private Context context;
	private DeviceHandle handle;

	/**
	 * Parse Intel HEX records into a firmware binary image.
	 */
	private static byte[] parseFirmware() {
		byte[] firmware = new byte[256];
		int maxAddr = 0;

		for (String line : FIRMWARE_HEX_RECORDS) {
			// Skip leading ':', parse hex pairs
			String hex = line.substring(1);
			byte[] raw = new byte[hex.length() / 2];
			for (int i = 0; i < raw.length; i++) {
				raw[i] = (byte) Integer.parseInt(hex.substring(i * 2, i * 2 + 2), 16);
			}

			int length = raw[0] & 0xFF;
			int addr = ((raw[1] & 0xFF) << 8) | (raw[2] & 0xFF);
			int recType = raw[3] & 0xFF;

			if (recType == 0) { // data record
				for (int i = 0; i < length; i++) {
					firmware[addr + i] = raw[4 + i];
				}
				int end = addr + length;
				if (end > maxAddr) {
					maxAddr = end;
				}
			}
		}

		byte[] result = new byte[maxAddr];
		System.arraycopy(firmware, 0, result, 0, maxAddr);
		return result;
	}

	/**
	 * Write data to EZ-USB internal RAM via vendor request 0xA0.
	 */
	private void vendorWrite(short address, byte[] data) {
		ByteBuffer buffer = ByteBuffer.allocateDirect(data.length);
		buffer.put(data);
		buffer.rewind();
		int result = LibUsb.controlTransfer(handle,
			(byte) 0x40, // USB_DIR_OUT | USB_TYPE_VENDOR | USB_RECIP_DEVICE
			VENDOR_REQUEST,
			address,
			(short) 0,
			buffer,
			TIMEOUT);
		if (result < 0) {
			throw new LibUsbException("Vendor write failed", result);
		}
	}

	/**
	 * Write a single byte to EZ-USB internal RAM via vendor request 0xA0.
	 */
	private void vendorWrite(short address, byte value) {
		vendorWrite(address, new byte[] { value });
	}

	/**
	 * Read data from EZ-USB internal RAM via vendor request 0xA0.
	 */
	private byte[] vendorRead(short address, int length) {
		ByteBuffer buffer = ByteBuffer.allocateDirect(length);
		int result = LibUsb.controlTransfer(handle,
			(byte) 0xC0, // USB_DIR_IN | USB_TYPE_VENDOR | USB_RECIP_DEVICE
			VENDOR_REQUEST,
			address,
			(short) 0,
			buffer,
			TIMEOUT);
		if (result < 0) {
			throw new LibUsbException("Vendor read failed", result);
		}
		byte[] data = new byte[result];
		buffer.get(data);
		return data;
	}

	/**
	 * Assert or release the 8051 CPU reset.
	 */
	private void resetCpu(boolean holdReset) {
		vendorWrite(CPUCS_ADDR, holdReset ? (byte) 0x01 : (byte) 0x00);
	}

	/**
	 * Download firmware to EZ-USB 8051 internal RAM in 64-byte chunks.
	 */
	private void downloadFirmware() {
		int chunkSize = 64;
		for (int offset = 0; offset < FIRMWARE.length; offset += chunkSize) {
			int len = Math.min(chunkSize, FIRMWARE.length - offset);
			byte[] chunk = new byte[len];
			System.arraycopy(FIRMWARE, offset, chunk, 0, len);
			vendorWrite((short) offset, chunk);
		}
	}

	/**
	 * Find the EZ-USB device on the USB bus.
	 */
	private Device findDevice() {
		DeviceList list = new DeviceList();
		int result = LibUsb.getDeviceList(context, list);
		if (result < 0) {
			throw new LibUsbException("Unable to get device list", result);
		}

		try {
			for (Device device : list) {
				DeviceDescriptor descriptor = new DeviceDescriptor();
				result = LibUsb.getDeviceDescriptor(device, descriptor);
				if (result != LibUsb.SUCCESS) {
					throw new LibUsbException("Unable to read device descriptor", result);
				}
				if (descriptor.idVendor() == VENDOR_ID && descriptor.idProduct() == PRODUCT_ID) {
					return device;
				}
			}
		} finally {
			LibUsb.freeDeviceList(list, true);
		}

		return null;
	}

	/**
	 * Initialize the device: find it, open it, upload firmware, and start the 8051.
	 *
	 * @throws LibUsbException if any USB operation fails
	 * @throws RuntimeException if the device is not found
	 */
	public void init() {
		// Initialize libusb
		context = new Context();
		int result = LibUsb.init(context);
		if (result != LibUsb.SUCCESS) {
			throw new LibUsbException("Unable to initialize libusb", result);
		}

		// Find device
		Device device = findDevice();
		if (device == null) {
			LibUsb.exit(context);
			context = null;
			throw new RuntimeException(String.format(
				"EZ-USB device not found (VID=0x%04X, PID=0x%04X). Check USB connection and permissions.",
				VENDOR_ID & 0xFFFF, PRODUCT_ID & 0xFFFF));
		}

		// Open device
		handle = new DeviceHandle();
		result = LibUsb.open(device, handle);
		if (result != LibUsb.SUCCESS) {
			LibUsb.exit(context);
			context = null;
			throw new LibUsbException("Unable to open USB device", result);
		}

		// Detach kernel driver if active (Linux)
		try {
			if (LibUsb.kernelDriverActive(handle, 0) == 1) {
				LibUsb.detachKernelDriver(handle, 0);
			}
		} catch (LibUsbException e) {
			// Not supported on all platforms — ignore
		}

		// Step 1: Hold 8051 in reset
		resetCpu(true);
		sleep(10);

		// Step 2: Download firmware
		downloadFirmware();
		sleep(10);

		// Step 3: Release reset — 8051 starts executing firmware
		resetCpu(false);
	}

	/**
	 * Read 12-bit ADC value for the given input channel.
	 *
	 * <p>Channel mapping (input index → MAX186 channel):
	 * 0→CH0, 1→CH4, 2→CH1, 3→CH5, 4→CH2, 5→CH6, 6→CH3, 7→CH7.
	 *
	 * @param channel input channel number (0–7)
	 * @return 12-bit ADC value (0–4095)
	 */
	public int ad(int channel) {
		if (handle == null) {
			throw new IllegalStateException("Device not initialized. Call init() first.");
		}
		if (channel < 0 || channel > 7) {
			throw new IllegalArgumentException("Channel must be 0-7, got " + channel);
		}

		// Read 32 bytes from device address 0x01F0
		byte[] buf = vendorRead((short) 0x01F0, 0x20);

		// Reconstruct 12-bit ADC value from two bytes
		int idx = channel * 2;
		int msb = buf[idx + 0x10] & 0xFF; // R3: upper 8 bits (D11..D4)
		int lsb = buf[idx + 0x11] & 0xFF; // R4: lower 4 bits (D3..D0)
		return (msb << 4) | lsb;
	}

	/**
	 * Write a value to the digital output port.
	 *
	 * @param value output value (low byte used)
	 */
	public void outc(int value) {
		if (handle == null) {
			throw new IllegalStateException("Device not initialized. Call init() first.");
		}
		vendorWrite((short) 0x0210, (byte) (value & 0xFF));
	}

	/**
	 * Release USB resources.
	 */
	@Override
	public void close() {
		if (handle != null) {
			LibUsb.close(handle);
			handle = null;
		}
		if (context != null) {
			LibUsb.exit(context);
			context = null;
		}
	}

	private static void sleep(int millis) {
		try {
			Thread.sleep(millis);
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
		}
	}
}
