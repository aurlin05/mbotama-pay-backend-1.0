-- Replace SN<->CG routes to use FEEXPAY instead of CINETPAY and ensure FEEXPAY stock for CG

DELETE FROM gateway_routes 
WHERE source_country='SENEGAL' AND dest_country='CONGO_BRAZZAVILLE' AND gateway='CINETPAY';

DELETE FROM gateway_routes 
WHERE source_country='CONGO_BRAZZAVILLE' AND dest_country='SENEGAL' AND gateway='CINETPAY';

INSERT INTO gateway_routes (source_country, dest_country, gateway, priority, gateway_fee_percent, enabled)
SELECT 'SENEGAL', 'CONGO_BRAZZAVILLE', 'FEEXPAY', 1, 3.50, true
WHERE NOT EXISTS (
    SELECT 1 FROM gateway_routes 
    WHERE source_country='SENEGAL' AND dest_country='CONGO_BRAZZAVILLE' AND gateway='FEEXPAY'
);

INSERT INTO gateway_routes (source_country, dest_country, gateway, priority, gateway_fee_percent, enabled)
SELECT 'CONGO_BRAZZAVILLE', 'SENEGAL', 'FEEXPAY', 1, 3.50, true
WHERE NOT EXISTS (
    SELECT 1 FROM gateway_routes 
    WHERE source_country='CONGO_BRAZZAVILLE' AND dest_country='SENEGAL' AND gateway='FEEXPAY'
);

INSERT INTO gateway_stocks (gateway, country, balance, min_threshold)
SELECT 'FEEXPAY', 'CONGO_BRAZZAVILLE', 1000000, 100000
WHERE NOT EXISTS (
    SELECT 1 FROM gateway_stocks 
    WHERE gateway='FEEXPAY' AND country='CONGO_BRAZZAVILLE'
);
