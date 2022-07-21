# serai-8-12-usb-java
USB interface for Serai 8-12 ADC hardware

Useful links:
* usb4java info: http://usb4java.org/quickstart/libusb.html
* hardware description: https://www.ak-modul-bus.de/stat/serai_8_12_interface_usb.html and http://www.elexs.de/modulbus/technik/serusb.html

`42-serai-usb.rules` needs to be copied into `/etc/udev/rules.d/` and the `OWNER` tag needs to match your username.
Then disconnect and reconnect the USB device and verify (via `ls /dev/bus/usb/.....`; find path via `lsusb`) that you actually own the device.
