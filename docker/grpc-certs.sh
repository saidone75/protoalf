#!/bin/sh
# Generates a development CA and the TLS certificate of the Alfresco gRPC listener.
# The gRPC module refuses plaintext on non-loopback addresses, and inside Docker it must listen on 0.0.0.0.
#   ca.crt      -> trusted by the MCP server
#   server.crt  -> gRPC listener certificate (SAN: ACS container name, localhost)
#   server.key  -> PKCS#8 private key, as required by Netty
# Runs only once: the files are kept in the 'grpc-certs' volume (`run.sh purge` deletes them).
set -e
DIR=/certs
SAN="DNS:acs,DNS:localhost,IP:127.0.0.1"

if [ -f "$DIR/server.crt" ] && [ -f "$DIR/server.key" ] && [ -f "$DIR/ca.crt" ]; then
  echo "gRPC certificates already present"
  exit 0
fi

apk add --no-cache openssl >/dev/null
cd "$DIR"

openssl req -x509 -newkey rsa:2048 -nodes -days 3650 -subj "/CN=protoalf-dev-ca" \
  -keyout ca.key -out ca.crt
openssl genpkey -algorithm RSA -pkeyopt rsa_keygen_bits:2048 -out server.key
openssl req -new -key server.key -subj "/CN=acs" -out server.csr
printf "subjectAltName=%s\nextendedKeyUsage=serverAuth\n" "$SAN" > server.ext
openssl x509 -req -in server.csr -CA ca.crt -CAkey ca.key -CAcreateserial -days 3650 \
  -extfile server.ext -out server.crt
rm -f server.csr server.ext ca.srl ca.key

# Development only: readable by the alfresco (ACS) and mcp container users
chmod 644 ca.crt server.crt server.key
echo "gRPC certificates generated"
