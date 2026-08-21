-- V17__routing_engine_rework.sql
--
-- Refonte du moteur de routage :
--   1. Colonnes manquantes sur transactions (motif d'échec, index de référence)
--   2. Table des devis de routage épinglés
--   3. Ouverture de PayDunya (zone UEMOA) et Monetbil (CEMAC)
--   4. Retrait des routes PayTech vers la Côte d'Ivoire, non substantiées
--
-- Note sur la numérotation : les versions V13 et V14 n'existent pas dans ce
-- dépôt. Flyway tolère les trous, mais si ces versions ont été appliquées un
-- jour sur un environnement, l'historique y est divergent — à vérifier avant
-- déploiement (SELECT version FROM flyway_schema_history ORDER BY installed_rank).

-- ========================================
-- 1. TRANSACTIONS
-- ========================================

-- Motif d'échec technique dans son propre champ. Il écrasait auparavant
-- `description`, c'est-à-dire le libellé saisi par l'expéditeur.
ALTER TABLE transactions ADD COLUMN IF NOT EXISTS failure_reason VARCHAR(500);

-- Les callbacks partenaires et les vérifications de statut retrouvent la
-- transaction par cette référence, sans index : balayage complet à chaque appel.
CREATE INDEX IF NOT EXISTS idx_transactions_external_ref
    ON transactions (external_reference);

-- Contrainte d'unicité volontairement NON posée ici : les références
-- historiques ne tenaient que sur 8 caractères hexadécimaux (32 bits), des
-- doublons peuvent exister en base et feraient échouer le déploiement. Les
-- nouvelles références sont des UUID complets. Poser la contrainte dans une
-- migration ultérieure, après dédoublonnage :
--   SELECT external_reference, COUNT(*) FROM transactions
--   GROUP BY external_reference HAVING COUNT(*) > 1;

-- ========================================
-- 2. DEVIS DE ROUTAGE
-- ========================================

CREATE TABLE IF NOT EXISTS route_quotes (
    id               VARCHAR(40) PRIMARY KEY,
    user_id          BIGINT REFERENCES users(id) ON DELETE SET NULL,
    source_country   VARCHAR(30) NOT NULL,
    dest_country     VARCHAR(30) NOT NULL,
    recipient_phone  VARCHAR(20) NOT NULL,
    amount           BIGINT      NOT NULL,
    source_currency  VARCHAR(5)  NOT NULL,
    payout_amount    BIGINT      NOT NULL,
    payout_currency  VARCHAR(5)  NOT NULL,
    total_fee        BIGINT      NOT NULL,
    display_percent  INTEGER     NOT NULL,
    gateway          VARCHAR(20) NOT NULL,
    decision_json    TEXT,
    created_at       TIMESTAMP   NOT NULL,
    expires_at       TIMESTAMP   NOT NULL,
    consumed_at      TIMESTAMP
);

CREATE INDEX IF NOT EXISTS idx_route_quotes_expires ON route_quotes (expires_at);
CREATE INDEX IF NOT EXISTS idx_route_quotes_user    ON route_quotes (user_id);

-- ========================================
-- 3. PAYDUNYA — zone UEMOA (XOF)
-- ========================================
--
-- Apport : premier partenaire couvrant simultanément SN, BF et NE. Ces trois
-- marchés ne disposaient que d'un seul agrégateur chacun — une panne y fermait
-- le corridor sans repli.
--
-- Les routes sont créées ACTIVES mais la passerelle reste inerte tant que
-- `gateway.paydunya.enabled` est faux ou que les identifiants sont absents :
-- la porte d'éligibilité l'écarte alors avec un motif explicite. Aucune
-- activation silencieuse n'est possible.
--
-- Barème : valeurs provisoires, à confirmer auprès du partenaire.

INSERT INTO gateway_routes (source_country, dest_country, gateway, priority, gateway_fee_percent, enabled)
SELECT s.c, d.c, 'PAYDUNYA', 3,
       CASE WHEN s.c = d.c THEN 2.90 ELSE 3.40 END,
       true
FROM (VALUES ('SENEGAL'), ('COTE_DIVOIRE'), ('BENIN'), ('TOGO'),
             ('BURKINA_FASO'), ('MALI'), ('NIGER')) AS s(c)
CROSS JOIN (VALUES ('SENEGAL'), ('COTE_DIVOIRE'), ('BENIN'), ('TOGO'),
                   ('BURKINA_FASO'), ('MALI'), ('NIGER')) AS d(c)
WHERE NOT EXISTS (
    SELECT 1 FROM gateway_routes gr
    WHERE gr.source_country = s.c AND gr.dest_country = d.c AND gr.gateway = 'PAYDUNYA'
);

-- ========================================
-- 4. MONETBIL — CEMAC et au-delà
-- ========================================
--
-- Couverture relevée dans la table opérateurs de « Monetbil Payment API v1 » :
-- neuf pays documentés (CM, SN, CD, CG, BJ, GN, GA, LR, UG). Six sont modélisés
-- ici ; le Gabon, le Liberia et l'Ouganda n'existent pas dans l'énumération des
-- pays et relèvent d'une décision produit.
--
-- Apport décisif : Monetbil est le SECOND partenaire à couvrir le
-- Congo-Brazzaville — jusqu'ici desservi par une seule passerelle — et le
-- premier à couvrir simultanément le Sénégal et le Congo. Le corridor SN↔CG,
-- aujourd'hui sans route directe (V15 l'avait retiré, CinetPay ne couvrant pas
-- le Congo), redevient franchissable sans pont.
--
-- Attention : les corridors Monetbil traversent quatre zones monétaires
-- (XAF / XOF / CDF / GNF). Sans taux déclaré dans `routing.fx.rates`, la porte
-- devise les refusera — c'est voulu. Seul XOF↔XAF est déclaré (parité), ce qui
-- couvre SN↔CM, SN↔CG, BJ↔CM et BJ↔CG.

INSERT INTO gateway_routes (source_country, dest_country, gateway, priority, gateway_fee_percent, enabled)
SELECT s.c, d.c, 'MONETBIL', 3,
       CASE WHEN s.c = d.c THEN 3.20 ELSE 3.80 END,
       true
FROM (VALUES ('CAMEROON'), ('SENEGAL'), ('DRC'),
             ('CONGO_BRAZZAVILLE'), ('BENIN'), ('GUINEA')) AS s(c)
CROSS JOIN (VALUES ('CAMEROON'), ('SENEGAL'), ('DRC'),
                   ('CONGO_BRAZZAVILLE'), ('BENIN'), ('GUINEA')) AS d(c)
WHERE NOT EXISTS (
    SELECT 1 FROM gateway_routes gr
    WHERE gr.source_country = s.c AND gr.dest_country = d.c AND gr.gateway = 'MONETBIL'
);

-- Aucun stock n'est créé pour ces deux passerelles : l'absence de ligne de
-- stock signifie « la passerelle finance sur son propre flottant », ce qui est
-- le cas nominal sans préfinancement. La porte de liquidité laisse alors passer.

-- ========================================
-- 5. PAYTECH — retrait de la Côte d'Ivoire
-- ========================================
--
-- Aucun opérateur ivoirien ne déclare PayTech dans le catalogue, et la
-- couverture n'a pas été confirmée par le partenaire. Le contrôle de cohérence
-- au démarrage signalait la contradiction. Les routes sont désactivées plutôt
-- que supprimées, pour être réactivées d'une seule commande après confirmation.

UPDATE gateway_routes
SET enabled = false
WHERE gateway = 'PAYTECH'
  AND (source_country = 'COTE_DIVOIRE' OR dest_country = 'COTE_DIVOIRE');

DELETE FROM gateway_stocks
WHERE gateway = 'PAYTECH' AND country = 'COTE_DIVOIRE';
