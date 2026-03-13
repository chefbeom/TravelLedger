-- Calen ledger dummy data for login_id = hana1234
-- This script seeds ledger-only data.
-- If the account does not exist, no rows are inserted.
-- Re-running the script removes previous DUMMYLEDGER rows for the same user and recreates them.

START TRANSACTION;

SET @target_login_id := 'hana1234';
SET @row_count := 5000;
SET @owner_id := (
    SELECT id
    FROM app_users
    WHERE login_id = @target_login_id
    ORDER BY id
    LIMIT 1
);

SELECT IF(
    @owner_id IS NULL,
    CONCAT('MISSING_USER:', @target_login_id),
    CONCAT('TARGET_USER_ID:', @owner_id, ' / ROW_COUNT:', @row_count)
) AS seed_status;

INSERT INTO category_groups (owner_id, name, entry_type, display_order, active)
SELECT @owner_id, seed.name, seed.entry_type, seed.display_order, TRUE
FROM (
    SELECT '식비' AS name, 'EXPENSE' AS entry_type, 1 AS display_order
    UNION ALL SELECT '교통', 'EXPENSE', 2
    UNION ALL SELECT '생활', 'EXPENSE', 3
    UNION ALL SELECT '급여', 'INCOME', 1
    UNION ALL SELECT '부수입', 'INCOME', 2
) seed
WHERE @owner_id IS NOT NULL
  AND NOT EXISTS (
      SELECT 1
      FROM category_groups cg
      WHERE cg.owner_id = @owner_id
        AND cg.name = seed.name
        AND cg.entry_type = seed.entry_type
  );

INSERT INTO category_details (group_id, name, display_order, active)
SELECT cg.id, seed.detail_name, seed.display_order, TRUE
FROM (
    SELECT '식비' AS group_name, 'EXPENSE' AS entry_type, '외식' AS detail_name, 1 AS display_order
    UNION ALL SELECT '식비', 'EXPENSE', '군것질', 2
    UNION ALL SELECT '식비', 'EXPENSE', '장보기', 3
    UNION ALL SELECT '교통', 'EXPENSE', '대중교통', 1
    UNION ALL SELECT '교통', 'EXPENSE', '택시', 2
    UNION ALL SELECT '생활', 'EXPENSE', '쇼핑', 1
    UNION ALL SELECT '생활', 'EXPENSE', '의료', 2
    UNION ALL SELECT '급여', 'INCOME', '본급', 1
    UNION ALL SELECT '급여', 'INCOME', '보너스', 2
    UNION ALL SELECT '부수입', 'INCOME', '프리랜서', 1
    UNION ALL SELECT '부수입', 'INCOME', '환급', 2
) seed
JOIN category_groups cg
  ON cg.owner_id = @owner_id
 AND cg.name = seed.group_name
 AND cg.entry_type = seed.entry_type
WHERE @owner_id IS NOT NULL
  AND NOT EXISTS (
      SELECT 1
      FROM category_details cd
      WHERE cd.group_id = cg.id
        AND cd.name = seed.detail_name
  );

INSERT INTO payment_methods (owner_id, name, kind, display_order, active)
SELECT @owner_id, seed.name, seed.kind, seed.display_order, TRUE
FROM (
    SELECT '신한카드' AS name, 'CARD' AS kind, 1 AS display_order
    UNION ALL SELECT '우리카드', 'CARD', 2
    UNION ALL SELECT '현금', 'CASH', 3
    UNION ALL SELECT '포인트', 'POINT', 4
    UNION ALL SELECT '계좌이체', 'TRANSFER', 5
) seed
WHERE @owner_id IS NOT NULL
  AND NOT EXISTS (
      SELECT 1
      FROM payment_methods pm
      WHERE pm.owner_id = @owner_id
        AND pm.name = seed.name
  );

SET @food_group_id := (
    SELECT id FROM category_groups WHERE owner_id = @owner_id AND name = '식비' AND entry_type = 'EXPENSE' ORDER BY id LIMIT 1
);
SET @transport_group_id := (
    SELECT id FROM category_groups WHERE owner_id = @owner_id AND name = '교통' AND entry_type = 'EXPENSE' ORDER BY id LIMIT 1
);
SET @living_group_id := (
    SELECT id FROM category_groups WHERE owner_id = @owner_id AND name = '생활' AND entry_type = 'EXPENSE' ORDER BY id LIMIT 1
);
SET @salary_group_id := (
    SELECT id FROM category_groups WHERE owner_id = @owner_id AND name = '급여' AND entry_type = 'INCOME' ORDER BY id LIMIT 1
);
SET @side_group_id := (
    SELECT id FROM category_groups WHERE owner_id = @owner_id AND name = '부수입' AND entry_type = 'INCOME' ORDER BY id LIMIT 1
);

SET @eat_out_detail_id := (
    SELECT cd.id FROM category_details cd JOIN category_groups cg ON cg.id = cd.group_id
    WHERE cg.owner_id = @owner_id AND cg.name = '식비' AND cg.entry_type = 'EXPENSE' AND cd.name = '외식'
    ORDER BY cd.id LIMIT 1
);
SET @snack_detail_id := (
    SELECT cd.id FROM category_details cd JOIN category_groups cg ON cg.id = cd.group_id
    WHERE cg.owner_id = @owner_id AND cg.name = '식비' AND cg.entry_type = 'EXPENSE' AND cd.name = '군것질'
    ORDER BY cd.id LIMIT 1
);
SET @grocery_detail_id := (
    SELECT cd.id FROM category_details cd JOIN category_groups cg ON cg.id = cd.group_id
    WHERE cg.owner_id = @owner_id AND cg.name = '식비' AND cg.entry_type = 'EXPENSE' AND cd.name = '장보기'
    ORDER BY cd.id LIMIT 1
);
SET @public_transport_detail_id := (
    SELECT cd.id FROM category_details cd JOIN category_groups cg ON cg.id = cd.group_id
    WHERE cg.owner_id = @owner_id AND cg.name = '교통' AND cg.entry_type = 'EXPENSE' AND cd.name = '대중교통'
    ORDER BY cd.id LIMIT 1
);
SET @taxi_detail_id := (
    SELECT cd.id FROM category_details cd JOIN category_groups cg ON cg.id = cd.group_id
    WHERE cg.owner_id = @owner_id AND cg.name = '교통' AND cg.entry_type = 'EXPENSE' AND cd.name = '택시'
    ORDER BY cd.id LIMIT 1
);
SET @shopping_detail_id := (
    SELECT cd.id FROM category_details cd JOIN category_groups cg ON cg.id = cd.group_id
    WHERE cg.owner_id = @owner_id AND cg.name = '생활' AND cg.entry_type = 'EXPENSE' AND cd.name = '쇼핑'
    ORDER BY cd.id LIMIT 1
);
SET @medical_detail_id := (
    SELECT cd.id FROM category_details cd JOIN category_groups cg ON cg.id = cd.group_id
    WHERE cg.owner_id = @owner_id AND cg.name = '생활' AND cg.entry_type = 'EXPENSE' AND cd.name = '의료'
    ORDER BY cd.id LIMIT 1
);
SET @salary_base_detail_id := (
    SELECT cd.id FROM category_details cd JOIN category_groups cg ON cg.id = cd.group_id
    WHERE cg.owner_id = @owner_id AND cg.name = '급여' AND cg.entry_type = 'INCOME' AND cd.name = '본급'
    ORDER BY cd.id LIMIT 1
);
SET @salary_bonus_detail_id := (
    SELECT cd.id FROM category_details cd JOIN category_groups cg ON cg.id = cd.group_id
    WHERE cg.owner_id = @owner_id AND cg.name = '급여' AND cg.entry_type = 'INCOME' AND cd.name = '보너스'
    ORDER BY cd.id LIMIT 1
);
SET @freelance_detail_id := (
    SELECT cd.id FROM category_details cd JOIN category_groups cg ON cg.id = cd.group_id
    WHERE cg.owner_id = @owner_id AND cg.name = '부수입' AND cg.entry_type = 'INCOME' AND cd.name = '프리랜서'
    ORDER BY cd.id LIMIT 1
);
SET @refund_detail_id := (
    SELECT cd.id FROM category_details cd JOIN category_groups cg ON cg.id = cd.group_id
    WHERE cg.owner_id = @owner_id AND cg.name = '부수입' AND cg.entry_type = 'INCOME' AND cd.name = '환급'
    ORDER BY cd.id LIMIT 1
);

SET @shinhan_payment_id := (
    SELECT id FROM payment_methods WHERE owner_id = @owner_id AND name = '신한카드' ORDER BY id LIMIT 1
);
SET @woori_payment_id := (
    SELECT id FROM payment_methods WHERE owner_id = @owner_id AND name = '우리카드' ORDER BY id LIMIT 1
);
SET @cash_payment_id := (
    SELECT id FROM payment_methods WHERE owner_id = @owner_id AND name = '현금' ORDER BY id LIMIT 1
);
SET @point_payment_id := (
    SELECT id FROM payment_methods WHERE owner_id = @owner_id AND name = '포인트' ORDER BY id LIMIT 1
);
SET @transfer_payment_id := (
    SELECT id FROM payment_methods WHERE owner_id = @owner_id AND name = '계좌이체' ORDER BY id LIMIT 1
);

DELETE FROM ledger_entries
WHERE owner_id = @owner_id
  AND title LIKE 'DUMMYLEDGER-%';

INSERT INTO ledger_entries (
    owner_id,
    entry_date,
    entry_time,
    title,
    memo,
    amount,
    entry_type,
    category_group_id,
    category_detail_id,
    payment_method_id
)
SELECT
    @owner_id AS owner_id,
    DATE_ADD('2021-01-01', INTERVAL MOD(numbers.seq_num * 17, 1826) DAY) AS entry_date,
    MAKETIME(MOD(numbers.seq_num * 7, 24), MOD(numbers.seq_num * 11, 60), 0) AS entry_time,
    CONCAT(
        'DUMMYLEDGER-',
        LPAD(numbers.seq_num, 5, '0'),
        ' ',
        CASE
            WHEN MOD(numbers.seq_num, 9) = 0 AND MOD(numbers.seq_num, 18) = 0 THEN '보너스 입금'
            WHEN MOD(numbers.seq_num, 9) = 0 THEN '부수입 정산'
            WHEN MOD(numbers.seq_num, 6) IN (0, 1) THEN '식사/간식'
            WHEN MOD(numbers.seq_num, 6) IN (2, 3) THEN '교통 이동'
            ELSE '생활 지출'
        END
    ) AS title,
    CONCAT(
        'hana1234 더미 가계부 데이터 / batch=', 5000,
        ' / seq=', numbers.seq_num,
        ' / weekday-pattern=', MOD(numbers.seq_num * 3, 7)
    ) AS memo,
    CASE
        WHEN MOD(numbers.seq_num, 9) = 0 THEN ROUND(1500000 + MOD(numbers.seq_num * 9137, 3800000), 2)
        ELSE ROUND(1200 + MOD(numbers.seq_num * 173, 245000), 2)
    END AS amount,
    CASE
        WHEN MOD(numbers.seq_num, 9) = 0 THEN 'INCOME'
        ELSE 'EXPENSE'
    END AS entry_type,
    CASE
        WHEN MOD(numbers.seq_num, 9) = 0 AND MOD(numbers.seq_num, 18) = 0 THEN @salary_group_id
        WHEN MOD(numbers.seq_num, 9) = 0 THEN @side_group_id
        WHEN MOD(numbers.seq_num, 6) IN (0, 1) THEN @food_group_id
        WHEN MOD(numbers.seq_num, 6) IN (2, 3) THEN @transport_group_id
        ELSE @living_group_id
    END AS category_group_id,
    CASE
        WHEN MOD(numbers.seq_num, 9) = 0 AND MOD(numbers.seq_num, 18) = 0 AND MOD(numbers.seq_num, 36) = 0 THEN @salary_bonus_detail_id
        WHEN MOD(numbers.seq_num, 9) = 0 AND MOD(numbers.seq_num, 18) = 0 THEN @salary_base_detail_id
        WHEN MOD(numbers.seq_num, 9) = 0 AND MOD(numbers.seq_num, 27) = 0 THEN @refund_detail_id
        WHEN MOD(numbers.seq_num, 9) = 0 THEN @freelance_detail_id
        WHEN MOD(numbers.seq_num, 6) = 0 THEN @eat_out_detail_id
        WHEN MOD(numbers.seq_num, 6) = 1 THEN @grocery_detail_id
        WHEN MOD(numbers.seq_num, 6) = 2 THEN @public_transport_detail_id
        WHEN MOD(numbers.seq_num, 6) = 3 THEN @taxi_detail_id
        WHEN MOD(numbers.seq_num, 6) = 4 THEN @shopping_detail_id
        ELSE @medical_detail_id
    END AS category_detail_id,
    CASE
        WHEN MOD(numbers.seq_num, 9) = 0 THEN @transfer_payment_id
        WHEN MOD(numbers.seq_num, 5) = 0 THEN @cash_payment_id
        WHEN MOD(numbers.seq_num, 5) = 1 THEN @shinhan_payment_id
        WHEN MOD(numbers.seq_num, 5) = 2 THEN @woori_payment_id
        WHEN MOD(numbers.seq_num, 5) = 3 THEN @point_payment_id
        ELSE @transfer_payment_id
    END AS payment_method_id
FROM (
    SELECT ones.n + tens.n * 10 + hundreds.n * 100 + thousands.n * 1000 + ten_thousands.n * 10000 + 1 AS seq_num
    FROM (SELECT 0 AS n UNION ALL SELECT 1 UNION ALL SELECT 2 UNION ALL SELECT 3 UNION ALL SELECT 4 UNION ALL SELECT 5 UNION ALL SELECT 6 UNION ALL SELECT 7 UNION ALL SELECT 8 UNION ALL SELECT 9) ones
    CROSS JOIN (SELECT 0 AS n UNION ALL SELECT 1 UNION ALL SELECT 2 UNION ALL SELECT 3 UNION ALL SELECT 4 UNION ALL SELECT 5 UNION ALL SELECT 6 UNION ALL SELECT 7 UNION ALL SELECT 8 UNION ALL SELECT 9) tens
    CROSS JOIN (SELECT 0 AS n UNION ALL SELECT 1 UNION ALL SELECT 2 UNION ALL SELECT 3 UNION ALL SELECT 4 UNION ALL SELECT 5 UNION ALL SELECT 6 UNION ALL SELECT 7 UNION ALL SELECT 8 UNION ALL SELECT 9) hundreds
    CROSS JOIN (SELECT 0 AS n UNION ALL SELECT 1 UNION ALL SELECT 2 UNION ALL SELECT 3 UNION ALL SELECT 4 UNION ALL SELECT 5 UNION ALL SELECT 6 UNION ALL SELECT 7 UNION ALL SELECT 8 UNION ALL SELECT 9) thousands
    CROSS JOIN (SELECT 0 AS n UNION ALL SELECT 1 UNION ALL SELECT 2 UNION ALL SELECT 3 UNION ALL SELECT 4 UNION ALL SELECT 5 UNION ALL SELECT 6 UNION ALL SELECT 7 UNION ALL SELECT 8 UNION ALL SELECT 9) ten_thousands
) numbers
WHERE @owner_id IS NOT NULL
  AND numbers.seq_num <= @row_count;

SELECT COUNT(*) AS inserted_dummy_rows
FROM ledger_entries
WHERE owner_id = @owner_id
  AND title LIKE 'DUMMYLEDGER-%';

COMMIT;
