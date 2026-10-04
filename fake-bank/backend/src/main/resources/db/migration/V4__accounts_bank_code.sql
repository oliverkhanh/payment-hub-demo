ALTER TABLE accounts ADD COLUMN bank_code VARCHAR(16);

UPDATE accounts SET bank_code = 'VCB' WHERE bank_code IS NULL;

ALTER TABLE accounts ALTER COLUMN bank_code SET NOT NULL;

ALTER TABLE accounts DROP CONSTRAINT accounts_username_key;
ALTER TABLE accounts ADD CONSTRAINT uq_account_bank_username UNIQUE (bank_code, username);

INSERT INTO accounts (bank_code, username, pin, status, balance, is_slow, is_error_account)
SELECT other.code, a.username, a.pin, a.status, a.balance, a.is_slow, a.is_error_account
FROM accounts a
CROSS JOIN (VALUES ('TCB'), ('ACB'), ('SCB'), ('BIDV'), ('MB')) AS other(code)
WHERE a.bank_code = 'VCB';
