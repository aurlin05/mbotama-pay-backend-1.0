-- V20__gateway_coverage_update_2026_10.sql
--
-- Mise à jour de la couverture partenaire, d'après la documentation officielle
-- relue le 9 octobre 2026.
--
--   1. FeexPay : ouverture du Sénégal et du Burkina Faso (API v2).
--   2. PayDunya : retrait du Niger, ouverture du Cameroun en versement.
--
-- Comme dans V17 et V19, les nouvelles routes ne servent que si la passerelle
-- est configurée : la porte d'éligibilité écarte une passerelle sans
-- identifiants avec un motif explicite.
--
-- Barèmes : valeurs provisoires, à confirmer auprès des partenaires.

-- ========================================
-- 1. FEEXPAY — Sénégal et Burkina Faso
-- ========================================
--
-- La documentation v2 liste désormais les versements Orange, Free et Wave au
-- Sénégal, et Moov au Burkina Faso (Orange et Wave Burkina exigent un code OTP
-- côté client, non pris en charge). Ce sont deux replis de plus pour des pays
-- qui reposaient surtout sur CinetPay.

INSERT INTO gateway_routes (source_country, dest_country, gateway, priority, gateway_fee_percent, enabled)
SELECT s.c, d.c, 'FEEXPAY', 1,
       CASE WHEN s.c = d.c THEN 2.70 ELSE 3.50 END,
       true
FROM (VALUES ('BENIN'), ('TOGO'), ('COTE_DIVOIRE'), ('CONGO_BRAZZAVILLE'),
             ('SENEGAL'), ('BURKINA_FASO')) AS s(c)
CROSS JOIN (VALUES ('BENIN'), ('TOGO'), ('COTE_DIVOIRE'), ('CONGO_BRAZZAVILLE'),
                   ('SENEGAL'), ('BURKINA_FASO')) AS d(c)
WHERE NOT EXISTS (
    SELECT 1 FROM gateway_routes gr
    WHERE gr.source_country = s.c AND gr.dest_country = d.c AND gr.gateway = 'FEEXPAY'
);

-- ========================================
-- 2. PAYDUNYA — Niger retiré, Cameroun ouvert en versement
-- ========================================
--
-- Le Niger ne figure plus dans la documentation PayDunya, ni en encaissement
-- ni en versement. Les routes sont désactivées, pas supprimées, pour être
-- réactivées d'une seule commande si le partenaire confirme le contraire.

UPDATE gateway_routes
SET enabled = false
WHERE gateway = 'PAYDUNYA'
  AND (source_country = 'NIGER' OR dest_country = 'NIGER');

-- Versement MTN Cameroun (XAF), depuis les pays où PayDunya encaisse. Le
-- corridor XOF→XAF est couvert par le taux déclaré dans `routing.fx.rates`.

INSERT INTO gateway_routes (source_country, dest_country, gateway, priority, gateway_fee_percent, enabled)
SELECT s.c, 'CAMEROON', 'PAYDUNYA', 3, 3.40, true
FROM (VALUES ('SENEGAL'), ('COTE_DIVOIRE'), ('BENIN'), ('TOGO'),
             ('BURKINA_FASO'), ('MALI')) AS s(c)
WHERE NOT EXISTS (
    SELECT 1 FROM gateway_routes gr
    WHERE gr.source_country = s.c AND gr.dest_country = 'CAMEROON' AND gr.gateway = 'PAYDUNYA'
);
