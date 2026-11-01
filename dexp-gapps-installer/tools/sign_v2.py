"""Repackage and sign a Gradle APK using Android APK Signature Scheme v2.

This CI script signs a minimal APK without proprietary Google assets. The
user's complete APK is built separately, using the same v2 signing format.
"""
import sys, zipfile, hashlib, struct, shutil, io, datetime
from pathlib import Path
from cryptography import x509
from cryptography.hazmat.primitives import serialization, hashes
from cryptography.hazmat.primitives.asymmetric import rsa, padding
from cryptography.x509.oid import NameOID

source, target = map(Path, sys.argv[1:3])
tmp = target.with_suffix('.unsigned.apk')
u32=lambda n: struct.pack('<I',n)
lp=lambda b: u32(len(b))+b

with zipfile.ZipFile(source) as z, zipfile.ZipFile(tmp,'w') as out:
    for i in z.infolist():
        if i.filename.startswith('META-INF/') and i.filename.upper().endswith(('.SF','.RSA','.DSA','.EC','.MF')):
            continue
        info=zipfile.ZipInfo(i.filename,(2026,1,1,0,0,0))
        info.compress_type=zipfile.ZIP_STORED if i.filename=='resources.arsc' else zipfile.ZIP_DEFLATED
        if info.compress_type==zipfile.ZIP_STORED:
            offset=out.fp.tell()
            pad=(-(offset+30+len(info.filename.encode())+4))%4
            info.extra=struct.pack('<HH',0xD935,pad)+b'\0'*pad
        out.writestr(info,z.read(i.filename))
with open(tmp,'rb') as f:
    f.seek(-22,2)
    end=f.read(22)
    assert end[:4]==b'PK\x05\x06'
    size,start=struct.unpack_from('<II',end,12)
    assert start+size==tmp.stat().st_size-22

def digest(f):
    hashes_out=[]
    for off,n,data in [(0,start,None),(start,size,None),(0,0,end)]:
        if data is not None:
            chunks=[data]
        else:
            f.seek(off); chunks=[]; rem=n
            while rem:
                b=f.read(min(rem,1048576));assert b
                chunks.append(b);rem-=len(b)
        for chunk in chunks:
            hashes_out.append(hashlib.sha256(b'\xa5'+u32(len(chunk))+chunk).digest())
    return hashlib.sha256(b'\x5a'+u32(len(hashes_out))+b''.join(hashes_out)).digest()

with open(tmp,'rb') as f:
    file_digest=digest(f)

key=rsa.generate_private_key(public_exponent=65537,key_size=3072)
cert=x509.CertificateBuilder().subject_name(
    x509.Name([x509.NameAttribute(NameOID.COMMON_NAME,'DEXP CI APK signing')])
).issuer_name(
    x509.Name([x509.NameAttribute(NameOID.COMMON_NAME,'DEXP CI APK signing')])
).public_key(key.public_key()).serial_number(x509.random_serial_number()).not_valid_before(
    datetime.datetime.now(datetime.timezone.utc)-datetime.timedelta(days=1)
).not_valid_after(
    datetime.datetime.now(datetime.timezone.utc)+datetime.timedelta(days=20)
).sign(key,hashes.SHA256())
alg=u32(0x0103)
signed=lp(lp(alg+lp(file_digest)))+lp(lp(cert.public_bytes(serialization.Encoding.DER)))+lp(b'')
sig=key.sign(signed,padding.PKCS1v15(),hashes.SHA256())
signer=lp(signed)+lp(lp(alg+lp(sig)))+lp(key.public_key().public_bytes(serialization.Encoding.DER,serialization.PublicFormat.SubjectPublicKeyInfo))
value=lp(lp(signer))
pair=struct.pack('<Q',len(value)+4)+u32(0x7109871a)+value
blocksize=len(pair)+24
block=struct.pack('<Q',blocksize)+pair+struct.pack('<Q',blocksize)+b'APK Sig Block 42'
assert len(block)==blocksize+8
end=bytearray(end)
struct.pack_into('<I',end,16,start+len(block))
with open(tmp,'rb') as f, open(target,'wb') as dst:
    dst.write(f.read(start))
    dst.write(block)
    dst.write(f.read(size))
    dst.write(end)
tmp.unlink()
print('Signed with APK Signature Scheme v2:',target)
