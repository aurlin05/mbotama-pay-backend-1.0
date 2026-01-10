-- V14__fix_bridge_routes_v2.sql
-- Correction des routes pour le bridge routing (fix de V13)

-- ========================================
-- SUPPRESSION des routes incorrectes
-- ========================================

-- CinetPay NE COUVRE PAS le Congo-Brazzaville (CG)
DELETE FROM gateway_routes WHERE source_country = 'SN' AND dest_country = 'CG' AND gateway = 'CINETPAY';
DELETE FROM gateway_routes WHERE source_country = 'CG' AND dest_country = 'SN' AND gateway = 'CINETPAY';
DELETE FROM gateway_routes WHERE source_country = 'CD' AND dest_country = 'CG' AND gateway = 'CINETPAY';
DELETE FROM gateway_routes WHERE source_country = 'CG' AND dest_country = 'CD' AND gateway = 'CINETPAY';

-- ========================================
-- ROUTES POUR LE BRIDGE ROUTING SN → CI → CG
-- ========================================

-- Route SN → CI (CinetPay)
INSERT INTO gateway_routes (source_country, dest_country, gateway, priority, gateway_fee_percent, enabled) 
SELECT 'SN', 'CI', 'CINETPAY', 1, 3.00, true
WHERE NOT EXISTS (SELECT 1 FROM gateway_routes WHERE source_country = 'SN' AND dest_country = 'CI' AND gateway = 'CINETPAY');

-- Route CI → SN (CinetPay)
INSERT INTO gateway_routes (source_country, dest_country, gateway, priority, gateway_fee_percent, enabled) 
SELECT 'CI', 'SN', 'CINETPAY', 1, 3.00, true
WHERE NOT EXISTS (SELECT 1 FROM gateway_routes WHERE source_country = 'CI' AND dest_country = 'SN' AND gateway = 'CINETPAY');

-- Route SN → CI (PayTech - alternative)
INSERT INTO gateway_routes (source_country, dest_country, gateway, priority, gateway_fee_percent, enabled) 
SELECT 'SN', 'CI', 'PAYTECH', 2, 3.50, true
WHERE NOT EXISTS (SELECT 1 FROM gateway_routes WHERE source_country = 'SN' AND dest_country = 'CI' AND gateway = 'PAYTECH');

-- Route CI → SN (PayTech - alternative)
INSERT INTO gateway_routes (source_country, dest_country, gateway, priority, gateway_fee_percent, enabled) 
SELECT 'CI', 'SN', 'PAYTECH', 2, 3.50, true
WHERE NOT EXISTS (SELECT 1 FROM gateway_routes WHERE source_country = 'CI' AND dest_country = 'SN' AND gateway = 'PAYTECH');

-- Route CI → CG (FeexPay - seul à couvrir CG)
INSERT INTO gateway_routes (source_country, dest_country, gateway, priority, gateway_fee_percent, enabled) 
SELECT 'CI', 'CG', 'FEEXPAY', 1, 3.00, true
WHERE NOT EXISTS (SELECT 1 FROM gateway_routes WHERE source_country = 'CI' AND dest_country = 'CG' AND gateway = 'FEEXPAY');

-- Route CG → CI (FeexPay)
INSERT INTO gateway_routes (source_country, dest_country, gateway, priority, gateway_fee_percent, enabled) 
SELECT 'CG', 'CI', 'FEEXPAY', 1, 3.00, true
WHERE NOT EXISTS (SELECT 1 FROM gateway_routes WHERE source_country = 'CG' AND dest_country = 'CI' AND gateway = 'FEEXPAY');

-- ========================================
-- AUTRES ROUTES BRIDGE UTILES
-- ========================================

-- BJ → CI
INSERT INTO gateway_routes (source_country, dest_country, gateway, priority, gateway_fee_percent, enabled) 
SELECT 'BJ', 'CI', 'FEEXPAY', 1, 2.50, true
WHERE NOT EXISTS (SELECT 1 FROM gateway_routes WHERE source_country = 'BJ' AND dest_country = 'CI' AND gateway = 'FEEXPAY');

INSERT INTO gateway_routes (source_country, dest_country, gateway, priority, gateway_fee_percent, enabled) 
SELECT 'CI', 'BJ', 'FEEXPAY', 1, 2.50, true
WHERE NOT EXISTS (SELECT 1 FROM gateway_routes WHERE source_country = 'CI' AND dest_country = 'BJ' AND gateway = 'FEEXPAY');

-- TG → CI
INSERT INTO gateway_routes (source_country, dest_country, gateway, priority, gateway_fee_percent, enabled) 
SELECT 'TG', 'CI', 'FEEXPAY', 1, 2.50, true
WHERE NOT EXISTS (SELECT 1 FROM gateway_routes WHERE source_country = 'TG' AND dest_country = 'CI' AND gateway = 'FEEXPAY');

INSERT INTO gateway_routes (source_country, dest_country, gateway, priority, gateway_fee_percent, enabled) 
SELECT 'CI', 'TG', 'FEEXPAY', 1, 2.50, true
WHERE NOT EXISTS (SELECT 1 FROM gateway_routes WHERE source_country = 'CI' AND dest_country = 'TG' AND gateway = 'FEEXPAY');

-- BJ → CG (FeexPay couvre les deux)
INSERT INTO gateway_routes (source_country, dest_country, gateway, priority, gateway_fee_percent, enabled) 
SELECT 'BJ', 'CG', 'FEEXPAY', 1, 3.00, true
WHERE NOT EXISTS (SELECT 1 FROM gateway_routes WHERE source_country = 'BJ' AND dest_country = 'CG' AND gateway = 'FEEXPAY');

INSERT INTO gateway_routes (source_country, dest_country, gateway, priority, gateway_fee_percent, enabled) 
SELECT 'CG', 'BJ', 'FEEXPAY', 1, 3.00, true
WHERE NOT EXISTS (SELECT 1 FROM gateway_routes WHERE source_country = 'CG' AND dest_country = 'BJ' AND gateway = 'FEEXPAY');

-- TG → CG (FeexPay couvre les deux)
INSERT INTO gateway_routes (source_country, dest_country, gateway, priority, gateway_fee_percent, enabled) 
SELECT 'TG', 'CG', 'FEEXPAY', 1, 3.00, true
WHERE NOT EXISTS (SELECT 1 FROM gateway_routes WHERE source_country = 'TG' AND dest_country = 'CG' AND gateway = 'FEEXPAY');

INSERT INTO gateway_routes (source_country, dest_country, gateway, priority, gateway_fee_percent, enabled) 
SELECT 'CG', 'TG', 'FEEXPAY', 1, 3.00, true
WHERE NOT EXISTS (SELECT 1 FROM gateway_routes WHERE source_country = 'CG' AND dest_country = 'TG' AND gateway = 'FEEXPAY');

-- ========================================
-- STOCKS pour CI (hub principal)
-- ========================================

INSERT INTO gateway_stocks (gateway, country, balance, min_threshold) 
SELECT 'CINETPAY', 'CI', 5000000, 100000
WHERE NOT EXISTS (SELECT 1 FROM gateway_stocks WHERE gateway = 'CINETPAY' AND country = 'CI');

INSERT INTO gateway_stocks (gateway, country, balance, min_threshold) 
SELECT 'FEEXPAY', 'CI', 5000000, 100000
WHERE NOT EXISTS (SELECT 1 FROM gateway_stocks WHERE gateway = 'FEEXPAY' AND country = 'CI');

INSERT INTO gateway_stocks (gateway, country, balance, min_threshold) 
SELECT 'PAYTECH', 'CI', 5000000, 100000
WHERE NOT EXISTS (SELECT 1 FROM gateway_stocks WHERE gateway = 'PAYTECH' AND country = 'CI');

-- Stock pour CG
INSERT INTO gateway_stocks (gateway, country, balance, min_threshold) 
SELECT 'FEEXPAY', 'CG', 5000000, 100000
WHERE NOT EXISTS (SELECT 1 FROM gateway_stocks WHERE gateway = 'FEEXPAY' AND country = 'CG');
