#!/usr/bin/env python3
import struct, hashlib, zlib, sys
from pathlib import Path

# Tiny DEX 035 writer for one Activity class. No Android SDK is needed.
# Java-equivalent class:
# public final class MainActivity extends Activity implements View.OnClickListener {
#   static { System.loadLibrary("luckyhang"); }
#   public MainActivity() { super(); }
#   protected void onCreate(Bundle b) { super.onCreate(b); nativeOnCreate(this); }
#   protected void onResume() { super.onResume(); nativeOnResume(this); }
#   public void onClick(View v) { nativeOnClick(this); }
#   public static native void nativeOnCreate(MainActivity a);
#   public static native void nativeOnResume(MainActivity a);
#   public static native void nativeOnClick(MainActivity a);
# }

U32=lambda x: struct.pack('<I',x)
U16=lambda x: struct.pack('<H',x)

def uleb(n):
    out=bytearray()
    while True:
        b=n&0x7f; n>>=7
        if n: out.append(b|0x80)
        else: out.append(b); return bytes(out)

def align4(b):
    while len(b)%4: b.append(0)

def mutf8(s):
    # All build-time strings are ASCII, so standard UTF-8 == MUTF-8.
    raw=s.encode('utf-8')
    return uleb(len(s.encode('utf-16-le'))//2)+raw+b'\0'

MAIN='Lcom/lucky/screenhang/v13/MainActivity;'
ACT='Landroid/app/Activity;'
BUNDLE='Landroid/os/Bundle;'
VIEW='Landroid/view/View;'
CLICK='Landroid/view/View$OnClickListener;'
SYSTEM='Ljava/lang/System;'
STRING='Ljava/lang/String;'
VOID='V'

method_specs = [
    (MAIN,'<clinit>',VOID,()),
    (MAIN,'<init>',VOID,()),
    (MAIN,'nativeOnClick',VOID,(MAIN,)),
    (MAIN,'nativeOnCreate',VOID,(MAIN,)),
    (MAIN,'nativeOnResume',VOID,(MAIN,)),
    (MAIN,'onClick',VOID,(VIEW,)),
    (MAIN,'onCreate',VOID,(BUNDLE,)),
    (MAIN,'onResume',VOID,()),
    (ACT,'<init>',VOID,()),
    (ACT,'onCreate',VOID,(BUNDLE,)),
    (ACT,'onResume',VOID,()),
    (SYSTEM,'loadLibrary',VOID,(STRING,)),
]

strings=set(['luckyhang'])
for c,n,r,p in method_specs:
    strings.update([c,n,r]); strings.update(p)
strings.update([ACT,CLICK])
# shorty strings
for _,_,r,p in method_specs:
    strings.add('V'+''.join('L' if x.startswith('L') or x.startswith('[') else x for x in p))
strings=sorted(strings)
sidx={s:i for i,s in enumerate(strings)}

types=sorted({ACT,BUNDLE,VIEW,CLICK,MAIN,SYSTEM,STRING,VOID}, key=lambda x:sidx[x])
tidx={t:i for i,t in enumerate(types)}

def proto_key(spec):
    r,p=spec
    return (tidx[r], tuple(tidx[x] for x in p))
protos=sorted({(r,p) for _,_,r,p in method_specs}, key=proto_key)
pidx={p:i for i,p in enumerate(protos)}

methods=sorted(method_specs, key=lambda m:(tidx[m[0]], sidx[m[1]], pidx[(m[2],m[3])]))
midx={m:i for i,m in enumerate(methods)}

def M(c,n,r,p): return midx[(c,n,r,p)]

# Fixed-size sections.
HDR=0x70
string_ids_off=HDR
string_ids_size=len(strings)
type_ids_off=string_ids_off+4*string_ids_size
type_ids_size=len(types)
proto_ids_off=type_ids_off+4*type_ids_size
proto_ids_size=len(protos)
field_ids_off=0; field_ids_size=0
method_ids_off=proto_ids_off+12*proto_ids_size
method_ids_size=len(methods)
class_defs_off=method_ids_off+8*method_ids_size
class_defs_size=1
data_off=class_defs_off+32
if data_off%4: data_off=(data_off+3)&~3

data=bytearray()

def data_align4(): align4(data)

def put_type_list(seq):
    if not seq: return 0
    data_align4(); off=data_off+len(data)
    data.extend(U32(len(seq)))
    for t in seq: data.extend(U16(tidx[t]))
    if len(seq)&1: data.extend(U16(0))
    return off

# Parameter type lists shared by protos.
param_off={}
for r,p in protos:
    param_off[p]=put_type_list(p) if p else 0

# Interface list.
interfaces_off=put_type_list((CLICK,))

# Code items. Each item aligned to 4 bytes.
code_off={}
def emit_code(key, registers, ins, outs, insns):
    data_align4(); off=data_off+len(data); code_off[key]=off
    data.extend(struct.pack('<HHHHII',registers,ins,outs,0,0,len(insns)))
    for w in insns: data.extend(U16(w))

# Dalvik instruction helpers (16-bit code units).
def invoke(op, method_index, regs):
    # format 35c, supports <=5 4-bit regs. A=count, G=fifth.
    rr=list(regs)+[0]*5
    A=len(regs); C,D,E,F,G=rr[0],rr[1],rr[2],rr[3],rr[4]
    return [op | (G<<8) | (A<<12), method_index, C | (D<<4) | (E<<8) | (F<<12)]

# <clinit>: const-string v0,"luckyhang"; invoke-static; return-void
emit_code((MAIN,'<clinit>',VOID,()), 1,0,1,
          [0x001a, sidx['luckyhang']] + invoke(0x71,M(SYSTEM,'loadLibrary',VOID,(STRING,)),[0]) + [0x000e])
# <init>
emit_code((MAIN,'<init>',VOID,()), 1,1,1,
          invoke(0x70,M(ACT,'<init>',VOID,()),[0]) + [0x000e])
# onCreate(this=v0,bundle=v1)
emit_code((MAIN,'onCreate',VOID,(BUNDLE,)), 2,2,2,
          invoke(0x6f,M(ACT,'onCreate',VOID,(BUNDLE,)),[0,1]) +
          invoke(0x71,M(MAIN,'nativeOnCreate',VOID,(MAIN,)),[0]) + [0x000e])
# onResume(this=v0)
emit_code((MAIN,'onResume',VOID,()), 1,1,1,
          invoke(0x6f,M(ACT,'onResume',VOID,()),[0]) +
          invoke(0x71,M(MAIN,'nativeOnResume',VOID,(MAIN,)),[0]) + [0x000e])
# onClick(this=v0, view=v1)
emit_code((MAIN,'onClick',VOID,(VIEW,)), 2,2,1,
          invoke(0x71,M(MAIN,'nativeOnClick',VOID,(MAIN,)),[0]) + [0x000e])

# String data items.
string_data_off={}
for s in strings:
    string_data_off[s]=data_off+len(data)
    data.extend(mutf8(s))

# Class data must reference code offsets; encode after code/string data.
class_data_off=data_off+len(data)

def method_entry(prev, spec, flags, code=0):
    mi=midx[spec]
    return mi, uleb(mi-prev)+uleb(flags)+uleb(code)

direct=[
    ((MAIN,'<clinit>',VOID,()), 0x10008, code_off[(MAIN,'<clinit>',VOID,())]),
    ((MAIN,'<init>',VOID,()), 0x10001, code_off[(MAIN,'<init>',VOID,())]),
    ((MAIN,'nativeOnClick',VOID,(MAIN,)), 0x0109, 0),
    ((MAIN,'nativeOnCreate',VOID,(MAIN,)), 0x0109, 0),
    ((MAIN,'nativeOnResume',VOID,(MAIN,)), 0x0109, 0),
]
virtual=[
    ((MAIN,'onClick',VOID,(VIEW,)), 0x0001, code_off[(MAIN,'onClick',VOID,(VIEW,))]),
    ((MAIN,'onCreate',VOID,(BUNDLE,)), 0x0004, code_off[(MAIN,'onCreate',VOID,(BUNDLE,))]),
    ((MAIN,'onResume',VOID,()), 0x0004, code_off[(MAIN,'onResume',VOID,())]),
]
direct.sort(key=lambda x:midx[x[0]]); virtual.sort(key=lambda x:midx[x[0]])
cd=bytearray(); cd+=uleb(0)+uleb(0)+uleb(len(direct))+uleb(len(virtual))
prev=0
for spec,flags,co in direct:
    mi,enc=method_entry(prev,spec,flags,co); cd+=enc; prev=mi
prev=0
for spec,flags,co in virtual:
    mi,enc=method_entry(prev,spec,flags,co); cd+=enc; prev=mi
data.extend(cd)

# Map list last, aligned.
data_align4(); map_off=data_off+len(data)
map_items=[]
def addmap(t,size,off):
    if size: map_items.append((off,t,size))
addmap(0x0000,1,0)
addmap(0x0001,string_ids_size,string_ids_off)
addmap(0x0002,type_ids_size,type_ids_off)
addmap(0x0003,proto_ids_size,proto_ids_off)
addmap(0x0005,method_ids_size,method_ids_off)
addmap(0x0006,class_defs_size,class_defs_off)
# Data item counts by unique offsets/types.
type_list_offsets=sorted({o for o in list(param_off.values())+[interfaces_off] if o})
addmap(0x1001,len(type_list_offsets),type_list_offsets[0] if type_list_offsets else 0)
addmap(0x2001,len(code_off),min(code_off.values()))
addmap(0x2002,len(strings),min(string_data_off.values()))
addmap(0x2000,1,class_data_off)
addmap(0x1000,1,map_off)
map_items.sort()
data.extend(U32(len(map_items)))
for off,t,size in map_items:
    data.extend(struct.pack('<HHII',t,0,size,off))

file_size=data_off+len(data)
data_size=len(data)

# Assemble fixed section tables.
out=bytearray(b'\0'*HDR)
# string_ids
for s in strings: out.extend(U32(string_data_off[s]))
# type_ids
for t in types: out.extend(U32(sidx[t]))
# proto_ids
for r,p in protos:
    shorty='V'+''.join('L' if x.startswith('L') or x.startswith('[') else x for x in p)
    out.extend(struct.pack('<III',sidx[shorty],tidx[r],param_off[p]))
# method_ids
for c,n,r,p in methods:
    out.extend(struct.pack('<HHI',tidx[c],pidx[(r,p)],sidx[n]))
# class_def
out.extend(struct.pack('<IIIIIIII',
    tidx[MAIN],0x0001,tidx[ACT],interfaces_off,0xffffffff,0,class_data_off,0))
while len(out)<data_off: out.append(0)
out.extend(data)
assert len(out)==file_size

# Header.
struct.pack_into('<8sI20s20I',out,0,
    b'dex\n035\0',0,b'\0'*20,
    file_size,0x70,0x12345678,0,0,map_off,
    string_ids_size,string_ids_off,
    type_ids_size,type_ids_off,
    proto_ids_size,proto_ids_off,
    field_ids_size,field_ids_off,
    method_ids_size,method_ids_off,
    class_defs_size,class_defs_off,
    data_size,data_off)
# SHA-1 covers bytes from file_size field (offset 32) to EOF.
sig=hashlib.sha1(out[32:]).digest(); out[12:32]=sig
# Adler32 covers signature onward (offset 12).
chk=zlib.adler32(out[12:]) & 0xffffffff; struct.pack_into('<I',out,8,chk)

path=Path(sys.argv[1]); path.write_bytes(out)
print(f'wrote {path}: {len(out)} bytes, strings={len(strings)}, methods={len(methods)}, checksum={chk:08x}')
