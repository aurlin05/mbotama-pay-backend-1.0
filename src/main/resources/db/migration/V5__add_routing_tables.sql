-- V5__add_routing_tables.sql
-- Migration pour le système de routage intelligent

-- Table des stocks par passerelle et pays
CREATE TABLE gateway_stocks (
    id BIGSERIAL PRIMARY KEY,
    gateway VARCHAR(20) NOT NULL,
    country VARCHAR(5) NOT NULL,
    balance BIGINT NOT NULL DEFAULT 0,
    min_threshold BIGINT NOT NULL DEFAULT 100000,
    last_updated TIMESTAMP NOT NULL DEFAULT NOW(),
    UNIQUE(gateway, country)
);

-- Table des règles de routage
CREATE TABLE gateway_routes (
    id BIGSERIAL PRIMARY KEY,
    source_country VARCHAR(5) NOT NULL,
    dest_country VARCHAR(5) NOT NULL,
    gateway VARCHAR(20) NOT NULL,
    priority INT NOT NULL DEFAULT 1,
    gateway_fee_percent DECIMAL(5,2) NOT NULL DEFAULT 2.70,
    enabled BOOLEAN NOT NULL DEFAULT true
);

-- Ajout des colonnes de routage sur transactions
ALTER TABLE transactions ADD COLUMN source_country VARCHAR(5);
ALTER TABLE transactions ADD COLUMN dest_country VARCHAR(5);
ALTER TABLE transactions ADD COLUMN collection_gateway VARCHAR(20);
ALTER TABLE transactions ADD COLUMN payout_gateway VARCHAR(20);
ALTER TABLE transactions ADD COLUMN used_stock BOOLEAN DEFAULT false;
ALTER TABLE transactions ADD COLUMN gateway_fee BIGINT DEFAULT 0;
ALTER TABLE transactions ADD COLUMN app_fee BIGINT DEFAULT 0;

-- Index pour performance des requêtes de routage
CREATE INDEX idx_gateway_routes_countries 
ON gateway_routes(source_country, dest_country, enabled);

CREATE INDEX idx_gateway_stocks_gateway_country
ON gateway_stocks(gateway, country);

-- ========================================
-- Données initiales de routage
-- Frais = Payin + Payout combinés
-- ========================================

INSERT INTO gateway_routes (source_country, dest_country, gateway, priority, gateway_fee_percent) VALUES
-- Sénégal → Sénégal
('SN', 'SN', 'FEEXPAY', 1, 2.70),    -- Payin 1.7% + Payout 1%
('SN', 'SN', 'PAYTECH', 2, 2.75),    -- Payin 1.75% + Payout 1%
('SN', 'SN', 'CINETPAY', 3, 3.50),   -- Payin 2% + Payout 1.5%

-- Côte d'Ivoire → Côte d'Ivoire
('CI', 'CI', 'FEEXPAY', 1, 2.70),    -- Payin 1.7% + Payout 1%
('CI', 'CI', 'CINETPAY', 2, 3.10),   -- Payin 1.8% (MTN) + Payout 1.3%

-- Bénin → Bénin
('BJ', 'BJ', 'FEEXPAY', 1, 2.70),
('BJ', 'BJ', 'CINETPAY', 2, 3.50),

-- Togo → Togo
('TG', 'TG', 'FEEXPAY', 1, 2.70),

-- Burkina Faso → Burkina Faso
('BF', 'BF', 'FEEXPAY', 1, 2.70),
('BF', 'BF', 'CINETPAY', 2, 3.50),

-- Mali → Mali
('ML', 'ML', 'PAYTECH', 1, 3.50),
('ML', 'ML', 'CINETPAY', 2, 4.00),

-- Cross-border routes
('SN', 'CI', 'CINETPAY', 1, 3.50),
('CI', 'SN', 'CINETPAY', 1, 3.50),
('SN', 'ML', 'PAYTECH', 1, 3.75),
('SN', 'BJ', 'FEEXPAY', 1, 2.70),
('SN', 'BJ', 'CINETPAY', 2, 3.50),
('CI', 'BJ', 'FEEXPAY', 1, 2.70),
('CI', 'BJ', 'CINETPAY', 2, 3.50),
('BJ', 'TG', 'FEEXPAY', 1, 2.70),
('TG', 'BJ', 'FEEXPAY', 1, 2.70),
('BF', 'CI', 'CINETPAY', 1, 3.50),
('CI', 'BF', 'CINETPAY', 1, 3.50);

-- Stocks initiaux (à alimenter manuellement)
INSERT INTO gateway_stocks (gateway, country, balance, min_threshold) VALUES
('FEEXPAY', 'SN', 0, 100000),
('FEEXPAY', 'BJ', 0, 100000),
('FEEXPAY', 'CI', 0, 100000),
('FEEXPAY', 'TG', 0, 100000),
('FEEXPAY', 'BF', 0, 100000),
('PAYTECH', 'SN', 0, 100000),
('PAYTECH', 'ML', 0, 100000),
('CINETPAY', 'SN', 0, 100000),
('CINETPAY', 'CI', 0, 100000),
('CINETPAY', 'BJ', 0, 100000),
('CINETPAY', 'ML', 0, 100000);
