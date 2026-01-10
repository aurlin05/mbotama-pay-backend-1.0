-- V12__add_missing_countries_routes.sql
-- Migration pour corriger les routes et ajouter les pays manquants

-- ========================================
-- CORRECTIONS des routes incorrectes
-- ========================================

-- Supprimer les routes FEEXPAY pour les pays non couverts (SN, BF)
DELETE FROM gateway_routes WHERE gateway = 'FEEXPAY' AND (source_country = 'SN' OR dest_country = 'SN');
DELETE FROM gateway_routes WHERE gateway = 'FEEXPAY' AND (source_country = 'BF' OR dest_country = 'BF');

-- Supprimer les stocks FEEXPAY pour SN et BF
DELETE FROM gateway_stocks WHERE gateway = 'FEEXPAY' AND country IN ('SN', 'BF');

-- Corriger les routes SN<->CG (FeexPay ne couvre pas SN, utiliser CINETPAY)
DELETE FROM gateway_routes WHERE source_country = 'SN' AND dest_country = 'CG' AND gateway = 'FEEXPAY';
DELETE FROM gateway_routes WHERE source_country = 'CG' AND dest_country = 'SN' AND gateway = 'FEEXPAY';
-- Aussi supprimer les anciennes avec noms complets (V11)
DELETE FROM gateway_routes WHERE source_country = 'SENEGAL' AND dest_country = 'CONGO_BRAZZAVILLE';
DELETE FROM gateway_routes WHERE source_country = 'CONGO_BRAZZAVILLE' AND dest_country = 'SENEGAL';

-- Ajouter route CG<->CG via FEEXPAY (Congo-Brazzaville interne)
INSERT INTO gateway_routes (source_country, dest_country, gateway, priority, gateway_fee_percent) VALUES
('CG', 'CG', 'FEEXPAY', 1, 2.70)
ON CONFLICT DO NOTHING;

-- Route SN<->CG doit passer par CINETPAY (seul à couvrir les deux)
INSERT INTO gateway_routes (source_country, dest_country, gateway, priority, gateway_fee_percent) VALUES
('SN', 'CG', 'CINETPAY', 1, 4.00),
('CG', 'SN', 'CINETPAY', 1, 4.00)
ON CONFLICT DO NOTHING;

-- Corriger SN interne : retirer FEEXPAY, garder PAYTECH et CINETPAY
-- (déjà supprimé ci-dessus, on s'assure que PAYTECH et CINETPAY existent)

-- Corriger BF interne : retirer FEEXPAY, garder CINETPAY uniquement
-- (déjà supprimé ci-dessus)

-- ========================================
-- Nouvelles routes de paiement
-- ========================================

-- Guinée → Guinée (CinetPay uniquement)
INSERT INTO gateway_routes (source_country, dest_country, gateway, priority, gateway_fee_percent) VALUES
('GN', 'GN', 'CINETPAY', 1, 3.50)
ON CONFLICT DO NOTHING;

-- Niger → Niger (CinetPay uniquement)
INSERT INTO gateway_routes (source_country, dest_country, gateway, priority, gateway_fee_percent) VALUES
('NE', 'NE', 'CINETPAY', 1, 3.50)
ON CONFLICT DO NOTHING;

-- RD Congo → RD Congo (CinetPay uniquement)
INSERT INTO gateway_routes (source_country, dest_country, gateway, priority, gateway_fee_percent) VALUES
('CD', 'CD', 'CINETPAY', 1, 4.00)
ON CONFLICT DO NOTHING;

-- Cameroun → Cameroun (CinetPay uniquement)
INSERT INTO gateway_routes (source_country, dest_country, gateway, priority, gateway_fee_percent) VALUES
('CM', 'CM', 'CINETPAY', 1, 3.50)
ON CONFLICT DO NOTHING;

-- Togo → Togo : ajouter CINETPAY comme alternative
INSERT INTO gateway_routes (source_country, dest_country, gateway, priority, gateway_fee_percent) VALUES
('TG', 'TG', 'CINETPAY', 2, 3.50)
ON CONFLICT DO NOTHING;

-- ========================================
-- Routes cross-border additionnelles
-- ========================================

-- Guinée cross-border
INSERT INTO gateway_routes (source_country, dest_country, gateway, priority, gateway_fee_percent) VALUES
('GN', 'SN', 'CINETPAY', 1, 4.00),
('SN', 'GN', 'CINETPAY', 1, 4.00),
('GN', 'CI', 'CINETPAY', 1, 4.00),
('CI', 'GN', 'CINETPAY', 1, 4.00),
('GN', 'ML', 'CINETPAY', 1, 4.00),
('ML', 'GN', 'CINETPAY', 1, 4.00)
ON CONFLICT DO NOTHING;

-- Niger cross-border
INSERT INTO gateway_routes (source_country, dest_country, gateway, priority, gateway_fee_percent) VALUES
('NE', 'BF', 'CINETPAY', 1, 4.00),
('BF', 'NE', 'CINETPAY', 1, 4.00),
('NE', 'BJ', 'CINETPAY', 1, 4.00),
('BJ', 'NE', 'CINETPAY', 1, 4.00),
('NE', 'TG', 'CINETPAY', 1, 4.00),
('TG', 'NE', 'CINETPAY', 1, 4.00)
ON CONFLICT DO NOTHING;

-- Cameroun cross-border
INSERT INTO gateway_routes (source_country, dest_country, gateway, priority, gateway_fee_percent) VALUES
('CM', 'CI', 'CINETPAY', 1, 4.00),
('CI', 'CM', 'CINETPAY', 1, 4.00),
('CM', 'SN', 'CINETPAY', 1, 4.00),
('SN', 'CM', 'CINETPAY', 1, 4.00)
ON CONFLICT DO NOTHING;

-- RD Congo cross-border (avec Congo-Brazzaville)
INSERT INTO gateway_routes (source_country, dest_country, gateway, priority, gateway_fee_percent) VALUES
('CD', 'CG', 'CINETPAY', 1, 4.50),
('CG', 'CD', 'CINETPAY', 1, 4.50)
ON CONFLICT DO NOTHING;

-- CI <-> CG (Côte d'Ivoire <-> Congo-Brazzaville)
INSERT INTO gateway_routes (source_country, dest_country, gateway, priority, gateway_fee_percent) VALUES
('CI', 'CG', 'FEEXPAY', 1, 3.50),
('CG', 'CI', 'FEEXPAY', 1, 3.50)
ON CONFLICT DO NOTHING;

-- BJ <-> CG (Bénin <-> Congo-Brazzaville)
INSERT INTO gateway_routes (source_country, dest_country, gateway, priority, gateway_fee_percent) VALUES
('BJ', 'CG', 'FEEXPAY', 1, 3.50),
('CG', 'BJ', 'FEEXPAY', 1, 3.50)
ON CONFLICT DO NOTHING;

-- TG <-> CG (Togo <-> Congo-Brazzaville)
INSERT INTO gateway_routes (source_country, dest_country, gateway, priority, gateway_fee_percent) VALUES
('TG', 'CG', 'FEEXPAY', 1, 3.50),
('CG', 'TG', 'FEEXPAY', 1, 3.50)
ON CONFLICT DO NOTHING;

-- ========================================
-- Stocks initiaux pour les nouveaux pays
-- ========================================

INSERT INTO gateway_stocks (gateway, country, balance, min_threshold) VALUES
('CINETPAY', 'GN', 0, 100000),
('CINETPAY', 'NE', 0, 100000),
('CINETPAY', 'CD', 0, 100000),
('CINETPAY', 'CM', 0, 100000),
('CINETPAY', 'TG', 0, 100000),
('CINETPAY', 'BF', 0, 100000),
('FEEXPAY', 'CG', 0, 100000)
ON CONFLICT DO NOTHING;
