#!/usr/bin/env bash
# Thí nghiệm compare-and-set vs pessimistic lock — tài liệu: docs/notes/compare-and-set.md
#
# 20 luồng cùng rút 10.000đ từ một tài khoản có 100.000đ.
# Đúng ra: 10 lần thành công, số dư còn 0.
#
# Chạy: ./scripts/cas-experiment.sh      (cần docker compose đang chạy ở infra/)
set -uo pipefail

cd "$(dirname "$0")/../infra"
psql_() { docker compose exec -T postgres psql -U bank_user -d bank_db "$@"; }

reset() {
  psql_ -q -c "DROP TABLE IF EXISTS cas_demo, cas_log;
     CREATE TABLE cas_demo (code VARCHAR(8) PRIMARY KEY, balance BIGINT NOT NULL CHECK (balance>=0));
     CREATE TABLE cas_log (id serial);
     INSERT INTO cas_demo VALUES ('X', 100000);" >/dev/null 2>&1
}
bal()    { psql_ -tA -c "select balance from cas_demo where code='X'" | tr -d ' \r'; }
cnt()    { psql_ -tA -c "select count(*) from cas_log" | tr -d ' \r'; }
report() { printf "   thành công: %2s/20 (đúng 10)   •   còn lại: %7sđ (đúng 0)   •   %ss\n" \
                  "$(cnt)" "$(bal)" "$1"; }

echo "Tài khoản X: 100.000đ. 20 luồng cùng rút 10.000đ."
echo

# ───────── 1. ĐỌC rồi GHI — sai: lost update ─────────
reset; T=$SECONDS
for _ in $(seq 1 20); do
  (
    B=$(psql_ -tA -c "select balance from cas_demo where code='X'" | tr -d ' \r')
    if [ -n "$B" ] && [ "$B" -ge 10000 ]; then
      psql_ -q -c "update cas_demo set balance=$((B-10000)) where code='X';
                   insert into cas_log default values;" >/dev/null 2>&1
    fi
  ) &
done; wait
echo "① ĐỌC rồi GHI (if trong Java)"; report $((SECONDS-T))

# ───────── 2. FOR UPDATE trong transaction — đúng, nhưng khoá lâu ─────────
reset; T=$SECONDS
for _ in $(seq 1 20); do
  psql_ -q -c "DO \$\$
     DECLARE b BIGINT;
     BEGIN
       SELECT balance INTO b FROM cas_demo WHERE code='X' FOR UPDATE;
       PERFORM pg_sleep(0.05);                       -- 'nghiệp vụ' 50ms, NẰM TRONG khoá
       IF b >= 10000 THEN
         UPDATE cas_demo SET balance = b - 10000 WHERE code='X';
         INSERT INTO cas_log DEFAULT VALUES;
       END IF;
     END \$\$;" >/dev/null 2>&1 &
done; wait
echo "② SELECT … FOR UPDATE, TRONG transaction"; report $((SECONDS-T))

# ───────── 3. FOR UPDATE nhưng quên transaction — sai ÂM THẦM ─────────
reset; T=$SECONDS
for _ in $(seq 1 20); do
  (
    B=$(psql_ -tA -c "select balance from cas_demo where code='X' for update" | tr -d ' \r')
    if [ -n "$B" ] && [ "$B" -ge 10000 ]; then
      psql_ -q -c "update cas_demo set balance=$((B-10000)) where code='X';
                   insert into cas_log default values;" >/dev/null 2>&1
    fi
  ) &
done; wait
echo "③ SELECT … FOR UPDATE, QUÊN transaction  ⚠️ không lỗi nào được ném ra"
report $((SECONDS-T))

# ───────── 4. COMPARE-AND-SET — đúng, khoá chỉ trong 1 câu lệnh ─────────
reset; T=$SECONDS
for _ in $(seq 1 20); do
  psql_ -q -c "SELECT pg_sleep(0.05);                -- 'nghiệp vụ' 50ms, NGOÀI khoá
     WITH done AS (UPDATE cas_demo SET balance = balance - 10000
                    WHERE code='X' AND balance >= 10000 RETURNING 1)
     INSERT INTO cas_log SELECT FROM done;" >/dev/null 2>&1 &
done; wait
echo "④ COMPARE-AND-SET"; report $((SECONDS-T))

psql_ -q -c "DROP TABLE IF EXISTS cas_demo, cas_log;" >/dev/null 2>&1
