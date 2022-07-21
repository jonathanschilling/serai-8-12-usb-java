package de.labathome.serai812usb;

import org.usb4java.Context;
import org.usb4java.Device;
import org.usb4java.DeviceDescriptor;
import org.usb4java.DeviceHandle;
import org.usb4java.DeviceList;
import org.usb4java.LibUsb;
import org.usb4java.LibUsbException;

public class UsbDemoApp {

	private static Context context;

	public static void main(String[] args) {

		UsbDemoApp app = new UsbDemoApp();

		app.initLibUsb();

		short vendorId = 0x0547;
		short productId = 0x2131;
		Device device = app.findDevice(vendorId, productId);

		if (device != null) {
			System.out.println("found device!");
		}

		DeviceHandle handle = new DeviceHandle();
		int result = LibUsb.open(device, handle);
		if (result != LibUsb.SUCCESS) {
			throw new LibUsbException("Unable to open USB device", result);
		}

		try {
			// Use device handle here

			System.out.println("got handle!");



		} finally {
			LibUsb.close(handle);
			System.out.println("could close handle");
		}

		app.closeLibUsb();
		System.out.println("could close libusb");
	}

	public void initLibUsb() {
		context = new Context();

		int result = LibUsb.init(context);
		if (result != LibUsb.SUCCESS) {
			throw new LibUsbException("Unable to initialize libusb.", result);
		}
	}

	public void closeLibUsb() {
		LibUsb.exit(context);
	}

	public Device findDevice(short vendorId, short productId) {

		// Read the USB device list
		DeviceList list = new DeviceList();
		int result = LibUsb.getDeviceList(context, list);
		if (result < 0) {
			throw new LibUsbException("Unable to get device list", result);
		}

		try {
			// Iterate over all devices and scan for the right one
			for (Device device : list) {
				DeviceDescriptor descriptor = new DeviceDescriptor();
				result = LibUsb.getDeviceDescriptor(device, descriptor);
				if (result != LibUsb.SUCCESS) {
					throw new LibUsbException("Unable to read device descriptor", result);
				}

				if (descriptor.idVendor() == vendorId && descriptor.idProduct() == productId) {
					return device;
				}
			}
		} finally {
			// Ensure the allocated device list is freed
			LibUsb.freeDeviceList(list, true);
		}

		// Device not found
		return null;
	}
}
