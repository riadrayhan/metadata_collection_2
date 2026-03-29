# Android Data Collector — Complete Setup Guide

---

## Project Structure

```
metadata_collection/
├── app/                              ← Android app source files
│   ├── AndroidManifest.xml
│   ├── java/com/datacollector/
│   │   ├── MainActivity.java         ← Entry point, permission handler
│   │   ├── DatabaseHelper.java       ← SQLite local storage
│   │   ├── CallLogCollector.java     ← Reads call logs
│   │   ├── SmsCollector.java         ← Reads SMS inbox/sent
│   │   ├── LocationCollector.java    ← Gets GPS location
│   │   ├── SimChangeDetector.java    ← Detects SIM swap via ICCID
│   │   └── DataSyncManager.java      ← Sends data to Netlify server
│   └── res/layout/
│       └── activity_main.xml
│
└── backend/                          ← Netlify deployment
    ├── netlify.toml                  ← Netlify config (routes, functions)
    ├── package.json
    ├── server.js                     ← Local dev server (optional)
    ├── functions/                    ← Netlify serverless functions
    │   ├── lib/db.js                 ← Shared MongoDB + helpers
    │   ├── collect.js                ← POST /api/collect
    │   ├── summary.js                ← GET /api/summary
    │   ├── call-logs.js              ← GET /api/data/call_logs
    │   ├── sms.js                    ← GET /api/data/sms
    │   ├── location.js               ← GET /api/data/location
    │   └── sim-history.js            ← GET /api/data/sim_history
    └── public/
        └── index.html                ← Admin Panel dashboard
```

---

## 1. MongoDB Atlas Setup (Free Cloud Database)

1. Go to [https://cloud.mongodb.com](https://cloud.mongodb.com) and create a free account
2. Create a **Free Shared Cluster** (M0 — free forever)
3. Go to **Database Access** → Add a database user (username + password)
4. Go to **Network Access** → Add IP Address → **Allow Access from Anywhere** (`0.0.0.0/0`)
5. Go to **Clusters** → **Connect** → **Connect your application**
6. Copy the connection string, it looks like:
   ```
   mongodb+srv://USERNAME:PASSWORD@cluster0.xxxxx.mongodb.net/datacollector?retryWrites=true&w=majority
   ```

---

## 2. Deploy Backend to Netlify (Live URL)

### Option A: Deploy via Netlify CLI

```bash
# Install Netlify CLI globally
npm install -g netlify-cli

# Go to backend folder
cd backend

# Install dependencies
npm install

# Login to Netlify
netlify login

# Create and deploy site
netlify init
# Choose: "Create & configure a new site"
# Team: your team
# Site name: your-site-name (e.g. datacollector-admin)

# Set environment variables
netlify env:set MONGODB_URI "mongodb+srv://USER:PASS@cluster0.xxxxx.mongodb.net/datacollector?retryWrites=true&w=majority"
netlify env:set API_KEY "your-secret-admin-key-here"

# Deploy
netlify deploy --prod
```

### Option B: Deploy via Netlify Dashboard

1. Push `backend/` folder to a **GitHub repo**
2. Go to [https://app.netlify.com](https://app.netlify.com) → **Add new site** → **Import from Git**
3. Select your repo, set:
   - **Base directory:** `backend`
   - **Build command:** _(leave empty)_
   - **Publish directory:** `backend/public`
   - **Functions directory:** `backend/functions`
4. Go to **Site settings** → **Environment variables** → Add:
   - `MONGODB_URI` = your MongoDB Atlas connection string
   - `API_KEY` = any strong secret key (e.g. `mY$ecretKey2024!`)
5. **Deploy** — your site will be live at `https://your-site-name.netlify.app`

### After Deploy

Your live URLs will be:
- **Admin Panel:** `https://your-site-name.netlify.app`
- **API Collect:** `https://your-site-name.netlify.app/api/collect`
- **API Summary:** `https://your-site-name.netlify.app/api/summary`

---

## 3. Android App Setup

### 3.1 Create new Android Studio project
- **Language:** Java
- **Min SDK:** API 26 (Android 8.0)
- **Template:** Empty Activity

### 3.2 Copy Java files
Place all `.java` files in:
```
app/src/main/java/com/datacollector/
```

Copy the layout file:
```
app/src/main/res/layout/activity_main.xml
```

### 3.3 Update `DataSyncManager.java`
Change these two lines to your actual Netlify URL and API key:
```java
private static final String SERVER_URL = "https://your-site-name.netlify.app/api/collect";
private static final String API_KEY = "your-secret-admin-key-here";
```

### 3.4 Add to `build.gradle` (app level)
```gradle
dependencies {
    implementation 'androidx.appcompat:appcompat:1.6.1'
    implementation 'com.google.android.gms:play-services-location:21.0.1'
    implementation 'com.google.android.material:material:1.11.0'
}
```

### 3.5 Build & Run
- Sync Gradle → Build → Run on device
- Press **Collect Data** → collects call logs, SMS, location, SIM info
- Press **Sync to Server** → uploads to your Netlify backend

---

## 4. Using the Admin Panel

1. Open your Netlify URL in a browser: `https://your-site-name.netlify.app`
2. Enter the **API_KEY** you set in Netlify environment variables
3. View all collected data:
   - **Dashboard** — summary cards + recent data
   - **Call Logs** — all call history with type, duration, date
   - **SMS** — all messages with sender/receiver, body
   - **Location** — GPS coordinates on an interactive map
   - **SIM History** — SIM card changes with old/new ICCID
4. Filter by device using the sidebar dropdown

---

## API Endpoints

| Method | URL | Description |
|--------|-----|-------------|
| POST | `/api/collect` | Receive data from Android app |
| GET | `/api/summary` | Total counts + device list |
| GET | `/api/data/call_logs` | All call logs |
| GET | `/api/data/sms` | All SMS |
| GET | `/api/data/location` | All locations |
| GET | `/api/data/sim_history` | SIM swap history |

### Query Parameters (all GET endpoints)
- `?device_id=xxx` — filter by device
- `?limit=100` — limit results

### Headers
- `x-api-key: YOUR_API_KEY` — required for all requests

---

## Permissions Required

| Permission | Purpose |
|-----------|---------|
| `READ_CALL_LOG` | Read call history |
| `READ_SMS` | Read SMS messages |
| `ACCESS_FINE_LOCATION` | GPS location |
| `READ_PHONE_STATE` | SIM serial (ICCID) |
| `INTERNET` | Send data to server |

All permissions are requested at runtime with 100% user consent.

---

## What Data is Collected

| Data Type | Fields |
|-----------|--------|
| Call Logs | Phone number, call type (incoming/outgoing/missed/rejected), date, duration |
| SMS | Address (sender/receiver), message body, type (sent/received), date |
| Location | Latitude, longitude, accuracy, timestamp |
| SIM History | Old ICCID, new ICCID, carrier name, phone number, timestamp |

---

## Notes

- **SIM change detection**: Compares current ICCID with last saved in SharedPreferences. Checked every app launch.
- **Data stored locally first**: SQLite DB → synced on button press to MongoDB via Netlify functions.
- **Android 10+**: `getSimSerialNumber()` may require carrier privileges.
- **Android 11+**: `READ_CALL_LOG` is a sensitive permission, may need Google Play approval for public apps.
- **Netlify Functions**: 10-second timeout on free plan. MongoDB connections are cached across warm invocations.
- **Security**: All endpoints require `x-api-key` header. Set `API_KEY` in Netlify environment variables.
