-- Seed minimal cross-border routes for SN <-> CG and add CG stocks

INSERT INTO gateway_routes (source_country, dest_country, gateway, priority, gateway_fee_percent, enabled)
VALUES ('SN', 'CG', 'CINETPAY', 1, 3.50, true)
ON CONFLICT DO NOTHING;

INSERT INTO gateway_routes (source_country, dest_country, gateway, priority, gateway_fee_percent, enabled)
VALUES ('CG', 'SN', 'CINETPAY', 1, 3.50, true)
ON CONFLICT DO NOTHING;

INSERT INTO gateway_stocks (gateway, country, balance, min_threshold)
VALUES ('CINETPAY', 'CG', 1000000, 100000)
ON CONFLICT DO NOTHING;
