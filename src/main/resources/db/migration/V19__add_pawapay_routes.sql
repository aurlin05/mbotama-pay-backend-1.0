-- V19__add_pawapay_routes.sql
--
-- Ouverture de pawaPay (Merchant API v2).
--
-- Couverture relevée dans la page « Providers » de la documentation v2 :
-- sept de nos onze pays (BJ, BF, CM, CI, CD, CG, SN). Le Togo, le Mali, le
-- Niger et la Guinée ne sont pas couverts. Opérateurs absents chez pawaPay :
-- Moov Côte d'Ivoire, Celtiis Bénin, Africell RDC.
--
-- Apport : un second chemin vers le Cameroun et la RD Congo, qui ne reposaient
-- que sur CinetPay, et un troisième vers le Congo-Brazzaville.
--
-- Comme pour PayDunya et Monetbil, les routes sont créées ACTIVES mais la
-- passerelle reste inerte tant que `gateway.pawapay.enabled` est faux ou que le
-- jeton d'API est absent. Aucune activation silencieuse n'est possible.
--
-- Corridors traversant une frontière monétaire (XOF / XAF / CDF) : sans taux
-- déclaré dans `routing.fx.rates`, la porte devise les refuse. Seul XOF↔XAF
-- l'est aujourd'hui.
--
-- Barème : valeurs provisoires, à confirmer auprès du partenaire.
--
-- Aucun stock n'est créé. Attention toutefois : les versements pawaPay sont
-- préfinancés par pays. Un portefeuille non approvisionné produit un refus
-- `PAWAPAY_WALLET_OUT_OF_FUNDS`, que le moteur traite comme un échec ordinaire
-- et contourne par repli.

INSERT INTO gateway_routes (source_country, dest_country, gateway, priority, gateway_fee_percent, enabled)
SELECT s.c, d.c, 'PAWAPAY', 3,
       CASE WHEN s.c = d.c THEN 3.00 ELSE 3.50 END,
       true
FROM (VALUES ('BENIN'), ('BURKINA_FASO'), ('CAMEROON'), ('COTE_DIVOIRE'),
             ('DRC'), ('CONGO_BRAZZAVILLE'), ('SENEGAL')) AS s(c)
CROSS JOIN (VALUES ('BENIN'), ('BURKINA_FASO'), ('CAMEROON'), ('COTE_DIVOIRE'),
                   ('DRC'), ('CONGO_BRAZZAVILLE'), ('SENEGAL')) AS d(c)
WHERE NOT EXISTS (
    SELECT 1 FROM gateway_routes gr
    WHERE gr.source_country = s.c AND gr.dest_country = d.c AND gr.gateway = 'PAWAPAY'
);
