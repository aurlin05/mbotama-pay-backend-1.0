# MbotamaPay Backend

Backend API pour MbotamaPay - Plateforme de transfert d'argent mobile pour l'Afrique de l'Ouest.

## 🛠️ Technologies

- **Java 21**
- **Spring Boot 3.2**
- **Gradle 8.5**
- **Spring Security** avec JWT
- **Spring Data JPA** avec PostgreSQL/H2
- **Flyway** pour les migrations
- **Swagger/OpenAPI** pour la documentation

## 📁 Structure du Projet

```
src/main/java/com/mbotamapay/
├── config/           # Configuration (Security, JWT, CORS)
├── controller/       # API Controllers
├── dto/              # Data Transfer Objects
├── entity/           # JPA Entities
├── exception/        # Exception Handling
├── repository/       # JPA Repositories
└── service/          # Business Logic
```

## 🚀 Démarrage Rapide

### Prérequis
- Java 21+
- Gradle 8.5+ (ou utiliser le wrapper)

### Lancer l'application

```bash
# Avec le wrapper Gradle (recommandé)
./gradlew bootRun

# Windows
gradlew.bat bootRun

# Production (PostgreSQL)
./gradlew bootRun --args='--spring.profiles.active=prod'
```

L'API sera accessible sur `http://localhost:8080/api/v1`

### Build

```bash
# Build le JAR
./gradlew build

# Le JAR sera dans build/libs/mbotamapay-backend.jar
java -jar build/libs/mbotamapay-backend.jar
```

## 📚 Documentation API

Une fois l'app lancée :
- **Swagger UI**: http://localhost:8080/api/v1/swagger-ui.html
- **OpenAPI JSON**: http://localhost:8080/api/v1/api-docs

## 🔐 Endpoints d'Authentification

| Méthode | Endpoint | Description |
|---------|----------|-------------|
| POST | `/auth/register` | Inscription (envoie OTP) |
| POST | `/auth/login` | Connexion (envoie OTP) |
| POST | `/auth/verify-otp` | Vérification OTP → JWT |
| POST | `/auth/resend-otp` | Renvoyer OTP |
| POST | `/auth/refresh-token` | Rafraîchir token |

## 🪪 Niveaux KYC

| Niveau | Limite Transaction | Description |
|--------|-------------------|-------------|
| NONE | 0 FCFA | Lecture seule |
| LEVEL_1 | 500 000 FCFA/mois | Selfie + CNI |
| LEVEL_2 | Illimité | Documents additionnels |

## ⚙️ Configuration

Variables d'environnement importantes :

```bash
# Base de données PostgreSQL (prod)
DATABASE_URL=jdbc:postgresql://localhost:5432/mbotamapay
DATABASE_USERNAME=postgres
DATABASE_PASSWORD=password

# JWT
JWT_SECRET=your-256-bit-secret-key

# SMS Provider (TODO)
SMS_PROVIDER_API_KEY=xxx
```

## 🧪 Tests

```bash
./gradlew test
```

## 📱 Code OTP de Test

En développement, le code OTP est affiché dans les logs :
```
========================================
📱 SMS OTP for +221771234567: 123456
========================================
```
