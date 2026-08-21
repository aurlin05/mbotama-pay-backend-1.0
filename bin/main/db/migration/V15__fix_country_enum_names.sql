-- V15__fix_country_enum_names.sql
-- Migration complète des routes avec noms d'enum Java corrects
-- 
-- COUVERTURE DES AGRÉGATEURS:
-- FeexPay:  BENIN, TOGO, COTE_DIVOIRE, CONGO_BRAZZAVILLE
-- CinetPay: COTE_DIVOIRE, SENEGAL, MALI, GUINEA, CAMEROON, BURKINA_FASO, BENIN, TOGO, NIGER, DRC
-- PayTech:  SENEGAL, COTE_DIVOIRE, MALI

-- ========================================
-- NETTOYAGE COMPLET
-- ========================================

-- Supprimer toutes les routes existantes (on repart de zéro)
DELETE FROM gateway_routes;
DELETE FROM gateway_stocks;

-- ========================================
-- ROUTES LOCALES (même pays)
-- ========================================

-- SENEGAL (SN) - PayTech, CinetPay
INSERT INTO gateway_routes (source_country, dest_country, gateway, priority, gateway_fee_percent, enabled) VALUES
('SENEGAL', 'SENEGAL', 'PAYTECH', 1, 2.75, true),
('SENEGAL', 'SENEGAL', 'CINETPAY', 2, 3.50, true);

-- COTE_DIVOIRE (CI) - FeexPay, CinetPay, PayTech
INSERT INTO gateway_routes (source_country, dest_country, gateway, priority, gateway_fee_percent, enabled) VALUES
('COTE_DIVOIRE', 'COTE_DIVOIRE', 'FEEXPAY', 1, 2.70, true),
('COTE_DIVOIRE', 'COTE_DIVOIRE', 'CINETPAY', 2, 3.10, true),
('COTE_DIVOIRE', 'COTE_DIVOIRE', 'PAYTECH', 3, 3.50, true);

-- BENIN (BJ) - FeexPay, CinetPay
INSERT INTO gateway_routes (source_country, dest_country, gateway, priority, gateway_fee_percent, enabled) VALUES
('BENIN', 'BENIN', 'FEEXPAY', 1, 2.70, true),
('BENIN', 'BENIN', 'CINETPAY', 2, 3.50, true);

-- TOGO (TG) - FeexPay, CinetPay
INSERT INTO gateway_routes (source_country, dest_country, gateway, priority, gateway_fee_percent, enabled) VALUES
('TOGO', 'TOGO', 'FEEXPAY', 1, 2.70, true),
('TOGO', 'TOGO', 'CINETPAY', 2, 3.50, true);

-- CONGO_BRAZZAVILLE (CG) - FeexPay uniquement
INSERT INTO gateway_routes (source_country, dest_country, gateway, priority, gateway_fee_percent, enabled) VALUES
('CONGO_BRAZZAVILLE', 'CONGO_BRAZZAVILLE', 'FEEXPAY', 1, 2.70, true);

-- MALI (ML) - PayTech, CinetPay
INSERT INTO gateway_routes (source_country, dest_country, gateway, priority, gateway_fee_percent, enabled) VALUES
('MALI', 'MALI', 'PAYTECH', 1, 3.50, true),
('MALI', 'MALI', 'CINETPAY', 2, 4.00, true);

-- BURKINA_FASO (BF) - CinetPay uniquement
INSERT INTO gateway_routes (source_country, dest_country, gateway, priority, gateway_fee_percent, enabled) VALUES
('BURKINA_FASO', 'BURKINA_FASO', 'CINETPAY', 1, 3.50, true);

-- GUINEA (GN) - CinetPay uniquement
INSERT INTO gateway_routes (source_country, dest_country, gateway, priority, gateway_fee_percent, enabled) VALUES
('GUINEA', 'GUINEA', 'CINETPAY', 1, 3.50, true);

-- NIGER (NE) - CinetPay uniquement
INSERT INTO gateway_routes (source_country, dest_country, gateway, priority, gateway_fee_percent, enabled) VALUES
('NIGER', 'NIGER', 'CINETPAY', 1, 3.50, true);

-- DRC (CD) - CinetPay uniquement
INSERT INTO gateway_routes (source_country, dest_country, gateway, priority, gateway_fee_percent, enabled) VALUES
('DRC', 'DRC', 'CINETPAY', 1, 4.00, true);

-- CAMEROON (CM) - CinetPay uniquement
INSERT INTO gateway_routes (source_country, dest_country, gateway, priority, gateway_fee_percent, enabled) VALUES
('CAMEROON', 'CAMEROON', 'CINETPAY', 1, 3.50, true);

-- ========================================
-- ROUTES CROSS-BORDER DIRECTES
-- (Quand un agrégateur couvre les deux pays)
-- ========================================

-- === FeexPay routes (BJ, TG, CI, CG) ===

-- BENIN ↔ TOGO
INSERT INTO gateway_routes (source_country, dest_country, gateway, priority, gateway_fee_percent, enabled) VALUES
('BENIN', 'TOGO', 'FEEXPAY', 1, 2.70, true),
('TOGO', 'BENIN', 'FEEXPAY', 1, 2.70, true);

-- BENIN ↔ COTE_DIVOIRE
INSERT INTO gateway_routes (source_country, dest_country, gateway, priority, gateway_fee_percent, enabled) VALUES
('BENIN', 'COTE_DIVOIRE', 'FEEXPAY', 1, 2.70, true),
('COTE_DIVOIRE', 'BENIN', 'FEEXPAY', 1, 2.70, true);

-- BENIN ↔ CONGO_BRAZZAVILLE
INSERT INTO gateway_routes (source_country, dest_country, gateway, priority, gateway_fee_percent, enabled) VALUES
('BENIN', 'CONGO_BRAZZAVILLE', 'FEEXPAY', 1, 3.00, true),
('CONGO_BRAZZAVILLE', 'BENIN', 'FEEXPAY', 1, 3.00, true);

-- TOGO ↔ COTE_DIVOIRE
INSERT INTO gateway_routes (source_country, dest_country, gateway, priority, gateway_fee_percent, enabled) VALUES
('TOGO', 'COTE_DIVOIRE', 'FEEXPAY', 1, 2.70, true),
('COTE_DIVOIRE', 'TOGO', 'FEEXPAY', 1, 2.70, true);

-- TOGO ↔ CONGO_BRAZZAVILLE
INSERT INTO gateway_routes (source_country, dest_country, gateway, priority, gateway_fee_percent, enabled) VALUES
('TOGO', 'CONGO_BRAZZAVILLE', 'FEEXPAY', 1, 3.00, true),
('CONGO_BRAZZAVILLE', 'TOGO', 'FEEXPAY', 1, 3.00, true);

-- COTE_DIVOIRE ↔ CONGO_BRAZZAVILLE
INSERT INTO gateway_routes (source_country, dest_country, gateway, priority, gateway_fee_percent, enabled) VALUES
('COTE_DIVOIRE', 'CONGO_BRAZZAVILLE', 'FEEXPAY', 1, 3.00, true),
('CONGO_BRAZZAVILLE', 'COTE_DIVOIRE', 'FEEXPAY', 1, 3.00, true);

-- === CinetPay routes (CI, SN, ML, GN, CM, BF, BJ, TG, NE, CD) ===

-- SENEGAL ↔ COTE_DIVOIRE
INSERT INTO gateway_routes (source_country, dest_country, gateway, priority, gateway_fee_percent, enabled) VALUES
('SENEGAL', 'COTE_DIVOIRE', 'CINETPAY', 1, 3.50, true),
('COTE_DIVOIRE', 'SENEGAL', 'CINETPAY', 1, 3.50, true);

-- SENEGAL ↔ MALI
INSERT INTO gateway_routes (source_country, dest_country, gateway, priority, gateway_fee_percent, enabled) VALUES
('SENEGAL', 'MALI', 'CINETPAY', 1, 4.00, true),
('MALI', 'SENEGAL', 'CINETPAY', 1, 4.00, true);

-- SENEGAL ↔ GUINEA
INSERT INTO gateway_routes (source_country, dest_country, gateway, priority, gateway_fee_percent, enabled) VALUES
('SENEGAL', 'GUINEA', 'CINETPAY', 1, 4.00, true),
('GUINEA', 'SENEGAL', 'CINETPAY', 1, 4.00, true);

-- SENEGAL ↔ BURKINA_FASO
INSERT INTO gateway_routes (source_country, dest_country, gateway, priority, gateway_fee_percent, enabled) VALUES
('SENEGAL', 'BURKINA_FASO', 'CINETPAY', 1, 4.00, true),
('BURKINA_FASO', 'SENEGAL', 'CINETPAY', 1, 4.00, true);

-- SENEGAL ↔ BENIN
INSERT INTO gateway_routes (source_country, dest_country, gateway, priority, gateway_fee_percent, enabled) VALUES
('SENEGAL', 'BENIN', 'CINETPAY', 1, 3.50, true),
('BENIN', 'SENEGAL', 'CINETPAY', 1, 3.50, true);

-- SENEGAL ↔ TOGO
INSERT INTO gateway_routes (source_country, dest_country, gateway, priority, gateway_fee_percent, enabled) VALUES
('SENEGAL', 'TOGO', 'CINETPAY', 1, 3.50, true),
('TOGO', 'SENEGAL', 'CINETPAY', 1, 3.50, true);

-- SENEGAL ↔ NIGER
INSERT INTO gateway_routes (source_country, dest_country, gateway, priority, gateway_fee_percent, enabled) VALUES
('SENEGAL', 'NIGER', 'CINETPAY', 1, 4.00, true),
('NIGER', 'SENEGAL', 'CINETPAY', 1, 4.00, true);

-- SENEGAL ↔ CAMEROON
INSERT INTO gateway_routes (source_country, dest_country, gateway, priority, gateway_fee_percent, enabled) VALUES
('SENEGAL', 'CAMEROON', 'CINETPAY', 1, 4.00, true),
('CAMEROON', 'SENEGAL', 'CINETPAY', 1, 4.00, true);

-- SENEGAL ↔ DRC
INSERT INTO gateway_routes (source_country, dest_country, gateway, priority, gateway_fee_percent, enabled) VALUES
('SENEGAL', 'DRC', 'CINETPAY', 1, 4.50, true),
('DRC', 'SENEGAL', 'CINETPAY', 1, 4.50, true);

-- COTE_DIVOIRE ↔ MALI
INSERT INTO gateway_routes (source_country, dest_country, gateway, priority, gateway_fee_percent, enabled) VALUES
('COTE_DIVOIRE', 'MALI', 'CINETPAY', 1, 3.50, true),
('MALI', 'COTE_DIVOIRE', 'CINETPAY', 1, 3.50, true);

-- COTE_DIVOIRE ↔ GUINEA
INSERT INTO gateway_routes (source_country, dest_country, gateway, priority, gateway_fee_percent, enabled) VALUES
('COTE_DIVOIRE', 'GUINEA', 'CINETPAY', 1, 3.50, true),
('GUINEA', 'COTE_DIVOIRE', 'CINETPAY', 1, 3.50, true);

-- COTE_DIVOIRE ↔ BURKINA_FASO
INSERT INTO gateway_routes (source_country, dest_country, gateway, priority, gateway_fee_percent, enabled) VALUES
('COTE_DIVOIRE', 'BURKINA_FASO', 'CINETPAY', 1, 3.50, true),
('BURKINA_FASO', 'COTE_DIVOIRE', 'CINETPAY', 1, 3.50, true);

-- COTE_DIVOIRE ↔ BENIN (CinetPay backup)
INSERT INTO gateway_routes (source_country, dest_country, gateway, priority, gateway_fee_percent, enabled) VALUES
('COTE_DIVOIRE', 'BENIN', 'CINETPAY', 2, 3.50, true),
('BENIN', 'COTE_DIVOIRE', 'CINETPAY', 2, 3.50, true);

-- COTE_DIVOIRE ↔ TOGO (CinetPay backup)
INSERT INTO gateway_routes (source_country, dest_country, gateway, priority, gateway_fee_percent, enabled) VALUES
('COTE_DIVOIRE', 'TOGO', 'CINETPAY', 2, 3.50, true),
('TOGO', 'COTE_DIVOIRE', 'CINETPAY', 2, 3.50, true);

-- COTE_DIVOIRE ↔ NIGER
INSERT INTO gateway_routes (source_country, dest_country, gateway, priority, gateway_fee_percent, enabled) VALUES
('COTE_DIVOIRE', 'NIGER', 'CINETPAY', 1, 3.50, true),
('NIGER', 'COTE_DIVOIRE', 'CINETPAY', 1, 3.50, true);

-- COTE_DIVOIRE ↔ CAMEROON
INSERT INTO gateway_routes (source_country, dest_country, gateway, priority, gateway_fee_percent, enabled) VALUES
('COTE_DIVOIRE', 'CAMEROON', 'CINETPAY', 1, 3.50, true),
('CAMEROON', 'COTE_DIVOIRE', 'CINETPAY', 1, 3.50, true);

-- COTE_DIVOIRE ↔ DRC
INSERT INTO gateway_routes (source_country, dest_country, gateway, priority, gateway_fee_percent, enabled) VALUES
('COTE_DIVOIRE', 'DRC', 'CINETPAY', 1, 4.00, true),
('DRC', 'COTE_DIVOIRE', 'CINETPAY', 1, 4.00, true);

-- MALI ↔ GUINEA
INSERT INTO gateway_routes (source_country, dest_country, gateway, priority, gateway_fee_percent, enabled) VALUES
('MALI', 'GUINEA', 'CINETPAY', 1, 4.00, true),
('GUINEA', 'MALI', 'CINETPAY', 1, 4.00, true);

-- MALI ↔ BURKINA_FASO
INSERT INTO gateway_routes (source_country, dest_country, gateway, priority, gateway_fee_percent, enabled) VALUES
('MALI', 'BURKINA_FASO', 'CINETPAY', 1, 4.00, true),
('BURKINA_FASO', 'MALI', 'CINETPAY', 1, 4.00, true);

-- MALI ↔ BENIN
INSERT INTO gateway_routes (source_country, dest_country, gateway, priority, gateway_fee_percent, enabled) VALUES
('MALI', 'BENIN', 'CINETPAY', 1, 4.00, true),
('BENIN', 'MALI', 'CINETPAY', 1, 4.00, true);

-- MALI ↔ TOGO
INSERT INTO gateway_routes (source_country, dest_country, gateway, priority, gateway_fee_percent, enabled) VALUES
('MALI', 'TOGO', 'CINETPAY', 1, 4.00, true),
('TOGO', 'MALI', 'CINETPAY', 1, 4.00, true);

-- MALI ↔ NIGER
INSERT INTO gateway_routes (source_country, dest_country, gateway, priority, gateway_fee_percent, enabled) VALUES
('MALI', 'NIGER', 'CINETPAY', 1, 4.00, true),
('NIGER', 'MALI', 'CINETPAY', 1, 4.00, true);

-- MALI ↔ CAMEROON
INSERT INTO gateway_routes (source_country, dest_country, gateway, priority, gateway_fee_percent, enabled) VALUES
('MALI', 'CAMEROON', 'CINETPAY', 1, 4.00, true),
('CAMEROON', 'MALI', 'CINETPAY', 1, 4.00, true);

-- MALI ↔ DRC
INSERT INTO gateway_routes (source_country, dest_country, gateway, priority, gateway_fee_percent, enabled) VALUES
('MALI', 'DRC', 'CINETPAY', 1, 4.50, true),
('DRC', 'MALI', 'CINETPAY', 1, 4.50, true);

-- GUINEA ↔ BURKINA_FASO
INSERT INTO gateway_routes (source_country, dest_country, gateway, priority, gateway_fee_percent, enabled) VALUES
('GUINEA', 'BURKINA_FASO', 'CINETPAY', 1, 4.00, true),
('BURKINA_FASO', 'GUINEA', 'CINETPAY', 1, 4.00, true);

-- GUINEA ↔ BENIN
INSERT INTO gateway_routes (source_country, dest_country, gateway, priority, gateway_fee_percent, enabled) VALUES
('GUINEA', 'BENIN', 'CINETPAY', 1, 4.00, true),
('BENIN', 'GUINEA', 'CINETPAY', 1, 4.00, true);

-- GUINEA ↔ TOGO
INSERT INTO gateway_routes (source_country, dest_country, gateway, priority, gateway_fee_percent, enabled) VALUES
('GUINEA', 'TOGO', 'CINETPAY', 1, 4.00, true),
('TOGO', 'GUINEA', 'CINETPAY', 1, 4.00, true);

-- GUINEA ↔ NIGER
INSERT INTO gateway_routes (source_country, dest_country, gateway, priority, gateway_fee_percent, enabled) VALUES
('GUINEA', 'NIGER', 'CINETPAY', 1, 4.00, true),
('NIGER', 'GUINEA', 'CINETPAY', 1, 4.00, true);

-- GUINEA ↔ CAMEROON
INSERT INTO gateway_routes (source_country, dest_country, gateway, priority, gateway_fee_percent, enabled) VALUES
('GUINEA', 'CAMEROON', 'CINETPAY', 1, 4.00, true),
('CAMEROON', 'GUINEA', 'CINETPAY', 1, 4.00, true);

-- GUINEA ↔ DRC
INSERT INTO gateway_routes (source_country, dest_country, gateway, priority, gateway_fee_percent, enabled) VALUES
('GUINEA', 'DRC', 'CINETPAY', 1, 4.50, true),
('DRC', 'GUINEA', 'CINETPAY', 1, 4.50, true);

-- BURKINA_FASO ↔ BENIN
INSERT INTO gateway_routes (source_country, dest_country, gateway, priority, gateway_fee_percent, enabled) VALUES
('BURKINA_FASO', 'BENIN', 'CINETPAY', 1, 3.50, true),
('BENIN', 'BURKINA_FASO', 'CINETPAY', 1, 3.50, true);

-- BURKINA_FASO ↔ TOGO
INSERT INTO gateway_routes (source_country, dest_country, gateway, priority, gateway_fee_percent, enabled) VALUES
('BURKINA_FASO', 'TOGO', 'CINETPAY', 1, 3.50, true),
('TOGO', 'BURKINA_FASO', 'CINETPAY', 1, 3.50, true);

-- BURKINA_FASO ↔ NIGER
INSERT INTO gateway_routes (source_country, dest_country, gateway, priority, gateway_fee_percent, enabled) VALUES
('BURKINA_FASO', 'NIGER', 'CINETPAY', 1, 3.50, true),
('NIGER', 'BURKINA_FASO', 'CINETPAY', 1, 3.50, true);

-- BURKINA_FASO ↔ CAMEROON
INSERT INTO gateway_routes (source_country, dest_country, gateway, priority, gateway_fee_percent, enabled) VALUES
('BURKINA_FASO', 'CAMEROON', 'CINETPAY', 1, 4.00, true),
('CAMEROON', 'BURKINA_FASO', 'CINETPAY', 1, 4.00, true);

-- BURKINA_FASO ↔ DRC
INSERT INTO gateway_routes (source_country, dest_country, gateway, priority, gateway_fee_percent, enabled) VALUES
('BURKINA_FASO', 'DRC', 'CINETPAY', 1, 4.50, true),
('DRC', 'BURKINA_FASO', 'CINETPAY', 1, 4.50, true);

-- BENIN ↔ TOGO (CinetPay backup)
INSERT INTO gateway_routes (source_country, dest_country, gateway, priority, gateway_fee_percent, enabled) VALUES
('BENIN', 'TOGO', 'CINETPAY', 2, 3.50, true),
('TOGO', 'BENIN', 'CINETPAY', 2, 3.50, true);

-- BENIN ↔ NIGER
INSERT INTO gateway_routes (source_country, dest_country, gateway, priority, gateway_fee_percent, enabled) VALUES
('BENIN', 'NIGER', 'CINETPAY', 1, 3.50, true),
('NIGER', 'BENIN', 'CINETPAY', 1, 3.50, true);

-- BENIN ↔ CAMEROON
INSERT INTO gateway_routes (source_country, dest_country, gateway, priority, gateway_fee_percent, enabled) VALUES
('BENIN', 'CAMEROON', 'CINETPAY', 1, 4.00, true),
('CAMEROON', 'BENIN', 'CINETPAY', 1, 4.00, true);

-- BENIN ↔ DRC
INSERT INTO gateway_routes (source_country, dest_country, gateway, priority, gateway_fee_percent, enabled) VALUES
('BENIN', 'DRC', 'CINETPAY', 1, 4.50, true),
('DRC', 'BENIN', 'CINETPAY', 1, 4.50, true);

-- TOGO ↔ NIGER
INSERT INTO gateway_routes (source_country, dest_country, gateway, priority, gateway_fee_percent, enabled) VALUES
('TOGO', 'NIGER', 'CINETPAY', 1, 3.50, true),
('NIGER', 'TOGO', 'CINETPAY', 1, 3.50, true);

-- TOGO ↔ CAMEROON
INSERT INTO gateway_routes (source_country, dest_country, gateway, priority, gateway_fee_percent, enabled) VALUES
('TOGO', 'CAMEROON', 'CINETPAY', 1, 4.00, true),
('CAMEROON', 'TOGO', 'CINETPAY', 1, 4.00, true);

-- TOGO ↔ DRC
INSERT INTO gateway_routes (source_country, dest_country, gateway, priority, gateway_fee_percent, enabled) VALUES
('TOGO', 'DRC', 'CINETPAY', 1, 4.50, true),
('DRC', 'TOGO', 'CINETPAY', 1, 4.50, true);

-- NIGER ↔ CAMEROON
INSERT INTO gateway_routes (source_country, dest_country, gateway, priority, gateway_fee_percent, enabled) VALUES
('NIGER', 'CAMEROON', 'CINETPAY', 1, 4.00, true),
('CAMEROON', 'NIGER', 'CINETPAY', 1, 4.00, true);

-- NIGER ↔ DRC
INSERT INTO gateway_routes (source_country, dest_country, gateway, priority, gateway_fee_percent, enabled) VALUES
('NIGER', 'DRC', 'CINETPAY', 1, 4.50, true),
('DRC', 'NIGER', 'CINETPAY', 1, 4.50, true);

-- CAMEROON ↔ DRC
INSERT INTO gateway_routes (source_country, dest_country, gateway, priority, gateway_fee_percent, enabled) VALUES
('CAMEROON', 'DRC', 'CINETPAY', 1, 4.00, true),
('DRC', 'CAMEROON', 'CINETPAY', 1, 4.00, true);

-- === PayTech routes (SN, CI, ML) - backup routes ===

-- SENEGAL ↔ COTE_DIVOIRE (PayTech backup)
INSERT INTO gateway_routes (source_country, dest_country, gateway, priority, gateway_fee_percent, enabled) VALUES
('SENEGAL', 'COTE_DIVOIRE', 'PAYTECH', 2, 3.75, true),
('COTE_DIVOIRE', 'SENEGAL', 'PAYTECH', 2, 3.75, true);

-- SENEGAL ↔ MALI (PayTech backup)
INSERT INTO gateway_routes (source_country, dest_country, gateway, priority, gateway_fee_percent, enabled) VALUES
('SENEGAL', 'MALI', 'PAYTECH', 2, 4.25, true),
('MALI', 'SENEGAL', 'PAYTECH', 2, 4.25, true);

-- COTE_DIVOIRE ↔ MALI (PayTech backup)
INSERT INTO gateway_routes (source_country, dest_country, gateway, priority, gateway_fee_percent, enabled) VALUES
('COTE_DIVOIRE', 'MALI', 'PAYTECH', 2, 3.75, true),
('MALI', 'COTE_DIVOIRE', 'PAYTECH', 2, 3.75, true);

-- ========================================
-- GATEWAY STOCKS (soldes par pays)
-- ========================================

-- FeexPay stocks (BJ, TG, CI, CG)
INSERT INTO gateway_stocks (gateway, country, balance, min_threshold) VALUES
('FEEXPAY', 'BENIN', 10000000, 500000),
('FEEXPAY', 'TOGO', 10000000, 500000),
('FEEXPAY', 'COTE_DIVOIRE', 15000000, 1000000),
('FEEXPAY', 'CONGO_BRAZZAVILLE', 8000000, 500000);

-- CinetPay stocks (CI, SN, ML, GN, CM, BF, BJ, TG, NE, CD)
INSERT INTO gateway_stocks (gateway, country, balance, min_threshold) VALUES
('CINETPAY', 'COTE_DIVOIRE', 20000000, 1000000),
('CINETPAY', 'SENEGAL', 15000000, 1000000),
('CINETPAY', 'MALI', 8000000, 500000),
('CINETPAY', 'GUINEA', 5000000, 300000),
('CINETPAY', 'CAMEROON', 10000000, 500000),
('CINETPAY', 'BURKINA_FASO', 8000000, 500000),
('CINETPAY', 'BENIN', 10000000, 500000),
('CINETPAY', 'TOGO', 8000000, 500000),
('CINETPAY', 'NIGER', 5000000, 300000),
('CINETPAY', 'DRC', 8000000, 500000);

-- PayTech stocks (SN, CI, ML)
INSERT INTO gateway_stocks (gateway, country, balance, min_threshold) VALUES
('PAYTECH', 'SENEGAL', 12000000, 800000),
('PAYTECH', 'COTE_DIVOIRE', 10000000, 500000),
('PAYTECH', 'MALI', 5000000, 300000);