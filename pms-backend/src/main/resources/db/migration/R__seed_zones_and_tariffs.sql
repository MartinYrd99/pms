INSERT INTO zones (name, city, active)
SELECT v.name, v.city, v.active
FROM (VALUES
    ('Blue Zone', 'Sofia', true),
    ('Green Zone', 'Sofia', true),
    ('Grey Zone', 'Plovdiv', false)
) AS v (name, city, active)
WHERE NOT EXISTS (
    SELECT 1 FROM zones z WHERE z.name = v.name AND z.city = v.city
);

INSERT INTO tariffs (zone_id, rule_type, hourly_rate, currency, valid_from)
SELECT z.id, v.rule_type, v.hourly_rate, v.currency, v.valid_from
FROM (VALUES
    ('Blue Zone', 'Sofia', 'HOURLY', 2.00, 'EUR', TIMESTAMPTZ '2024-01-01 00:00:00+00'),
    ('Green Zone', 'Sofia', 'HOURLY', 1.00, 'EUR', TIMESTAMPTZ '2024-01-01 00:00:00+00'),
    ('Grey Zone', 'Plovdiv', 'HOURLY', 2.50, 'EUR', TIMESTAMPTZ '2024-01-01 00:00:00+00')
) AS v (name, city, rule_type, hourly_rate, currency, valid_from)
JOIN zones z ON z.name = v.name AND z.city = v.city
WHERE NOT EXISTS (
    SELECT 1 FROM tariffs t WHERE t.zone_id = z.id AND t.valid_to IS NULL
);
