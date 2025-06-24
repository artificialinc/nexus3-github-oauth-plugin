#!/bin/bash

APP_ID="app_id_from_github"
INSTALLATION_ID="installation_id_from_url"
PRIVATE_KEY_PATH="/path/to/private-key.pem"

# 9 min expiration JWT
header='{"alg":"RS256","typ":"JWT"}'
iat=$(date +%s)
exp=$((iat + 540))
payload="{\"iat\":$iat,\"exp\":$exp,\"iss\":$APP_ID}"

base64url() { openssl base64 -e -A | tr '+/' '-_' | tr -d '='; }
header_b64=$(echo -n "$header" | base64url)
payload_b64=$(echo -n "$payload" | base64url)
unsigned_token="${header_b64}.${payload_b64}"

signature=$(echo -n "$unsigned_token" | openssl dgst -sha256 -sign "$PRIVATE_KEY_PATH" | base64url)
jwt="${unsigned_token}.${signature}"

# Exchange JWT for installation token
response=$(curl -s -X POST \
  -H "Authorization: Bearer $jwt" \
  -H "Accept: application/vnd.github+json" \
  "https://api.github.com/app/installations/$INSTALLATION_ID/access_tokens")

echo "$response" | grep token
