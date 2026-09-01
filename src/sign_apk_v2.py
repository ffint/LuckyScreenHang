#!/usr/bin/env python3
import hashlib, struct, sys
from pathlib import Path
from datetime import datetime, timedelta, timezone
from cryptography import x509
from cryptography.x509.oid import NameOID
from cryptography.hazmat.primitives import hashes, serialization
from cryptography.hazmat.primitives.asymmetric import padding, rsa

V2_ID = 0x7109871A
SIG_ALG = 0x0103  # RSASSA-PKCS1-v1_5 with SHA2-256
MAGIC = b'APK Sig Block 42'
EOCD_SIG = b'PK\x05\x06'
CHUNK = 1024 * 1024


def u32(n): return struct.pack('<I', n)
def u64(n): return struct.pack('<Q', n)
def lp32(b): return u32(len(b)) + b

def make_key_cert(key_path: Path, cert_path: Path):
    if key_path.exists() and cert_path.exists():
        key = serialization.load_pem_private_key(key_path.read_bytes(), password=None)
        cert = x509.load_pem_x509_certificate(cert_path.read_bytes())
        return key, cert
    key = rsa.generate_private_key(public_exponent=65537, key_size=2048)
    subject = issuer = x509.Name([
        x509.NameAttribute(NameOID.COMMON_NAME, 'Lucky Screen Hang Personal Build'),
        x509.NameAttribute(NameOID.ORGANIZATION_NAME, 'Lucky'),
    ])
    now = datetime.now(timezone.utc)
    cert = (x509.CertificateBuilder()
        .subject_name(subject).issuer_name(issuer)
        .public_key(key.public_key())
        .serial_number(x509.random_serial_number())
        .not_valid_before(now - timedelta(days=1))
        .not_valid_after(now + timedelta(days=7300))
        .add_extension(x509.BasicConstraints(ca=False, path_length=None), critical=True)
        .sign(key, hashes.SHA256()))
    key_path.write_bytes(key.private_bytes(
        serialization.Encoding.PEM,
        serialization.PrivateFormat.PKCS8,
        serialization.NoEncryption()))
    cert_path.write_bytes(cert.public_bytes(serialization.Encoding.PEM))
    return key, cert


def find_eocd(data: bytes):
    pos = data.rfind(EOCD_SIG)
    if pos < 0 or pos + 22 > len(data):
        raise ValueError('EOCD not found')
    comment_len = struct.unpack_from('<H', data, pos + 20)[0]
    if pos + 22 + comment_len != len(data):
        raise ValueError('ZIP trailing data/comment layout unsupported')
    cd_size = struct.unpack_from('<I', data, pos + 12)[0]
    cd_off = struct.unpack_from('<I', data, pos + 16)[0]
    if cd_off + cd_size != pos:
        raise ValueError('ZIP64 or non-contiguous central directory unsupported')
    return pos, cd_off, cd_size


def chunked_digest(sections):
    digests = []
    for section in sections:
        for off in range(0, len(section), CHUNK):
            chunk = section[off:off+CHUNK]
            digests.append(hashlib.sha256(b'\xA5' + u32(len(chunk)) + chunk).digest())
    return hashlib.sha256(b'\x5A' + u32(len(digests)) + b''.join(digests)).digest()


def sign(unsigned_path: Path, output_path: Path, key_path: Path, cert_path: Path):
    data = unsigned_path.read_bytes()
    eocd_pos, cd_off, cd_size = find_eocd(data)
    before = data[:cd_off]
    central = data[cd_off:eocd_pos]
    eocd_for_digest = bytearray(data[eocd_pos:])
    # During digest calculation this field points to signing-block start.
    struct.pack_into('<I', eocd_for_digest, 16, cd_off)
    content_digest = chunked_digest([before, central, bytes(eocd_for_digest)])

    key, cert = make_key_cert(key_path, cert_path)
    cert_der = cert.public_bytes(serialization.Encoding.DER)
    pub_der = key.public_key().public_bytes(
        serialization.Encoding.DER,
        serialization.PublicFormat.SubjectPublicKeyInfo)

    digest_record = u32(SIG_ALG) + lp32(content_digest)
    digests = lp32(lp32(digest_record))
    certs = lp32(lp32(cert_der))
    attrs = lp32(b'')
    signed_data = digests + certs + attrs
    signature = key.sign(signed_data, padding.PKCS1v15(), hashes.SHA256())
    sig_record = u32(SIG_ALG) + lp32(signature)
    signatures = lp32(lp32(sig_record))
    signer = lp32(signed_data) + signatures + lp32(pub_der)
    v2_value = lp32(lp32(signer))

    pair = u64(4 + len(v2_value)) + u32(V2_ID) + v2_value
    block_size = len(pair) + 24  # excludes first uint64, includes trailing size + magic
    signing_block = u64(block_size) + pair + u64(block_size) + MAGIC

    new_cd_off = cd_off + len(signing_block)
    final_eocd = bytearray(data[eocd_pos:])
    struct.pack_into('<I', final_eocd, 16, new_cd_off)
    output_path.write_bytes(before + signing_block + central + bytes(final_eocd))
    return {
        'content_digest': content_digest.hex(),
        'signing_block_size': len(signing_block),
        'central_directory_offset': new_cd_off,
        'cert_sha256': hashlib.sha256(cert_der).hexdigest(),
    }


def verify(path: Path, cert_path: Path):
    # Structural/self-verification of the v2 block generated above.
    data = path.read_bytes()
    eocd_pos, cd_off, cd_size = find_eocd(data)
    if data[cd_off-16:cd_off] != MAGIC:
        raise ValueError('APK Sig Block magic missing')
    block_size = struct.unpack_from('<Q', data, cd_off-24)[0]
    block_start = cd_off - (block_size + 8)
    if struct.unpack_from('<Q', data, block_start)[0] != block_size:
        raise ValueError('APK Sig Block size mismatch')
    p = block_start + 8
    pair_len = struct.unpack_from('<Q', data, p)[0]; p += 8
    pair_end = p + pair_len
    pair_id = struct.unpack_from('<I', data, p)[0]; p += 4
    if pair_id != V2_ID: raise ValueError('v2 ID missing')
    value = data[p:pair_end]

    def take_lp(buf, pos):
        n = struct.unpack_from('<I', buf, pos)[0]
        return buf[pos+4:pos+4+n], pos+4+n
    signers_blob, _ = take_lp(value, 0)
    signer, _ = take_lp(signers_blob, 0)
    pos = 0
    signed_data, pos = take_lp(signer, pos)
    signatures_blob, pos = take_lp(signer, pos)
    pub_der, pos = take_lp(signer, pos)
    sig_record, _ = take_lp(signatures_blob, 0)
    alg = struct.unpack_from('<I', sig_record, 0)[0]
    signature, _ = take_lp(sig_record, 4)
    if alg != SIG_ALG: raise ValueError('unexpected signature algorithm')

    # Parse signed_data digest.
    pos2 = 0
    digests_blob, pos2 = take_lp(signed_data, pos2)
    certs_blob, pos2 = take_lp(signed_data, pos2)
    attrs_blob, pos2 = take_lp(signed_data, pos2)
    digest_record, _ = take_lp(digests_blob, 0)
    dalg = struct.unpack_from('<I', digest_record, 0)[0]
    expected_digest, _ = take_lp(digest_record, 4)
    cert_der, _ = take_lp(certs_blob, 0)
    if dalg != SIG_ALG: raise ValueError('digest algorithm mismatch')

    cert = x509.load_der_x509_certificate(cert_der)
    cert.public_key().verify(signature, signed_data, padding.PKCS1v15(), hashes.SHA256())
    cert_pub = cert.public_key().public_bytes(serialization.Encoding.DER, serialization.PublicFormat.SubjectPublicKeyInfo)
    if cert_pub != pub_der: raise ValueError('public key mismatch')

    # Recreate digest sections; signing block is excluded and EOCD cd offset points to block start.
    before = data[:block_start]
    central = data[cd_off:eocd_pos]
    eocd_for_digest = bytearray(data[eocd_pos:])
    struct.pack_into('<I', eocd_for_digest, 16, block_start)
    actual_digest = chunked_digest([before, central, bytes(eocd_for_digest)])
    if actual_digest != expected_digest:
        raise ValueError('content digest mismatch')
    if cert_path.exists():
        expected_cert = x509.load_pem_x509_certificate(cert_path.read_bytes()).public_bytes(serialization.Encoding.DER)
        if expected_cert != cert_der: raise ValueError('certificate mismatch')
    return {
        'block_start': block_start,
        'central_directory_offset': cd_off,
        'content_digest': actual_digest.hex(),
        'certificate_subject': cert.subject.rfc4514_string(),
    }

if __name__ == '__main__':
    if len(sys.argv) < 5:
        print('usage: sign_apk_v2.py unsigned.apk signed.apk key.pem cert.pem')
        raise SystemExit(2)
    up, op, kp, cp = map(Path, sys.argv[1:5])
    info = sign(up, op, kp, cp)
    print('signed:', info)
    print('verified:', verify(op, cp))
