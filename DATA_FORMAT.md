# Data Format Documentation

## Envelope Format (All Requests)

Every sync request is a **POST** to `/api/collect`:

```json
{
  "type": "<table_name>",
  "data": [ { ...records... } ],
  "device_id": "<android_id>"
}
```

**Valid `type` values:** `call_logs`, `sms`, `location`, `sim_history`, `mobile_money`, `telecom_usage`, `ride_hailing`, `device_info`, `location_dwell`, `behavior_scores`, `installed_apps`

The backend auto-adds to each record:
- `_id` — UUID (`crypto.randomUUID()`)
- `device_id` — copied from envelope
- `createdAt` — ISO 8601 timestamp

---

## Data Formats Per Type

### 1. `call_logs`

| Field | Type | Example |
|-------|------|---------|
| `id` | string | `"1"` |
| `number` | string | `"+8801712345678"` |
| `type` | string | `"INCOMING"` / `"OUTGOING"` / `"MISSED"` / `"REJECTED"` / `"UNKNOWN"` |
| `date` | string | `"1711382400000"` (epoch ms) |
| `duration` | string | `"120"` (seconds) |
| `synced` | string | `"0"` |

---

### 2. `sms`

| Field | Type | Example |
|-------|------|---------|
| `id` | string | `"1"` |
| `address` | string | `"bKash"` / `"+8801812345678"` |
| `body` | string | `"Your bKash Cash In..."` |
| `date` | string | `"1711382400000"` (epoch ms) |
| `type` | string | `"RECEIVED"` / `"SENT"` / `"DRAFT"` / `"OTHER"` |
| `synced` | string | `"0"` |

---

### 3. `location`

| Field | Type | Example |
|-------|------|---------|
| `id` | string | `"1"` |
| `latitude` | string | `"23.8103"` |
| `longitude` | string | `"90.4125"` |
| `accuracy` | string | `"15.5"` (meters) |
| `timestamp` | string | `"2026-03-25 14:30:00"` |
| `address` | string | `"Dhanmondi, Dhaka, Dhaka Division"` |
| `synced` | string | `"0"` |

**ADB script adds extra fields:**

| Field | Type | Example |
|-------|------|---------|
| `source` | string | `"IP_GEOLOCATION"` / `"CELL_TOWER"` / `"WIFI"` |
| `isp` | string | `"Grameenphone Ltd."` (IP only) |
| `cell_towers` | int | `3` (Cell only) |
| `wifi_ssid` | string | `"HomeWiFi"` (WiFi only) |
| `wifi_bssid` | string | `"aa:bb:cc:dd:ee:ff"` (WiFi only) |

---

### 4. `sim_history`

| Field | Type | Example |
|-------|------|---------|
| `id` | string | `"1"` |
| `old_iccid` | string | `"INITIAL"` / `"8988021..."` |
| `new_iccid` | string | `"8988021..."` |
| `phone_number` | string | `"01712345678"` |
| `carrier` | string | `"Grameenphone"` |
| `timestamp` | string | `"2026-03-25 14:30:00"` |
| `synced` | string | `"0"` |

**ADB script adds extra fields:**

| Field | Type | Example |
|-------|------|---------|
| `sim_slot` | string | `"0"` / `"1"` |
| `sim_state` | string | `"READY"` / `"LOADED"` / `"UNKNOWN"` / `"NOT_READY"` |
| `mcc` | string | `"470"` |
| `mnc` | string | `"01"` |
| `country` | string | `"bd"` |
| `imsi` | string | `"470011234567890"` |
| `event_type` | string | `"CURRENT_SIM"` / `"SIM_REMOVED"` / `"SIM_STATE_CHANGE"` |

---

### 5. `mobile_money`

| Field | Type | Example |
|-------|------|---------|
| `id` | string | `"1"` |
| `provider` | string | `"bKash"` / `"Nagad"` |
| `txn_type` | string | `"CASH_IN"` / `"CASH_OUT"` / `"SEND_MONEY"` / `"RECEIVE_MONEY"` / `"PAYMENT"` / `"BILL_PAY"` / `"MERCHANT_PAYMENT"` / `"MOBILE_RECHARGE"` / `"ADD_MONEY"` / `"WITHDRAW"` / `"SALARY"` / `"REMITTANCE"` / `"OTHER"` |
| `amount` | string | `"500.00"` |
| `balance` | string | `"1200.50"` |
| `txn_id` | string | `"ABC123XYZ"` |
| `counter_party` | string | `"01712345678"` |
| `sender` | string | `"bKash"` / `"16247"` |
| `raw_sms` | string | `"Your bKash Cash In of Tk 500..."` |
| `timestamp` | string | `"2026-03-25 14:30:00"` |
| `synced` | string | `"0"` |

---

### 6. `telecom_usage`

| Field | Type | Example |
|-------|------|---------|
| `id` | string | `"1"` |
| `operator` | string | `"Grameenphone"` / `"Robi"` / `"Banglalink"` / `"Airtel"` / `"Teletalk"` |
| `recharge_type` | string | `"RECHARGE"` / `"BUNDLE_PURCHASE"` / `"BONUS"` / `"EXPIRY_NOTICE"` / `"BALANCE_INFO"` / `"OTHER"` |
| `amount` | string | `"100"` |
| `balance` | string | `"250.50"` |
| `sender` | string | `"GP"` / `"16800"` |
| `raw_sms` | string | `"Recharge of Tk 100 successful..."` |
| `timestamp` | string | `"2026-03-25 14:30:00"` |
| `synced` | string | `"0"` |

---

### 7. `ride_hailing`

| Field | Type | Example |
|-------|------|---------|
| `id` | string | `"1"` |
| `provider` | string | `"Uber"` / `"Pathao"` |
| `ride_type` | string | `"TRIP_COMPLETED"` / `"CANCELLED"` / `"PROMO"` / `"VERIFICATION"` / `"DELIVERY"` / `"OTHER"` |
| `amount` | string | `"350"` |
| `trip_details` | string | `"Your trip with Uber..."` (max 200 chars) |
| `sender` | string | `"Uber"` / `"Pathao"` |
| `timestamp` | string | `"2026-03-25 14:30:00"` |
| `synced` | string | `"0"` |

---

### 8. `device_info`

| Field | Type | Example |
|-------|------|---------|
| `id` | string | `"1"` |
| `device_id` | string | `"abc123def456"` |
| `brand` | string | `"samsung"` |
| `model` | string | `"SM-A325F"` |
| `manufacturer` | string | `"samsung"` |
| `device` | string | `"a32"` |
| `hardware` | string | `"mt6769v"` |
| `os_version` | string | `"13"` |
| `api_level` | string | `"33"` |
| `security_patch` | string | `"2025-12-01"` |
| `build_fingerprint` | string | `"samsung/a32/..."` |
| `first_install_time` | string | `"1680000000000"` |
| `uptime_days` | string | `"5"` |
| `is_rooted` | string | `"YES"` / `"NO"` |
| `sim_swap_count` | string | `"2"` |
| `factory_reset_indicator` | string | `"0"` / `"-1"` |
| `screen_info` | string | `"1080x2400 420dpi"` |
| `ram_info` | string | `"4096MB total, 1200MB available"` |
| `storage_info` | string | `"64GB total, 32GB free"` |
| `battery_info` | string | `"85%, charging"` |
| `network_type` | string | `"4G/LTE"` / `"3G"` / `"2G"` / `"5G"` |
| `timezone` | string | `"Asia/Dhaka"` |
| `language` | string | `"en"` / `"bn"` |
| `country` | string | `"BD"` |
| `timestamp` | string | `"2026-03-25 14:30:00"` |
| `synced` | string | `"0"` |

---

### 9. `location_dwell`

| Field | Type | Example |
|-------|------|---------|
| `id` | string | `"1"` |
| `latitude` | string | `"23.8103"` |
| `longitude` | string | `"90.4125"` |
| `accuracy` | string | `"15.5"` |
| `address` | string | `"Dhanmondi, Dhaka"` |
| `timestamp` | string | `"2026-03-25 14:30:00"` |
| `dwell_minutes` | string | `"45"` |
| `location_type` | string | `"NIGHT_HOME"` / `"WORK_HOURS"` / `"WEEKEND"` / `"MORNING_COMMUTE"` / `"EVENING"` / `"OTHER"` |
| `visit_count` | string | `"5"` |
| `event_type` | string | `"ARRIVAL"` / `"DWELL"` |
| `synced` | string | `"0"` |

---

### 10. `behavior_scores`

| Field | Type | Example |
|-------|------|---------|
| `id` | string | `"1"` |
| `total_calls` | string | `"523"` |
| `incoming_calls` | string | `"210"` |
| `outgoing_calls` | string | `"250"` |
| `missed_calls` | string | `"63"` |
| `total_duration` | string | `"86400"` (seconds) |
| `night_calls` | string | `"25"` |
| `weekend_calls` | string | `"80"` |
| `unique_call_contacts` | string | `"45"` |
| `call_regularity` | string | `"0.65"` |
| `in_out_ratio` | string | `"0.84"` |
| `night_ratio` | string | `"0.048"` |
| `weekend_ratio` | string | `"0.153"` |
| `avg_call_duration` | string | `"165.2"` |
| `contact_diversity` | string | `"0.086"` |
| `total_sms` | string | `"312"` |
| `sent_sms` | string | `"98"` |
| `received_sms` | string | `"214"` |
| `unique_sms_contacts` | string | `"35"` |
| `network_size` | string | `"62"` |
| `unique_locations` | string | `"8"` |
| `total_mfs_txns` | string | `"47"` |
| `total_mfs_volume` | string | `"25000.00"` |
| `total_recharges` | string | `"12"` |
| `total_recharge_amount` | string | `"2400.00"` |
| `mfs_activity_score` | string | `"0.47"` |
| `recharge_frequency` | string | `"12.0"` |
| `timestamp` | string | `"2026-03-25 14:30:00"` |
| `synced` | string | `"0"` |

---

### 11. `installed_apps`

| Field | Type | Example |
|-------|------|---------|
| `id` | string | `"1"` |
| `package_name` | string | `"com.bKash.customerapp"` |
| `app_name` | string | `"bKash"` |
| `category` | string | `"MFS"` / `"BANKING"` / `"RIDE"` / `"ECOMMERCE"` / `"FOOD_DELIVERY"` / `"SOCIAL"` / `"UTILITY"` / `"PROFESSIONAL"` / `"TELECOM"` / `"GAME"` / `"OTHER"` |
| `version` | string | `"8.2.1"` |
| `install_date` | string | `"2024-06-15"` |
| `last_update` | string | `"2026-01-10"` |
| `status` | string | `"INSTALLED"` / `"NOT_INSTALLED"` |
| `timestamp` | string | `"2026-03-25 14:30:00"` |
| `synced` | string | `"0"` |

**Summary record** (special entry per collection):

| Field | Value |
|-------|-------|
| `package_name` | `"_summary_"` |
| `app_name` | `"App Summary"` |
| `category` | `"SUMMARY"` |
| `install_date` | Total app count (e.g., `"125"`) |
| `last_update` | Breakdown (e.g., `"system:87,user:38"`) |
| `status` | `"SUMMARY"` |

---

## Backend API Endpoints

### Write Endpoint

| Method | Path | Body |
|--------|------|------|
| POST | `/api/collect` | `{ "type", "data", "device_id" }` |

### Read Endpoints

| Netlify Function | Local Route | Query Params | Limit |
|---|---|---|---|
| `/.netlify/functions/call-logs` | `GET /api/data/call_logs` | `device_id`, `limit` | 200 |
| `/.netlify/functions/sms` | `GET /api/data/sms` | `device_id`, `limit` | 200 |
| `/.netlify/functions/location` | `GET /api/data/location` | `device_id`, `limit` | 100 |
| `/.netlify/functions/sim-history` | `GET /api/data/sim_history` | `device_id` | all |
| `/.netlify/functions/mobile-money` | `GET /api/data/mobile_money` | `device_id`, `limit` | 500 |
| `/.netlify/functions/telecom-usage` | `GET /api/data/telecom_usage` | `device_id`, `limit` | 500 |
| `/.netlify/functions/ride-hailing` | `GET /api/data/ride_hailing` | `device_id`, `limit` | 200 |
| `/.netlify/functions/device-info` | `GET /api/data/device_info` | `device_id` | all |
| `/.netlify/functions/location-dwell` | `GET /api/data/location_dwell` | `device_id`, `limit` | 300 |
| `/.netlify/functions/behavior-scores` | `GET /api/data/behavior_scores` | `device_id` | all |
| `/.netlify/functions/installed-apps` | `GET /api/data/installed_apps` | `device_id`, `limit` | 500 |
| `/.netlify/functions/summary` | `GET /api/summary` | — | — |
| `/.netlify/functions/delete` | `DELETE /api/delete` | `type`, `id`, `device_id` | — |

### Read Response Format

```json
{
  "count": 42,
  "data": [ { ...records... } ]
}
```

### Summary Response Format

```json
{
  "total_call_logs": 523,
  "total_sms": 312,
  "total_locations": 15,
  "total_sim_changes": 3,
  "total_mobile_money": 47,
  "total_telecom_usage": 25,
  "total_ride_hailing": 8,
  "total_device_info": 1,
  "total_location_dwell": 40,
  "total_behavior_scores": 1,
  "total_installed_apps": 50,
  "devices": 1,
  "device_ids": ["abc123def456"]
}
```

---

## Data Collection Flow

```
Android App Collectors
        │
        ▼
  Local SQLite DB
        │
        ▼
  DataSyncManager
  (queries unsynced rows)
        │
        ▼
  POST /api/collect
  { type, data[], device_id }
        │
        ▼
  Backend Storage
  (Netlify Blobs / local data.json)
  Auto-adds: _id, device_id, createdAt
```

> **Note:** The local `server.js` dev server only accepts 4 types (`call_logs`, `sms`, `location`, `sim_history`), while the Netlify functions accept all 11 types.
