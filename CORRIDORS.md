# Corridors et couverture partenaire

État de référence après la refonte du moteur de routage (migration `V17`).
Ce document décrit **ce que le système fait réellement**, pas ce qu'on aimerait
qu'il fasse. Toute divergence entre ce document et la table `gateway_routes` est
signalée au démarrage par le contrôle de cohérence.

---

## 1. Passerelles et couverture

| Passerelle | Pays de versement | Devises | État |
|---|---|---|---|
| **FeexPay** | BJ, TG, CI, CG | XOF, XAF | actif |
| **CinetPay** | CI, SN, ML, GN, CM, BF, BJ, TG, NE, CD | XOF, XAF, GNF, CDF | actif |
| **PayTech** | SN, ML | XOF | actif |
| **PayDunya** | SN, CI, BJ, TG, BF, ML, NE | XOF | **inactif** — identifiants requis |
| **Monetbil** | CM, SN, CD, CG, BJ, GN | XAF, XOF, CDF, GNF | **inactif** — identifiants requis |

Une passerelle « inactive » a ses routes déclarées en base, mais
`isOperational()` renvoie faux tant que `gateway.<nom>.enabled` est faux ou que
les identifiants manquent. La porte d'éligibilité l'écarte alors avec le motif
« passerelle non configurée ou non validée ». Aucune activation silencieuse
n'est possible.

### Couverture modifiée par cette refonte

- **PayTech perd la Côte d'Ivoire.** Aucun opérateur ivoirien ne déclarait
  PayTech dans le catalogue, et la couverture n'a pas été confirmée par le
  partenaire. Les routes sont **désactivées, pas supprimées** : une seule
  commande les réactive après confirmation.
  ```sql
  UPDATE gateway_routes SET enabled = true
  WHERE gateway = 'PAYTECH'
    AND (source_country = 'COTE_DIVOIRE' OR dest_country = 'COTE_DIVOIRE');
  ```

- **FeexPay reste sur 4 pays.** Le javadoc de la classe en annonçait 6 (avec
  Sénégal et Burkina Faso) quand la constante n'en déclarait que 4, et la
  migration `V12` avait supprimé les routes SN et BF en les qualifiant de
  « pays non couverts ». La couverture à jour n'a pas pu être établie à partir
  de la documentation publique du partenaire. **À confirmer.** Une fois connue,
  elle se met à jour sans redéploiement (§ 5).

---

## 2. Zones monétaires

Le moteur ne suppose plus XOF partout. Chaque pays porte sa devise, et un
corridor qui traverse une frontière monétaire exige un taux déclaré.

| Devise | Pays |
|---|---|
| **XOF** | BJ, BF, CI, ML, NE, SN, TG |
| **XAF** | CM, CG |
| **GNF** | GN |
| **CDF** | CD |

Taux déclarés dans `routing.fx.rates` :

| Paire | Taux | Justification |
|---|---|---|
| `XOF-XAF` | 1.0 | Les deux monnaies sont arrimées à l'euro au même taux fixe (655,957). Numériquement équivalentes, mais **deux devises distinctes** : l'étiquette envoyée au partenaire doit être la bonne. |

### Corridors fermés faute de taux

Tout corridor impliquant **GN (GNF)** ou **CD (CDF)** avec un pays d'une autre
zone est refusé, avec le motif « aucun taux déclaré pour XOF→GNF ».

Cela concerne notamment SN↔GN, SN↔CD, CI↔GN, CI↔CD, ML↔GN, ML↔CD, BF↔GN,
BJ↔CD, TG↔CD, NE↔CD, CM↔CD, CM↔GN.

> Ces corridors **s'exécutaient auparavant**, mais en envoyant au partenaire un
> montant étiqueté `XOF` pour un versement en GNF ou en CDF — des devises dont
> l'ordre de grandeur est respectivement quatorze fois et cinq fois différent.
> Les fermer est une correction, pas une régression. Ils rouvrent dès qu'un taux
> est fourni :
> ```yaml
> routing:
>   fx:
>     rates:
>       XOF-XAF: 1.0
>       XOF-GNF: 14.2   # à sourcer et à rafraîchir
>       XOF-CDF: 4.8
> ```

Les corridors internes restent ouverts : GN→GN et CD→CD ne traversent aucune
frontière monétaire.

---

## 3. Corridors par pays de destination

Nombre de passerelles capables de servir chaque pays. Un pays à **une seule**
passerelle n'a aucun repli : une panne partenaire y ferme le corridor.

| Pays | Actives aujourd'hui | Après activation PayDunya / Monetbil |
|---|---|---|
| Côte d'Ivoire | FeexPay, CinetPay — **2** | + PayDunya — **3** |
| Bénin | FeexPay, CinetPay — **2** | + PayDunya, Monetbil — **4** |
| Togo | FeexPay, CinetPay — **2** | + PayDunya — **3** |
| Sénégal | PayTech, CinetPay — **2** | + PayDunya, Monetbil — **4** |
| Mali | PayTech, CinetPay — **2** | + PayDunya — **3** |
| Burkina Faso | CinetPay — **1** ⚠ | + PayDunya — **2** |
| Niger | CinetPay — **1** ⚠ | + PayDunya — **2** |
| Cameroun | CinetPay — **1** ⚠ | + Monetbil — **2** |
| Guinée | CinetPay — **1** ⚠ | + Monetbil — **2** |
| RD Congo | CinetPay — **1** ⚠ | + Monetbil — **2** |
| Congo-Brazzaville | FeexPay — **1** ⚠ | + Monetbil — **2** |

⚠ Point de défaillance unique.

**L'activation des deux nouveaux partenaires supprime les six points de
défaillance unique du réseau.** C'est le principal apport opérationnel de cette
intégration, davantage que le gain tarifaire.

---

## 4. Le cas SN↔CG

Le corridor Sénégal ↔ Congo-Brazzaville mérite une note, parce que son histoire
illustre exactement le défaut que le contrôle de cohérence corrige.

- `V12` a créé la route SN↔CG via **CinetPay**, avec le commentaire
  « seul à couvrir les deux ». C'était faux : CinetPay ne dessert pas le Congo.
- `V15` a supprimé la route. Le corridor est donc **sans route directe**
  aujourd'hui, franchissable seulement par pont — lequel n'est pas exécutable
  (§ 6).
- **Monetbil couvre à la fois le Sénégal et le Congo-Brazzaville.** Son
  activation rend le corridor directement franchissable, sans pont.

Rien dans le code ne détectait l'erreur de `V12` : la table de routes était la
seule source de vérité et personne ne la confrontait aux capacités réelles des
partenaires. C'est maintenant vérifié à chaque démarrage.

---

## 5. Mettre à jour une couverture partenaire

La couverture d'un agrégateur change quand il ouvre un marché. Elle n'est plus
une constante compilée.

```yaml
gateway:
  capabilities:
    feexpay:
      payout-countries: BENIN,TOGO,COTE_DIVOIRE,CONGO_BRAZZAVILLE,SENEGAL
      collection-countries: BENIN,TOGO,COTE_DIVOIRE,CONGO_BRAZZAVILLE,SENEGAL
      currencies: XOF,XAF
      operators: MTN_BJ,MOOV_BJ,CELTIIS_BJ,ORANGE_SN,WAVE_SN,FREE_SN
```

Chaque champ est indépendant : ne redéfinir que `payout-countries` laisse les
opérateurs et les devises inchangés.

**Procédure complète pour ouvrir un marché :**

1. Déclarer la couverture en configuration (ci-dessus).
2. Insérer les routes correspondantes dans `gateway_routes`.
3. Redémarrer, ou appeler `POST /admin/routing/matrix/refresh`.
4. Vérifier `GET /admin/routing/consistency` — la réponse doit être vide
   d'erreurs. Une route vers un pays non déclaré, une devise non gérée ou un
   opérateur injoignable y apparaît immédiatement.

En production, positionner `routing.validation.fail-on-inconsistency=true` pour
que le démarrage échoue plutôt que de servir un graphe incohérent.

---

## 6. Routage par pont

Aucun pont n'est exécutable en l'état, quelle que soit la configuration des
routes. La raison n'est pas le calcul du chemin — qui fonctionne et se fait
désormais en mémoire — mais l'exécution multi-tronçons :

- le montant complet est envoyé sur **chaque** tronçon, sans déduction ;
- les comptes intermédiaires sont fabriqués (`préfixe pays + 00000001`) ;
- aucune compensation n'existe si un tronçon échoue après un précédent réussi.

Le moteur calcule donc les ponts, les affiche en prévisualisation et les expose
à l'administration, mais refuse de les exécuter tant que
`routing.bridge.transit-accounts` ne déclare pas de compte de transit réel pour
chaque pays traversé. Le motif est explicite dans la décision :
« pont CI → CG identifié mais non exécutable : aucun compte de transit configuré
pour CI ».

---

## 7. Plafonds par corridor

Les plafonds de `application-limits.yml` sont **maintenant appliqués**. Ils ne
l'étaient pas : le fichier n'était chargé par aucun profil, et le service
cherchait les corridors sous la clé `SENEGAL-SENEGAL` alors que la configuration
les indexe par code ISO (`SN-SN`).

Conséquence à connaître : un corridor absent de `application-limits.yml` retombe
sur le défaut, soit **100 000 FCFA par transaction** au lieu du maximum absolu
de 200 000. Les corridors à fort volume doivent donc y être déclarés
explicitement. Pour refuser les corridors non déclarés plutôt que de leur
appliquer un défaut permissif :

```yaml
limits:
  reject-unknown-corridors: true
```

---

## 8. Points ouverts

| Sujet | Décision attendue |
|---|---|
| Couverture FeexPay à jour | Liste des pays de versement, à confirmer auprès du partenaire |
| Barèmes PayDunya / Monetbil | Les routes sont seedées à 2,90 % / 3,40 % et 3,20 % / 3,80 % — valeurs provisoires |
| Taux GNF et CDF | Sans eux, six pays restent isolés de la zone XOF |
| Gabon, Liberia, Ouganda | Couverts par Monetbil, absents de l'énumération `Country`. Le Gabon est le candidat le plus naturel : zone CEMAC, même devise que CM et CG |
| Préfixes 90/91 au Bénin | Attribués à MTN dans la table Monetbil, à Celtiis dans notre catalogue. Laissés à Celtiis ; le contrôle de cohérence signale toute collision |
| Wave au Sénégal | Modélisé comme un réseau avec le préfixe 78, alors que c'est un portefeuille superposé. Non modifié : le corriger réacheminerait du trafic réel |
| Contrainte d'unicité sur `external_reference` | À poser après dédoublonnage des références historiques (8 caractères hexadécimaux, collisions possibles) |
