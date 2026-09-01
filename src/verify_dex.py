#!/usr/bin/env python3
import struct, sys
from pathlib import Path

p=Path(sys.argv[1]); b=p.read_bytes()
if b[:8] != b'dex\n035\0': raise SystemExit('unsupported dex magic')
map_off=struct.unpack_from('<I',b,52)[0]
count=struct.unpack_from('<I',b,map_off)[0]
code_count=code_off=None
for i in range(count):
    typ,unused,size,off=struct.unpack_from('<HHII',b,map_off+4+i*12)
    if typ==0x2001:
        code_count,code_off=size,off
        break
if code_off is None: raise SystemExit('no code_item map')

def a4(x): return (x+3)&~3
pos=code_off
invokes=[]
for ci in range(code_count):
    pos=a4(pos)
    registers,ins,outs,tries,debug,insns_size=struct.unpack_from('<HHHHII',b,pos)
    units=list(struct.unpack_from('<%dH'%insns_size,b,pos+16))
    pc=0
    while pc < len(units):
        w=units[pc]; op=w&0xff
        if op==0x1a: width=2
        elif op in (0x6e,0x6f,0x70,0x71,0x72):
            if pc+2>=len(units): raise SystemExit(f'truncated invoke in code item {ci}')
            A=(w>>12)&0xf; G=(w>>8)&0xf
            method_idx=units[pc+1]
            regs=units[pc+2]
            invokes.append((ci,pc,op,A,G,method_idx,regs))
            if not (1 <= A <= 5):
                raise SystemExit(f'BAD invoke-35c in code item {ci} pc {pc}: count A={A}, G={G}, word=0x{w:04x}')
            width=3
        elif op==0x0e: width=1
        else:
            raise SystemExit(f'unknown opcode 0x{op:02x} in generated dex code item {ci} pc {pc}')
        pc += width
    pos=pos+16+insns_size*2
    if tries and insns_size&1: pos+=2
    if tries: raise SystemExit('generated dex unexpectedly has try blocks')
print(f'OK: {p.name}: {code_count} code items, {len(invokes)} invoke-35c instructions')
for x in invokes:
    print('  code_item=%d pc=%d op=0x%02x A=%d G=%d method=%d regs=0x%04x'%x)
