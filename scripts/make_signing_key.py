"""Create the permanent signing key for the Gesits MID Android app.

Run once, on YOUR computer (never share the output):
    pip install cryptography
    python scripts/make_signing_key.py

It writes two files in the current folder:
    gesits-mid-release.p12        the key itself  -> keep a backup (e.g. USB stick / password manager)
    gesits-mid-github-secrets.txt the two values to paste into GitHub, then DELETE this file

Every APK signed with this key installs over the previous one. If the key is lost, the next
version can only be installed after uninstalling the app (losing its data), so keep the backup.
"""
import base64
import datetime
import secrets
import sys
from pathlib import Path

try:
    from cryptography import x509
    from cryptography.hazmat.primitives import hashes, serialization
    from cryptography.hazmat.primitives.asymmetric import rsa
    from cryptography.hazmat.primitives.serialization import pkcs12
    from cryptography.x509.oid import NameOID
except ImportError:
    sys.exit("Please run first:  pip install cryptography")

KEY_FILE = Path("gesits-mid-release.p12")
SECRETS_FILE = Path("gesits-mid-github-secrets.txt")
ALIAS = b"gesits-mid"

if KEY_FILE.exists():
    sys.exit(f"{KEY_FILE} already exists - not overwriting your key.")

password = secrets.token_urlsafe(24)
key = rsa.generate_private_key(public_exponent=65537, key_size=3072)
name = x509.Name([
    x509.NameAttribute(NameOID.COMMON_NAME, "Gesits MID"),
    x509.NameAttribute(NameOID.ORGANIZATION_NAME, "CAKRA Labs"),
    x509.NameAttribute(NameOID.COUNTRY_NAME, "ID"),
])
now = datetime.datetime.now(datetime.timezone.utc)
cert = (x509.CertificateBuilder()
        .subject_name(name).issuer_name(name)
        .public_key(key.public_key())
        .serial_number(x509.random_serial_number())
        .not_valid_before(now - datetime.timedelta(days=1))
        .not_valid_after(now + datetime.timedelta(days=365 * 30))
        .sign(key, hashes.SHA256()))
# Java/Android tools read this PKCS#12 file with the same password for store and key.
p12 = pkcs12.serialize_key_and_certificates(
    ALIAS, key, cert, None, serialization.BestAvailableEncryption(password.encode()))
KEY_FILE.write_bytes(p12)

SECRETS_FILE.write_text(
    "Paste these into GitHub: repo GESITS_MID_pub -> Settings -> Secrets and variables -> Actions\n"
    "-> New repository secret. Then DELETE this file.\n\n"
    "Name:  GESITS_KEYSTORE_PASSWORD\nValue:\n" + password + "\n\n"
    "Name:  GESITS_KEYSTORE_B64\nValue (one long line):\n" + base64.b64encode(p12).decode() + "\n",
    encoding="utf-8")

print(f"Created {KEY_FILE.resolve()}  <- keep a backup of this file")
print(f"Created {SECRETS_FILE.resolve()}  <- paste the 2 values into GitHub, then delete it")
