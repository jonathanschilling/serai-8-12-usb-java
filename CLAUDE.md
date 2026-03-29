# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project

Java USB interface library for the Serai 8-12 ADC hardware (EZ-USB/Cypress AN2131 microcontroller with MAX186 12-bit ADC). Uses usb4java (libusb wrapper) to communicate with the device (vendor `0x0547`, product `0x2131`).

## Build

```bash
mvn clean package    # build JAR
mvn compile          # compile only
```

## Run

```bash
java -cp target/serai-8-12-usb-java-1.0.0.jar de.labathome.serai812usb.UsbDemoApp
```

Requires Linux udev rules installed (`42-serai-usb.rules` → `/etc/udev/rules.d/`) with the correct OWNER set. Device must be reconnected after rule installation.

## Architecture

- **Single source file:** `src/main/java/de/labathome/serai812usb/UsbDemoApp.java` — demo app that initializes libusb, finds the device on the USB bus, and obtains a handle.
- **Reference implementation:** `serusb.py` documents the full hardware protocol (firmware upload via vendor request 0xA0, 8051 reset, MAX186 SPI bit-banging, ADC memory layout at 0x0200–0x020F, digital output at 0x0210). Use this as the authoritative protocol reference when extending the Java code.
- **Dependency:** `org.usb4java:usb4java:1.3.0` (declared in `pom.xml`, parent POM `de.labathome:de-labathome-parent:1.0.2`).
- No tests exist.
