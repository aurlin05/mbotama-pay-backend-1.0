-- V18__gateway_coverage_admin.sql
--
-- Couverture partenaire pilotable depuis l'administration.
--
-- La couverture d'un agrégateur est une donnée d'exploitation : elle change
-- quand le partenaire ouvre un marché, et il ne devrait pas falloir un
-- redéploiement pour la suivre. Elle était une constante compilée, puis une
-- propriété de configuration ; cette table en fait un état modifiable à chaud.
--
-- Chaîne de résolution : base > configuration > défaut du code.
-- Une colonne NULL signifie « non redéfini à ce niveau », le niveau inférieur
-- s'applique alors. On peut donc n'ouvrir qu'un pays sans retoucher les
-- opérateurs ni les devises.

CREATE TABLE IF NOT EXISTS gateway_coverage (
    gateway              VARCHAR(20) PRIMARY KEY,
    collection_countries VARCHAR(1000),
    payout_countries     VARCHAR(1000),
    currencies           VARCHAR(200),
    operators            VARCHAR(4000),
    supports_payout      BOOLEAN,
    note                 VARCHAR(500),
    updated_at           TIMESTAMP NOT NULL,
    updated_by           VARCHAR(40)
);

COMMENT ON TABLE gateway_coverage IS
    'Redéfinition à chaud de la couverture partenaire. NULL = non redéfini, le niveau inférieur (configuration puis code) s''applique.';
