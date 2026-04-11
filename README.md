# 📈 IPO GMP Tracker

A **production-ready**, real-time IPO Grey Market Premium (GMP) tracking web application built with Spring Boot, MongoDB, Thymeleaf, and WebSockets.

![Java](https://img.shields.io/badge/Java-17-orange)
![Spring Boot](https://img.shields.io/badge/Spring%20Boot-3.2.5-green)
![MongoDB](https://img.shields.io/badge/MongoDB-7.0-brightgreen)
![License](https://img.shields.io/badge/License-MIT-blue)

---

## ✨ Features

| Feature | Details |
|---------|---------|
| 📊 Live Dashboard | Real-time GMP table with colour-coded flash animations |
| ⚡ WebSocket Updates | STOMP over SockJS — pushes every 45 seconds |
| 🔐 Admin Panel | Secure CRUD with Spring Security (Basic Auth + Form Login) |
| 🔌 REST API | Full CRUD — `GET / POST / PUT / PATCH / DELETE /api/ipos` |
| 📱 Responsive | Bootstrap 5 dark theme, mobile-first |
| 🐳 Docker-ready | Multi-stage Dockerfile + Docker Compose included |
| ☁️ Free hosting | Deploy on Render + MongoDB Atlas (both free tiers) |

---

## 🏗️ Architecture

```
┌─────────────────────────────────────────────────────────────────┐
│                        Browser (Client)                         │
│  Thymeleaf HTML + Bootstrap 5                                   │
│  WebSocket (STOMP/SockJS)  ←→  REST API calls                  │
└──────────────────┬──────────────────────────────────────────────┘
                   │ HTTP / WebSocket
┌──────────────────▼──────────────────────────────────────────────┐
│                   Spring Boot Application                        │
│  ┌─────────────┐  ┌─────────────┐  ┌──────────────────────┐   │
│  │ WebController│  │IpoApiCtrl   │  │ WebSocketConfig       │   │
│  │  (MVC pages)│  │ (REST CRUD) │  │ STOMP /ws endpoint    │   │
│  └──────┬──────┘  └──────┬──────┘  └──────────────────────┘   │
│         └────────────┬───┘                                      │
│                ┌─────▼─────┐                                    │
│                │ IpoService │ ← broadcasts via SimpMessaging    │
│                └─────┬─────┘                                    │
│                      │           ┌─────────────────┐            │
│                ┌─────▼─────┐    │  GmpScheduler   │            │
│                │IpoRepository    │  (every 45s)    │            │
│                │(Spring Data)    └────────┬────────┘            │
│                └─────┬─────┘             │                      │
│                      │            ┌──────▼──────┐               │
└──────────────────────│────────────│GmpDataService│──────────────┘
                        │            └─────────────┘
                ┌───────▼───────┐
                │    MongoDB    │
                │  (Atlas/local)│
                └───────────────┘
```

---

## 📁 Project Structure

```
ipo-gmp-tracker/
├── src/main/java/com/ipogmp/tracker/
│   ├── IpoGmpTrackerApplication.java   ← Entry point
│   ├── config/
│   │   ├── SecurityConfig.java          ← Spring Security
│   │   └── WebSocketConfig.java         ← STOMP/SockJS
│   ├── controller/
│   │   ├── IpoApiController.java        ← REST API
│   │   └── WebController.java           ← Thymeleaf pages
│   ├── dto/
│   │   ├── IpoDTO.java                  ← API contract
│   │   └── ApiResponse.java             ← Response envelope
│   ├── exception/
│   │   ├── GlobalExceptionHandler.java  ← Centralised errors
│   │   ├── IpoNotFoundException.java
│   │   └── DuplicateIpoException.java
│   ├── model/
│   │   └── Ipo.java                     ← MongoDB document
│   ├── repository/
│   │   └── IpoRepository.java           ← Spring Data Mongo
│   ├── scheduler/
│   │   └── GmpScheduler.java            ← @Scheduled refresh
│   └── service/
│       ├── IpoService.java              ← Business logic + WS broadcast
│       ├── GmpDataService.java          ← GMP fetch (mock/pluggable)
│       └── DataInitializer.java         ← DB seeder
├── src/main/resources/
│   ├── templates/
│   │   ├── index.html                   ← Dashboard
│   │   ├── admin.html                   ← Admin panel
│   │   └── login.html                   ← Login page
│   ├── static/
│   │   ├── css/style.css
│   │   └── js/
│   │       ├── app.js                   ← Dashboard WS + rendering
│   │       └── admin.js                 ← CRUD operations
│   └── application.properties
├── Dockerfile
├── docker-compose.yml
├── render.yaml
└── pom.xml
```

---

## 🚀 Running Locally

### Prerequisites
- Java 17+
- Maven 3.9+
- MongoDB (local or Atlas)

### Option A — With Docker Compose (Recommended)

```bash
# Clone and start everything (App + MongoDB)
git clone <your-repo>
cd ipo-gmp-tracker

docker-compose up --build
```
Open: http://localhost:8080

### Option B — Maven + Local MongoDB

```bash
# 1. Start MongoDB locally
mongod --dbpath /data/db

# 2. Run the app
mvn spring-boot:run

# Or build JAR first
mvn clean package -DskipTests
java -jar target/ipo-gmp-tracker-1.0.0.jar
```

### Environment Variables

| Variable | Default | Description |
|----------|---------|-------------|
| `PORT` | `8080` | Server port |
| `MONGO_URI` | `mongodb://localhost:27017/ipo_gmp_tracker` | MongoDB connection string |
| `ADMIN_USERNAME` | `admin` | Admin login username |
| `ADMIN_PASSWORD` | `admin123` | Admin login password |
| `GMP_REFRESH_INTERVAL_MS` | `45000` | GMP auto-refresh interval (ms) |

---

## 🔌 REST API Reference

Base URL: `http://localhost:8080/api`

| Method | Endpoint | Auth | Description |
|--------|----------|------|-------------|
| `GET` | `/api/ipos` | Public | List all IPOs (sorted by GMP desc) |
| `GET` | `/api/ipos?status=OPEN` | Public | Filter by status |
| `GET` | `/api/ipos/active` | Public | OPEN + UPCOMING only |
| `GET` | `/api/ipos/{id}` | Public | Get single IPO |
| `POST` | `/api/ipos` | Admin | Create new IPO |
| `PUT` | `/api/ipos/{id}` | Admin | Update IPO |
| `PATCH` | `/api/ipos/{id}/gmp?value=150` | Admin | Update GMP only |
| `DELETE` | `/api/ipos/{id}` | Admin | Delete IPO |

### Example: Create IPO

```bash
curl -X POST http://localhost:8080/api/ipos \
  -H "Content-Type: application/json" \
  -u admin:admin123 \
  -d '{
    "name": "Example IPO Ltd",
    "issuePrice": 500,
    "gmp": 75,
    "kostakRate": 800,
    "subjectToSauda": 400,
    "lotSize": 30,
    "status": "OPEN"
  }'
```

### WebSocket

Connect to `ws://localhost:8080/ws` (SockJS fallback)
Subscribe to `/topic/ipos`

Event payload:
```json
{
  "event": "GMP_UPDATED",
  "data": {
    "id": "...",
    "name": "Bajaj Housing Finance",
    "gmp": 98.50,
    "gmpTrend": "UP",
    "expectedListingPrice": 798.50,
    ...
  }
}
```

Events: `ALL_IPOS` | `IPO_CREATED` | `IPO_UPDATED` | `GMP_UPDATED` | `IPO_DELETED`

---

## ☁️ Free Deployment Guide

### Step 1 — MongoDB Atlas (Free Tier)

1. Go to [mongodb.com/atlas](https://www.mongodb.com/atlas)
2. Create a free account → **Create Free Cluster** (M0 tier)
3. Create a database user (username + password)
4. Under **Network Access** → Add `0.0.0.0/0` (allow all IPs)
5. Click **Connect → Drivers** → Copy the URI:
   ```
   mongodb+srv://<user>:<password>@cluster0.xxxxx.mongodb.net/ipo_gmp_tracker
   ```

### Step 2 — Deploy on Render (Free Tier)

1. Push your code to GitHub
2. Go to [render.com](https://render.com) → **New Web Service**
3. Connect your GitHub repo
4. Render auto-detects `render.yaml`
5. Set environment variables in the Render dashboard:
   - `MONGO_URI` → your Atlas URI from Step 1
   - `ADMIN_PASSWORD` → choose a strong password
6. Click **Deploy** — your app will be live in ~3-5 minutes

> **Free tier note**: Render free instances sleep after 15 min of inactivity.
> First request after sleep takes ~30s. Upgrade to Starter ($7/mo) to avoid cold starts.

### Step 3 — Optional: Railway Deployment

```bash
# Install Railway CLI
npm install -g @railway/cli

railway login
railway init
railway up
railway variables set MONGO_URI="your-atlas-uri"
railway variables set ADMIN_PASSWORD="strongpassword"
```

---

## 🔧 Customisation Guide

### Plug in Real GMP Data

Edit `GmpDataService.java` → replace `fetchLiveGmpData()`:

```java
// Option A: Jsoup web scraping
Document doc = Jsoup.connect("https://www.ipowatch.in/ipo-gmp/")
    .userAgent("Mozilla/5.0").get();
// parse relevant elements...

// Option B: RapidAPI
HttpClient client = HttpClient.newHttpClient();
HttpRequest req = HttpRequest.newBuilder()
    .uri(URI.create("https://ipo-api.p.rapidapi.com/gmp"))
    .header("X-RapidAPI-Key", apiKey)
    .build();
```

### Change Refresh Interval

In `application.properties`:
```properties
app.gmp.refresh-interval-ms=30000   # 30 seconds
```
Or via environment variable: `GMP_REFRESH_INTERVAL_MS=30000`

### Add More Admin Users

In `SecurityConfig.java`, add more `UserDetails` to `InMemoryUserDetailsManager`.
For production, replace with a database-backed `UserDetailsService`.

---

## 🏷️ MongoDB Schema

Collection: `ipos`

```json
{
  "_id":             "ObjectId",
  "name":            "Bajaj Housing Finance",
  "gmp":             95.0,
  "previous_gmp":    90.0,
  "kostak_rate":     800.0,
  "subject_to_sauda":400.0,
  "issue_price":     700.0,
  "lot_size":        700,
  "issue_size":      6560.0,
  "registrar":       "KFin Technologies",
  "status":          "OPEN",
  "open_date":       "ISODate",
  "close_date":      "ISODate",
  "listing_date":    "ISODate",
  "last_updated":    "ISODate"
}
```

---

## 🛡️ Security Notes

- Change default admin credentials before deploying
- Store secrets in environment variables, never in code
- For production: replace `InMemoryUserDetailsManager` with DB-backed auth
- Enable HTTPS via your hosting provider (Render provides it free)
- Consider rate-limiting the REST API for public endpoints

---

## 📜 License

MIT License — free to use, modify, and distribute.
