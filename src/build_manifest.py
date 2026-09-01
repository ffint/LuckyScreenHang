#!/usr/bin/env python3
import struct, sys
from pathlib import Path
NO=0xFFFFFFFF; UTF8_FLAG=0x00000100; TYPE_STRING=0x03; TYPE_INT_DEC=0x10; TYPE_INT_BOOLEAN=0x12
RID={'versionCode':0x0101021b,'versionName':0x0101021c,'minSdkVersion':0x0101020c,'targetSdkVersion':0x01010270,'label':0x01010001,'hasCode':0x0101000c,'name':0x01010003,'exported':0x01010010}
strings=[]
def s(x):
    if x not in strings: strings.append(x)
    return strings.index(x)
for x in ['android','http://schemas.android.com/apk/res/android','manifest','package','versionCode','versionName','com.lucky.screenhang.v13','1.3.3','uses-sdk','minSdkVersion','targetSdkVersion','uses-permission','android.permission.SYSTEM_ALERT_WINDOW','queries','moe.shizuku.privileged.api','application','label','hasCode','Lucky 息屏挂机 v1.3.3','activity','name','exported','com.lucky.screenhang.v13.MainActivity','intent-filter','action','android.intent.action.MAIN','category','android.intent.category.LAUNCHER']:
    s(x)
ANDROID_URI=s('http://schemas.android.com/apk/res/android'); ANDROID_PREFIX=s('android')
def enc_len(n): return bytes([n]) if n<=0x7f else bytes([(n>>8)|0x80,n&0xff])
def string_pool():
    data=bytearray(); offs=[]
    for text in strings:
        offs.append(len(data)); u8=text.encode(); u16=len(text.encode('utf-16le'))//2
        data+=enc_len(u16)+enc_len(len(u8))+u8+b'\0'
    while len(data)%4: data+=b'\0'
    hs=28; ss=hs+4*len(strings); size=ss+len(data)
    out=bytearray(struct.pack('<HHI',1,hs,size)); out+=struct.pack('<IIIII',len(strings),0,UTF8_FLAG,ss,0)
    out+=b''.join(struct.pack('<I',o) for o in offs); out+=data; return out
def resource_map():
    vals=[0]*len(strings)
    for name,rid in RID.items(): vals[s(name)]=rid
    while vals and vals[-1]==0: vals.pop()
    return struct.pack('<HHI',0x0180,8,8+4*len(vals))+b''.join(struct.pack('<I',v) for v in vals)
def nh(t,size,line=1): return struct.pack('<HHIII',t,16,size,line,NO)
def ns_start(line=1): return nh(0x0100,24,line)+struct.pack('<II',ANDROID_PREFIX,ANDROID_URI)
def ns_end(line=99): return nh(0x0101,24,line)+struct.pack('<II',ANDROID_PREFIX,ANDROID_URI)
def aval(ns,name,raw,dtype,data): return struct.pack('<IIIHBBI',ns,s(name),raw,8,0,dtype,data)
def astr(name,value,android=True):
    idx=s(value); return aval(ANDROID_URI if android else NO,name,idx,TYPE_STRING,idx)
def aint(name,value): return aval(ANDROID_URI,name,NO,TYPE_INT_DEC,value)
def abool(name,value): return aval(ANDROID_URI,name,NO,TYPE_INT_BOOLEAN,0xffffffff if value else 0)
def start(tag,attrs=(),line=1):
    attrs=list(attrs); ext=struct.pack('<IIHHHHHH',NO,s(tag),20,20,len(attrs),0,0,0); size=16+len(ext)+20*len(attrs)
    return nh(0x0102,size,line)+ext+b''.join(attrs)
def end(tag,line=1): return nh(0x0103,24,line)+struct.pack('<II',NO,s(tag))
chunks=[ns_start(1)]
chunks += [start('manifest',[astr('package','com.lucky.screenhang.v13',False),aint('versionCode',7),astr('versionName','1.3.3')],2)]
chunks += [start('uses-sdk',[aint('minSdkVersion',29),aint('targetSdkVersion',37)],3),end('uses-sdk',3)]
chunks += [start('uses-permission',[astr('name','android.permission.SYSTEM_ALERT_WINDOW')],4),end('uses-permission',4)]
chunks += [start('queries',[],5),start('package',[astr('name','moe.shizuku.privileged.api')],6),end('package',6),end('queries',7)]
chunks += [start('application',[astr('label','Lucky 息屏挂机 v1.3.3'),abool('hasCode',True)],8)]
chunks += [start('activity',[astr('name','com.lucky.screenhang.v13.MainActivity'),abool('exported',True)],9)]
chunks += [start('intent-filter',[],10),start('action',[astr('name','android.intent.action.MAIN')],11),end('action',11),start('category',[astr('name','android.intent.category.LAUNCHER')],12),end('category',12),end('intent-filter',13)]
chunks += [end('activity',14),end('application',15),end('manifest',16),ns_end(17)]
body=bytes(string_pool())+resource_map()+b''.join(chunks)
out=struct.pack('<HHI',3,8,8+len(body))+body
Path(sys.argv[1]).write_bytes(out)
print(f'wrote {sys.argv[1]}: {len(out)} bytes, strings={len(strings)}')
