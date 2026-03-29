# Makefile for Serai 8-12 EZ-USB firmware
# Requires: sdcc (provides sdas8051, sdld, makebin)

AS      = sdas8051
LD      = sdld
MAKEBIN = makebin

SRC     = serai812.asm
REL     = serai812.rel
IHX     = serai812.ihx
BIN     = serai812.bin
LST     = serai812.lst
SYM     = serai812.sym
RST     = serai812.rst
MAP     = serai812.map
MEM     = serai812.mem
LK      = serai812.lk

FW_SIZE = 256

all: $(BIN)

$(REL): $(SRC)
	$(AS) -ols $(SRC)

$(IHX): $(REL)
	$(LD) -i -o $(IHX) $(REL)

$(BIN): $(IHX)
	$(MAKEBIN) -s $(FW_SIZE) $(IHX) $(BIN)

clean:
	rm -f $(REL) $(IHX) $(BIN) $(LST) $(SYM) $(RST) $(MAP) $(MEM) $(LK)

.PHONY: all clean
