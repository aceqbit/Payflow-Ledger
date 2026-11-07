#!/usr/bin/env python3
"""Prints an HS256 dev JWT. Usage: mint_token.py [merchant_id] [scope]. Secret from JWT_SECRET."""
import base64
import hashlib
import hmac
import json
import os
import sys
import time

secret = os.environ.get("JWT_SECRET", "dev-only-secret-change-me-0123456789abcdef").encode()
merchant = sys.argv[1] if len(sys.argv) > 1 else "admin"
scope = sys.argv[2] if len(sys.argv) > 2 else ""
b = lambda x: base64.urlsafe_b64encode(x).rstrip(b"=")
head = b(json.dumps({"alg": "HS256", "typ": "JWT"}).encode())
body = b(json.dumps({"sub": "dev", "merchant_id": merchant, "scope": scope, "exp": int(time.time()) + 86400}).encode())
sig = b(hmac.new(secret, head + b"." + body, hashlib.sha256).digest())
print((head + b"." + body + b"." + sig).decode())
