-- V13__fix_bridge_routes.sql
-- Correction des routes pour le bridge routing

-- ========================================
-- SUPPRESSION des routes incorrectes
-- ========================================

-- CinetPay NE COUVRE PAS le Congo-Brazzaville (CG)
-- Supprimer les routes SN<->CG via CINETPAY (incorrectes)
DELETE FROM gateway_routes WHERE source_country = 'SN' AND dest_country = 'CG' AND gateway = 'CINETPAY';
DELETE FROM gateway_routes WHERE source_country = 'CG' AND dest_country = 'SN' AND gateway = 'CINETPAY';

-- CinetPay NE COUVRE PAS le Congo-Brazzaville (CG)
-- Supprimer les routes CD<->CG via CINETPAY (incorrectes - CD oui, CG non)
DELETE FROM gateway_routes WHERE source_country = 'CD' AND dest_country = 'CG' AND gateway = 'CINETPAY';
DELETE FROM gateway_routes WHERE source_country = 'CG' AND dest_country = 'CD' AND gateway = 'CINETPAY';

-- ========================================
-- ROUTES NÉCESSAIRES POUR LE BRIDGE ROUTING
-- ========================================

-- Pour que SN → CG fonctionne via bridge (SN → CI → CG):
-- 1. SN → CI doit exister (CinetPay ou PayTech)
-- 2. CI → CG doit exister (FeexPay)

-- Route SN → CI (CinetPay - les deux pays sont couverts)
INSERT INTO gateway_routes (source_country, dest_country, gateway, priority, gateway_fee_percent, active) VALUES
('SN', 'CI', 'CINETPAY', 1, 3.00, true)
ON CONFLICT (source_country, dest_country, gateway) DO UPDATE SET active = true, gateway_fee_percent = 3.00;

-- Route CI → SN (CinetPay)
INSERT INTO gateway_routes (source_country, dest_country, gateway, priority, gateway_fee_percent, active) VALUES
('CI', 'SN', 'CINETPAY', 1, 3.00, true)
ON CONFLICT (source_country, dest_country, gateway) DO UPDATE SET active = true, gateway_fee_percent = 3.00;

-- Route SN → CI (PayTech - alternative)
INSERT INTO gateway_routes (source_country, dest_country, gateway, priority, gateway_fee_percent, active) VALUES
('SN', 'CI', 'PAYTECH', 2, 3.50, true)
ON CONFLICT (source_country, dest_country, gateway) DO UPDATE SET active = true, gateway_fee_percent = 3.50;

-- Route CI → SN (PayTech - alternative)
INSERT INTO gateway_routes (source_country, dest_country, gateway, priority, gateway_fee_percent, active) VALUES
('CI', 'SN', 'PAYTECH', 2, 3.50, true)
ON CONFLICT (source_country, dest_country, gateway) DO UPDATE SET active = true, gateway_fee_percent = 3.50;

-- Route CI → CG (FeexPay - seul à couvrir CG)
INSERT INTO gateway_routes (source_country, dest_country, gateway, priority, gateway_fee_percent, active) VALUES
('CI', 'CG', 'FEEXPAY', 1, 3.00, true)
ON CONFLICT (source_country, dest_country, gateway) DO UPDATE SET active = true, gateway_fee_percent = 3.00;

-- Route CG → CI (FeexPay)
INSERT INTO gateway_routes (source_country, dest_country, gateway, priority, gateway_fee_percent, active) VALUES
('CG', 'CI', 'FEEXPAY', 1, 3.00, true)
ON CONFLICT (source_country, dest_country, gateway) DO UPDATE SET active = true, gateway_fee_percent = 3.00;

-- ========================================
-- AUTRES ROUTES BRIDGE UTILES
-- ========================================

-- BJ → CI (FeexPay et CinetPay couvrent les deux)
INSERT INTO gateway_routes (source_country, dest_country, gateway, priority, gateway_fee_percent, active) VALUES
('BJ', 'CI', 'FEEXPAY', 1, 2.50, true),
('CI', 'BJ', 'FEEXPAY', 1, 2.50, true),
('BJ', 'CI', 'CINETPAY', 2, 3.00, true),
('CI', 'BJ', 'CINETPAY', 2, 3.00, true)
ON CONFLICT (source_country, dest_country, gateway) DO UPDATE SET active = true;

-- TG → CI (FeexPay et CinetPay couvrent les deux)
INSERT INTO gateway_routes (source_country, dest_country, gateway, priority, gateway_fee_percent, active) VALUES
('TG', 'CI', 'FEEXPAY', 1, 2.50, true),
('CI', 'TG', 'FEEXPAY', 1, 2.50, true),
('TG', 'CI', 'CINETPAY', 2, 3.00, true),
('CI', 'TG', 'CINETPAY', 2, 3.00, true)
ON CONFLICT (source_country, dest_country, gateway) DO UPDATE SET active = true;

-- BJ → CG (FeexPay couvre les deux)
INSERT INTO gateway_routes (source_country, dest_country, gateway, priority, gateway_fee_percent, active) VALUES
('BJ', 'CG', 'FEEXPAY', 1, 3.00, true),
('CG', 'BJ', 'FEEXPAY', 1, 3.00, true)
ON CONFLICT (source_country, dest_country, gateway) DO UPDATE SET active = true;

-- TG → CG (FeexPay couvre les deux)
INSERT INTO gateway_routes (source_country, dest_country, gateway, priority, gateway_fee_percent, active) VALUES
('TG', 'CG', 'FEEXPAY', 1, 3.00, true),
('CG', 'TG', 'FEEXPAY', 1, 3.00, true)
ON CONFLICT (source_country, dest_country, gateway) DO UPDATE SET active = true;

-- ========================================
-- STOCKS pour CI (hub principal)
-- ========================================

INSERT INTO gateway_stocks (gateway, country, balance, min_threshold) VALUES
('CINETPAY', 'CI', 5000000, 100000),
('FEEXPAY', 'CI', 5000000, 100000),
('PAYTECH', 'CI', 5000000, 100000)
ON CONFLICT (gateway, country) DO UPDATE SET balance = GREATEST(gateway_stocks.balance, 5000000);
