# Release signing

The repository stores only the public release certificate.

GitHub Actions expects one repository secret:

- `LUCKY_RELEASE_KEY_PEM_B64`: base64 of the PEM RSA private key matching `release-cert.pem`.

Never commit the private key. The release workflow validates that the secret key matches the committed certificate before signing.

Release certificate SHA-256 fingerprint:

`63:69:70:E8:27:D7:26:9E:EA:4D:10:FB:D2:51:CD:1E:CC:F9:C5:4F:7B:4F:AA:90:6D:7D:78:81:A8:5A:03:0E`
