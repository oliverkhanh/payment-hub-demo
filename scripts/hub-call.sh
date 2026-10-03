#!/usr/bin/env bash
# Gọi API Payment Hub với HMAC đã ký sẵn — dùng để thử tay Increment 3.
#
# Chuỗi ký (docs/notes/hmac-request-signing.md §2), nối bằng '\n', KHÔNG có '\n' cuối:
#   METHOD \n PATH \n TIMESTAMP \n IDEMPOTENCY_KEY \n SHA256_HEX(BODY)
#
# Dùng:
#   ./scripts/hub-call.sh POST /v1/payments '{"order_code":"ord_1",...}'   # tự sinh key mới
#   IDEM_KEY=abc-123 ./scripts/hub-call.sh POST /v1/payments '{...}'       # dùng lại key -> test replay
#   ./scripts/hub-call.sh GET  /v1/payments/pay_xxx                        # không body
set -euo pipefail

HUB="${HUB:-http://localhost:8080}"
API_KEY="${API_KEY:-mk_dev_demo}"
SECRET="${SECRET:-sk_dev_demo_secret_do_not_use_in_prod}"   # DEV-ONLY, khớp seed V2__payments.sql

METHOD="${1:?thiếu METHOD}"
PATH_ONLY="${2:?thiếu PATH}"
BODY="${3-}"

TS="$(date +%s)"
IDEM_KEY="${IDEM_KEY-}"
# POST/PUT/PATCH bắt buộc có Idempotency-Key; GET thì để rỗng (dòng trong chuỗi ký VẪN CÒN).
if [[ -z "$IDEM_KEY" && "$METHOD" != "GET" ]]; then
  IDEM_KEY="$(uuidgen)"
fi

# SHA-256 hex chữ thường của body. Body rỗng -> e3b0c442...b855 (hằng số, hai bên tính ra giống nhau).
BODY_SHA="$(printf '%s' "$BODY" | openssl dgst -sha256 -hex | awk '{print $NF}')"

# printf thay vì echo: không được có newline cuối chuỗi ký.
CANONICAL="$(printf '%s\n%s\n%s\n%s\n%s' "$METHOD" "$PATH_ONLY" "$TS" "$IDEM_KEY" "$BODY_SHA")"
SIG="$(printf '%s' "$CANONICAL" | openssl dgst -sha256 -hmac "$SECRET" -hex | awk '{print $NF}')"

{
  echo "── canonical string ──"
  printf '%s\n' "$CANONICAL" | sed 's/^/  │ /'
  echo "── signature: ${SIG:0:16}…  idempotency-key: ${IDEM_KEY:-<rỗng>}"
} >&2

ARGS=(-sS -i -X "$METHOD" "$HUB$PATH_ONLY"
      -H "X-Merchant-Key: $API_KEY"
      -H "X-Timestamp: $TS"
      -H "X-Signature: $SIG"
      -H "X-Signature-Version: v1")
[[ -n "$IDEM_KEY" ]] && ARGS+=(-H "Idempotency-Key: $IDEM_KEY")
[[ -n "$BODY" ]]     && ARGS+=(-H "Content-Type: application/json" --data-raw "$BODY")

curl "${ARGS[@]}"
echo
