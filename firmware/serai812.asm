; =============================================================================
; Serai 8-12 ADC Firmware for Cypress EZ-USB AN2131 (8051 core)
; =============================================================================
;
; This firmware runs on the 8051 core inside the Cypress/Anchor Chips EZ-USB
; AN2131 microcontroller. It continuously:
;   1. Reads all 8 channels of a MAX186 12-bit ADC via bit-banged SPI
;   2. Stores the results in external RAM at 0x0200-0x020F
;   3. Reads a digital output byte from 0x0210 and writes it to Port A
;
; MAX186 SPI wiring on EZ-USB Port A (accent accent accent accent accent 0x7F98):
;   Bit 0 = SCLK  (SPI clock)
;   Bit 2 = DIN   (MOSI -> MAX186 control byte input)
;   Bit 4 = DOUT  (MISO <- MAX186 ADC data output)
;
; EZ-USB I/O register addresses (accent accent accent accessed via MOVX):
;   0x7F97 = OEA    (Port A Output Enable, accent accent bit=1 means output)
;   0x7F98 = OUTA   (Port A Output Data)
;   0x7F9A = PINSA  (Port A Pin State, read-only)
;   0x7F9D = OEC    (Port C Output Enable)
;   0x7F9E = OUTC   (Port C Output Data)
;
; Memory layout for ADC results (external RAM):
;   0x0200: Eingang 0 MSB (CH0, ctrl 0x8E)
;   0x0201: Eingang 0 LSB
;   0x0202: Eingang 1 MSB (CH4, ctrl 0xCE)
;   0x0203: Eingang 1 LSB
;   0x0204: Eingang 2 MSB (CH1, ctrl 0x9E)
;   0x0205: Eingang 2 LSB
;   0x0206: Eingang 3 MSB (CH5, ctrl 0xDE)
;   0x0207: Eingang 3 LSB
;   0x0208: Eingang 4 MSB (CH2, ctrl 0xAE)
;   0x0209: Eingang 4 LSB
;   0x020A: Eingang 5 MSB (CH6, ctrl 0xEE)
;   0x020B: Eingang 5 LSB
;   0x020C: Eingang 6 MSB (CH3, ctrl 0xBE)
;   0x020D: Eingang 6 LSB
;   0x020E: Eingang 7 MSB (CH7, ctrl 0xFE)
;   0x020F: Eingang 7 LSB
;
; Each ADC result is stored as two bytes:
;   MSB = R3 = upper 8 bits of 12-bit result (D11..D4)
;   LSB = R4 = lower 4 bits of 12-bit result (D3..D0)
;   Full value = (MSB << 4) | LSB, range 0-4095
;
; Digital output:
;   0x0210: byte written here by USB host is output to Port A (0x7F98)
;
; MAX186 control byte format (MSB first):
;   Bit 7:   START (always 1)
;   Bit 6-4: channel select (SEL2, SEL1, SEL0)
;   Bit 3:   UNI/BIP (1 = unipolar)
;   Bit 2:   SGL/DIF (1 = single-ended)
;   Bit 1:   PD1 (power down)
;   Bit 0:   PD0 (power down)
;   All channels here use: unipolar, single-ended, normal power = 0bXXX_1110 = 0x_E
; =============================================================================

.area CODE (ABS)
.org 0x0000

; EZ-USB register addresses
OEA     = 0x7F97        ; Port A Output Enable
OUTA    = 0x7F98        ; Port A Output Data
PINSA   = 0x7F9A        ; Port A Pin State (read)
OEC     = 0x7F9D        ; Port C Output Enable
OUTC    = 0x7F9E        ; Port C Output Data

; SPI bit positions on Port A
;   SCLK = bit 0, DIN = bit 2, DOUT = bit 4
; OEA value: bits 0,1,2 as output = 0x07
; For sending control byte: DIN bit (bit 2) = 0x04

; ADC result storage base address
ADC_BASE = 0x0200

; Digital output mailbox address
DOUT_ADDR = 0x0210

; =============================================================================
; Entry point
; =============================================================================
main:
        lcall   port_init       ; configure port directions
        lcall   portc_init      ; set Port C outputs high
main_loop:
        lcall   read_all_adc    ; read all 8 ADC channels + handle digital out
        sjmp    main_loop       ; repeat forever

; =============================================================================
; portc_init: Set all Port C pins as outputs, drive high (0xFF)
; =============================================================================
portc_init:
        mov     dptr, #OUTC
        mov     a, #0xFF
        movx    @dptr, a
        ret

; =============================================================================
; port_init: Configure Port A direction
;   Bits 0,1,2 as output (SCLK, unused, DIN) -> OEA = 0x07
;   Set OEA to 0x02 initially (only bit 1 as output for idle state)
;   Then set to 0x02 ... actually the firmware sets OEA = 0x07 for SPI,
;   and OEA = 0x02 for tristate/read mode.
;   Initial: OEC = 0x07 (bits 0-2 output), OEA = 0x02 (for Port A idle)
; =============================================================================
port_init:
        mov     dptr, #OEC
        mov     a, #0x07        ; Port C: bits 0-2 as output
        movx    @dptr, a
        mov     dptr, #OEA
        mov     a, #0x02        ; Port A: bit 1 as output (idle)
        movx    @dptr, a
        ret

; =============================================================================
; delay: Software delay loop
;   On entry: A = outer loop count
;   Destroys: A, R1
;   Total iterations: A * 256 (inner loop via R1)
; =============================================================================
delay:
        mov     r1, #0xFF
delay_inner:
        djnz    r1, delay_inner
        dec     a
        jnz     delay
        ret

; =============================================================================
; spi_read_adc: Perform one full MAX186 ADC conversion via bit-banged SPI
;
;   On entry: A = MAX186 control byte (e.g. 0x8E for CH0)
;   On exit:  R3 = upper 8 bits of 12-bit result (D11..D4)
;             R4 = lower 4 bits of 12-bit result (D3..D0)
;
; Protocol:
;   1. Clock out 8-bit control byte on DIN (bit 2), MSB first
;      - For each bit: set DIN, pulse SCLK high then low
;   2. Wait for conversion (~30 SCLK-equivalent delay cycles)
;   3. Clock in 8 upper result bits from DOUT (bit 4) into R3
;   4. Clock in 4 lower result bits from DOUT (bit 4) into R4
;   5. Restore OEA to idle (0x02)
; =============================================================================
spi_read_adc:
        mov     r3, a           ; save control byte in R3

        ; --- Phase 1: Clock out 8-bit control byte ---
        mov     dptr, #OEA
        mov     a, 0x00         ; read current R0 (direct address 0x00)
        movx    @dptr, a        ; (restores OEA — R0 presumably holds 0x07 leftover)
        mov     a, #0x00        ; start with OUTA = 0 (SCLK=0, DIN=0)
        mov     r2, #0x08       ; 8 bits to send

spi_send_bit:
        xch     a, r3           ; A = control byte, R3 = port value
        jnb     acc.7, spi_din_low  ; test MSB of control byte
        mov     r3, #0x04       ; DIN=1 (bit 2) if control bit is 1
spi_din_low:
        xch     a, r3           ; A = port value (0x00 or 0x04), R3 = control byte
        movx    @dptr, a        ; write to OUTA: SCLK=0, DIN=bit
        inc     a               ; set SCLK=1 (bit 0)
        movx    @dptr, a        ; write to OUTA: SCLK=1, DIN=bit
        dec     a               ; clear SCLK=0
        movx    @dptr, a        ; write to OUTA: SCLK=0, DIN=bit
        mov     a, #0x00        ; reset port value for next bit
        xch     a, r3           ; A = control byte
        rl      a               ; shift left — next bit becomes MSB
        xch     a, r3           ; R3 = shifted control byte, A = 0x00
        djnz    r2, spi_send_bit

        ; --- Conversion delay ---
        mov     r2, #0x1E       ; ~30 iterations settling time
spi_conv_wait:
        djnz    r2, spi_conv_wait

        ; --- Phase 2: Clock in 8 upper result bits (D11..D4) into R3 ---
        mov     r3, #0x00       ; clear result MSB
        mov     r2, #0x08       ; 8 bits to read

spi_read_msb:
        xch     a, r3           ; A = partial result
        rl      a               ; shift left to make room for new bit
        xch     a, r3           ; R3 = shifted result
        mov     dptr, #OEA
        mov     a, #0x01        ; SCLK=1 (only SCLK as output)
        movx    @dptr, a
        mov     a, #0x00        ; SCLK=0
        movx    @dptr, a
        mov     dptr, #PINSA
        movx    a, @dptr        ; read Port A pins
        jnb     acc.4, spi_msb_zero  ; test DOUT (bit 4)
        inc     r3              ; DOUT=1: set LSB of result
spi_msb_zero:
        djnz    r2, spi_read_msb

        ; --- Phase 3: Clock in 4 lower result bits (D3..D0) into R4 ---
        mov     r4, #0x00       ; clear result LSB
        mov     r2, #0x04       ; 4 bits to read

spi_read_lsb:
        xch     a, r4           ; A = partial result
        rl      a               ; shift left
        xch     a, r4           ; R4 = shifted result
        mov     dptr, #OEA
        mov     a, #0x01        ; SCLK=1
        movx    @dptr, a
        mov     a, #0x00        ; SCLK=0
        movx    @dptr, a
        mov     dptr, #PINSA
        movx    a, @dptr        ; read Port A pins
        jnb     acc.4, spi_lsb_zero  ; test DOUT (bit 4)
        inc     r4              ; DOUT=1: set LSB of result
spi_lsb_zero:
        djnz    r2, spi_read_lsb

        ; --- Restore Port A to idle ---
        mov     dptr, #OEA
        mov     a, #0x02        ; only bit 1 as output (idle)
        movx    @dptr, a
        ret

; =============================================================================
; read_all_adc: Read all 8 MAX186 channels and handle digital output
;
; Reads channels in this order (matching original DLL convention):
;   Eingang 0 -> CH0 (ctrl 0x8E) -> store at 0x0200
;   Eingang 1 -> CH4 (ctrl 0xCE) -> store at 0x0202
;   Eingang 2 -> CH1 (ctrl 0x9E) -> store at 0x0204
;   Eingang 3 -> CH5 (ctrl 0xDE) -> store at 0x0206
;   Eingang 4 -> CH2 (ctrl 0xAE) -> store at 0x0208
;   Eingang 5 -> CH6 (ctrl 0xEE) -> store at 0x020A
;   Eingang 6 -> CH3 (ctrl 0xBE) -> store at 0x020C
;   Eingang 7 -> CH7 (ctrl 0xFE) -> store at 0x020E
;
; Also writes a sentinel byte (0x01) to 0x01F8, reads the digital output
; mailbox at 0x0210, writes it to Port A, and performs a short delay.
; =============================================================================
read_all_adc:
        ; --- Eingang 0: CH0 ---
        mov     a, #0x8E        ; MAX186 ctrl: CH0, unipolar, single-ended
        lcall   spi_read_adc
        mov     a, r3
        mov     dptr, #(ADC_BASE + 0)
        movx    @dptr, a        ; store MSB
        mov     a, r4
        inc     dptr
        movx    @dptr, a        ; store LSB

        ; --- Eingang 1: CH4 ---
        mov     a, #0xCE
        lcall   spi_read_adc
        mov     a, r3
        mov     dptr, #(ADC_BASE + 2)
        movx    @dptr, a
        mov     a, r4
        inc     dptr
        movx    @dptr, a

        ; --- Eingang 2: CH1 ---
        mov     a, #0x9E
        lcall   spi_read_adc
        mov     a, r3
        mov     dptr, #(ADC_BASE + 4)
        movx    @dptr, a
        mov     a, r4
        inc     dptr
        movx    @dptr, a

        ; --- Eingang 3: CH5 ---
        mov     a, #0xDE
        lcall   spi_read_adc
        mov     a, r3
        mov     dptr, #(ADC_BASE + 6)
        movx    @dptr, a
        mov     a, r4
        inc     dptr
        movx    @dptr, a

        ; --- Eingang 4: CH2 ---
        mov     a, #0xAE
        lcall   spi_read_adc
        mov     a, r3
        mov     dptr, #(ADC_BASE + 8)
        movx    @dptr, a
        mov     a, r4
        inc     dptr
        movx    @dptr, a

        ; --- Sentinel byte ---
        mov     a, #0x01
        mov     dptr, #0x01F8
        movx    @dptr, a        ; write 0x01 to 0x01F8 (signals data ready)

        ; --- Eingang 5: CH6 ---
        mov     a, #0xEE
        lcall   spi_read_adc
        mov     a, r3
        mov     dptr, #(ADC_BASE + 10)
        movx    @dptr, a
        mov     a, r4
        inc     dptr
        movx    @dptr, a

        ; --- Eingang 6: CH3 ---
        mov     a, #0xBE
        lcall   spi_read_adc
        mov     a, r3
        mov     dptr, #(ADC_BASE + 12)
        movx    @dptr, a
        mov     a, r4
        inc     dptr
        movx    @dptr, a

        ; --- Eingang 7: CH7 ---
        mov     a, #0xFE
        lcall   spi_read_adc
        mov     a, r3
        mov     dptr, #(ADC_BASE + 14)
        movx    @dptr, a
        mov     a, r4
        inc     dptr
        movx    @dptr, a

        ; --- Digital output ---
        mov     dptr, #DOUT_ADDR
        movx    a, @dptr        ; read digital output byte from USB host
        mov     dptr, #OUTA
        movx    @dptr, a        ; write it to Port A

        ; --- Short delay before next cycle ---
        mov     a, #0x01
        lcall   delay
        ret
