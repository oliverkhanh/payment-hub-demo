UPDATE accounts
   SET username     = lower(bank_code) || '_' || username,
       updated_date = now()
 WHERE NOT starts_with(username, lower(bank_code) || '_');
