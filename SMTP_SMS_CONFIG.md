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

Créez un fichier `.env` à la racine du projet backend :

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

#### Mailgun
```env
MAIL_HOST=smtp.mailgun.org
MAIL_PORT=587
MAIL_USERNAME=postmaster@votre-domaine.mailgun.org
MAIL_PASSWORD=votre-password-mailgun
```

#### Office365/Outlook
```env
MAIL_HOST=smtp.office365.com
MAIL_PORT=587
MAIL_USERNAME=votre-email@outlook.com
MAIL_PASSWORD=votre-mot-de-passe
```

---

## 📱 Configuration SMS

Le service SMS est actuellement en mode développement (logs uniquement).

### Option 1 : Twilio (Recommandé - International)

1. **Créer un compte sur** https://www.twilio.com
2. **Obtenir vos credentials** :
   - Account SID
   - Auth Token
   - Numéro de téléphone Twilio

3. **Ajouter la dépendance** dans `pom.xml` :

```xml
<dependency>
    <groupId>com.twilio.sdk</groupId>
    <artifactId>twilio</artifactId>
    <version>9.14.0</version>
</dependency>
```

4. **Mettre à jour SmsService.java** :

```java
@Value("${sms.twilio.account-sid}")
private String twilioAccountSid;

@Value("${sms.twilio.auth-token}")
private String twilioAuthToken;

@Value("${sms.twilio.phone-number}")
private String twilioPhoneNumber;

@PostConstruct
public void init() {
    Twilio.init(twilioAccountSid, twilioAuthToken);
}

private void doSendSms(String phoneNumber, String message) {
    try {
        Message.creator(
            new PhoneNumber(phoneNumber),
            new PhoneNumber(twilioPhoneNumber),
            message
        ).create();
        
        log.info("✅ SMS sent successfully to {}", phoneNumber);
    } catch (Exception e) {
        log.error("❌ Failed to send SMS to {}: {}", phoneNumber, e.getMessage());
    }
}
```

5. **Variables d'environnement** :

```env
SMS_TWILIO_ACCOUNT_SID=ACxxxxxxxxxxxxx
SMS_TWILIO_AUTH_TOKEN=votre-auth-token
SMS_TWILIO_PHONE_NUMBER=+1234567890
```

---

### Option 2 : Orange SMS API (Sénégal/Afrique)

Pour le Sénégal, Orange propose une API SMS locale.

1. **S'inscrire sur** https://developer.orange.com
2. **Créer une application** et obtenir :
   - Client ID
   - Client Secret

3. **Ajouter au application.yml** :

```yaml
sms:
  orange:
    api-url: https://api.orange.com/smsmessaging/v1
    client-id: ${SMS_ORANGE_CLIENT_ID}
    client-secret: ${SMS_ORANGE_CLIENT_SECRET}
    sender-name: MbotamaPay
```

4. **Implémenter dans SmsService.java** :

```java
@Value("${sms.orange.api-url}")
private String orangeApiUrl;

@Value("${sms.orange.client-id}")
private String orangeClientId;

@Value("${sms.orange.client-secret}")
private String orangeClientSecret;

@Autowired
private RestTemplate restTemplate;

private String getOrangeAccessToken() {
    // Implémentation OAuth2 pour obtenir le token
    // ...
}

private void doSendSms(String phoneNumber, String message) {
    String accessToken = getOrangeAccessToken();
    
    HttpHeaders headers = new HttpHeaders();
    headers.setBearerAuth(accessToken);
    headers.setContentType(MediaType.APPLICATION_JSON);
    
    Map<String, Object> body = Map.of(
        "outboundSMSMessageRequest", Map.of(
            "address", List.of("tel:" + phoneNumber),
            "senderAddress", "tel:+221123456789",
            "outboundSMSTextMessage", Map.of("message", message)
        )
    );
    
    // Envoyer la requête...
}
```

---

### Option 3 : InTouch (Sénégal)

Service SMS local sénégalais.

```env
SMS_INTOUCH_API_URL=https://api.intouchsms.sn/v1
SMS_INTOUCH_API_KEY=votre-api-key
SMS_INTOUCH_SENDER_ID=MbotamaPay
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

4. **Testez l'envoi d'email/SMS** en vous inscrivant sur l'application

---

## 🔍 Debugging

### Vérifier les logs
```bash
tail -f logs/application.log
```

### Test SMTP
Vous pouvez tester la connexion SMTP avec :
```yaml
spring:
  mail:
    test-connection: true  # Activez dans application.yml
```

### Mode développement
En développement, les SMS sont loggés dans la console. C'est parfait pour tester sans envoyer de vrais SMS.

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
- Documentation : https://docs.mbotamapay.com
