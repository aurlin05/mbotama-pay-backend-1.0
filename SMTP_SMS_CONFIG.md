# Configuration SMTP & SMS - MBOTAMAPAY Backend

## 📧 Configuration SMTP (Email)

### 1. Configuration Gmail

Pour utiliser Gmail comme serveur SMTP :

1. **Activer l'authentification à deux facteurs** sur votre compte Gmail
   - Allez sur https://myaccount.google.com/security
   - Activez la "Validation en deux étapes"

2. **Générer un mot de passe d'application**
   - Allez sur https://myaccount.google.com/apppasswords
   - Sélectionnez "Autre (nom personnalisé)"
   - Nommez-le "MbotamaPay Backend"
   - Copiez le mot de passe généré (16 caractères)

3. **Configurer les variables d'environnement**

```env
MAIL_HOST=smtp.gmail.com
MAIL_PORT=587
MAIL_USERNAME=votre-email@gmail.com
MAIL_PASSWORD=xxxx-xxxx-xxxx-xxxx  # App password généré
```

### 2. Autres fournisseurs SMTP

#### SendGrid
```env
MAIL_HOST=smtp.sendgrid.net
MAIL_PORT=587
MAIL_USERNAME=apikey
MAIL_PASSWORD=votre-api-key-sendgrid
```

#### Brevo (Sendinblue) - Recommandé
```env
EMAIL_PROVIDER=brevo
BREVO_API_KEY=votre-api-key-brevo
```

---

## 📱 Configuration SMS

Le service SMS est actuellement en mode développement (logs uniquement).

### Option 1 : Orange SMS API (Afrique de l'Ouest)

Pour le Sénégal et l'Afrique de l'Ouest, Orange propose une API SMS locale.

1. **S'inscrire sur** https://developer.orange.com
2. **Créer une application** et obtenir :
   - Client ID
   - Client Secret

3. **Ajouter au application.yml** :

```yaml
sms:
  enabled: true
  provider: orange
  orange:
    api-url: https://api.orange.com/smsmessaging/v1
    client-id: ${SMS_ORANGE_CLIENT_ID}
    client-secret: ${SMS_ORANGE_CLIENT_SECRET}
    sender-name: MbotamaPay
```

---

### Option 2 : Infobip (International - Afrique)

Infobip a une bonne couverture en Afrique.

1. **S'inscrire sur** https://www.infobip.com
2. **Obtenir votre API Key**

```yaml
sms:
  enabled: true
  provider: infobip
  infobip:
    api-url: https://api.infobip.com
    api-key: ${SMS_INFOBIP_API_KEY}
    sender-id: MbotamaPay
```

---

### Option 3 : Africa's Talking (Afrique)

Spécialisé pour l'Afrique avec une bonne couverture.

1. **S'inscrire sur** https://africastalking.com
2. **Obtenir vos credentials**

```yaml
sms:
  enabled: true
  provider: africastalking
  africastalking:
    username: ${SMS_AT_USERNAME}
    api-key: ${SMS_AT_API_KEY}
    sender-id: MbotamaPay
```

---

## 🚀 Démarrage rapide

1. **Copiez le fichier d'exemple** :
```bash
cp .env.example .env
```

2. **Éditez `.env`** avec vos vraies informations

3. **Redémarrez l'application** :
```bash
./gradlew bootRun
```

---

## 🔍 Debugging

### Mode développement
En développement, les SMS sont loggés dans la console :
```
📱 SMS to +221771234567
📝 Message: Votre code MbotamaPay est: 123456
```

C'est parfait pour tester sans envoyer de vrais SMS.

---

## ⚠️ Important

- **Ne committez jamais** le fichier `.env` dans Git
- Ajoutez `.env` dans `.gitignore`
- Utilisez des variables d'environnement en production
- Respectez les limites d'envoi de votre fournisseur
- Vérifiez les coûts d'envoi SMS avant de passer en production

---

## 📞 Support

Pour toute question sur la configuration :
- Email : support@mbotamapay.com
