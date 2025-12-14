-- Normalize country codes to enum names for gateway_routes and gateway_stocks

-- gateway_routes: source_country
UPDATE gateway_routes SET source_country='SENEGAL' WHERE source_country='SN';
UPDATE gateway_routes SET source_country='COTE_DIVOIRE' WHERE source_country='CI';
UPDATE gateway_routes SET source_country='BENIN' WHERE source_country='BJ';
UPDATE gateway_routes SET source_country='TOGO' WHERE source_country='TG';
UPDATE gateway_routes SET source_country='BURKINA_FASO' WHERE source_country='BF';
UPDATE gateway_routes SET source_country='MALI' WHERE source_country='ML';
UPDATE gateway_routes SET source_country='CAMEROON' WHERE source_country='CM';
UPDATE gateway_routes SET source_country='GUINEA' WHERE source_country='GN';
UPDATE gateway_routes SET source_country='NIGER' WHERE source_country='NE';
UPDATE gateway_routes SET source_country='NIGERIA' WHERE source_country='NG';
UPDATE gateway_routes SET source_country='CONGO_BRAZZAVILLE' WHERE source_country='CG';
UPDATE gateway_routes SET source_country='DRC' WHERE source_country='CD';

-- gateway_routes: dest_country
UPDATE gateway_routes SET dest_country='SENEGAL' WHERE dest_country='SN';
UPDATE gateway_routes SET dest_country='COTE_DIVOIRE' WHERE dest_country='CI';
UPDATE gateway_routes SET dest_country='BENIN' WHERE dest_country='BJ';
UPDATE gateway_routes SET dest_country='TOGO' WHERE dest_country='TG';
UPDATE gateway_routes SET dest_country='BURKINA_FASO' WHERE dest_country='BF';
UPDATE gateway_routes SET dest_country='MALI' WHERE dest_country='ML';
UPDATE gateway_routes SET dest_country='CAMEROON' WHERE dest_country='CM';
UPDATE gateway_routes SET dest_country='GUINEA' WHERE dest_country='GN';
UPDATE gateway_routes SET dest_country='NIGER' WHERE dest_country='NE';
UPDATE gateway_routes SET dest_country='NIGERIA' WHERE dest_country='NG';
UPDATE gateway_routes SET dest_country='CONGO_BRAZZAVILLE' WHERE dest_country='CG';
UPDATE gateway_routes SET dest_country='DRC' WHERE dest_country='CD';

-- gateway_stocks: country
UPDATE gateway_stocks SET country='SENEGAL' WHERE country='SN';
UPDATE gateway_stocks SET country='COTE_DIVOIRE' WHERE country='CI';
UPDATE gateway_stocks SET country='BENIN' WHERE country='BJ';
UPDATE gateway_stocks SET country='TOGO' WHERE country='TG';
UPDATE gateway_stocks SET country='BURKINA_FASO' WHERE country='BF';
UPDATE gateway_stocks SET country='MALI' WHERE country='ML';
UPDATE gateway_stocks SET country='CAMEROON' WHERE country='CM';
UPDATE gateway_stocks SET country='GUINEA' WHERE country='GN';
UPDATE gateway_stocks SET country='NIGER' WHERE country='NE';
UPDATE gateway_stocks SET country='NIGERIA' WHERE country='NG';
UPDATE gateway_stocks SET country='CONGO_BRAZZAVILLE' WHERE country='CG';
UPDATE gateway_stocks SET country='DRC' WHERE country='CD';
