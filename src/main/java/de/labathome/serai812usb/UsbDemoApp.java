package de.labathome.serai812usb;

public class UsbDemoApp {

	public static void main(String[] args) {
		System.out.println("Serai 8-12 USB — Java driver demo");

		try (Serai812Usb device = new Serai812Usb()) {
			device.init();
			System.out.println("INIT OK — firmware uploaded");

			for (int ch = 0; ch < 8; ch++) {
				int val = device.ad(ch);
				System.out.printf("  AD(%d) = %d%n", ch, val);
			}

			device.outc(0x00);
			System.out.println("OUTC(0x00) OK");
		}
	}
}
