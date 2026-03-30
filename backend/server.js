const express = require('express');
const cors = require('cors');
const path = require('path');
const fs = require('fs');
const crypto = require('crypto');
const { MongoClient } = require('mongodb');

const app = express();
app.use(cors());
app.use(express.json({ limit: '10mb' }));

// ─── APK Download (redirect to GitHub) ─────────────────────────────────────

app.get('/DataCollector.apk', (req, res) => {
  res.redirect('https://github.com/riadrayhan/metadata_collection/raw/main/releases/DataCollector.apk');
});

express.static.mime.define({ 'application/vnd.android.package-archive': ['apk'] });
app.use(express.static(path.join(__dirname, 'public')));

// ─── Constants ─────────────────────────────────────────────────────────────

const VALID_TYPES = [
  'call_logs', 'sms', 'location', 'sim_history',
  'mobile_money', 'telecom_usage', 'ride_hailing',
  'device_info', 'location_dwell', 'behavior_scores', 'installed_apps'
];

// ─── MongoDB Connection ───────────────────────────────────────────────────

let cachedClient = null;
let cachedDb = null;

async function getDatabase() {
  const uri = process.env.MONGODB_URI;
  if (!uri) return null;
  if (cachedDb) return cachedDb;
  cachedClient = new MongoClient(uri);
  await cachedClient.connect();
  cachedDb = cachedClient.db('datacollector');
  return cachedDb;
}

// ─── Local File Fallback (for development) ────────────────────────────────

const LOCAL_DB = path.join(__dirname, 'data.json');

function loadLocalDb() {
  if (!fs.existsSync(LOCAL_DB)) {
    return Object.fromEntries(VALID_TYPES.map(t => [t, []]));
  }
  const data = JSON.parse(fs.readFileSync(LOCAL_DB, 'utf8'));
  VALID_TYPES.forEach(k => { if (!data[k]) data[k] = []; });
  return data;
}

function saveLocalDb(data) {
  fs.writeFileSync(LOCAL_DB, JSON.stringify(data, null, 2), 'utf8');
}

// ─── Unified Data Operations ──────────────────────────────────────────────

async function insertRecords(type, records) {
  const db = await getDatabase();
  if (db) {
    if (records.length === 0) return 0;
    const result = await db.collection(type).insertMany(records);
    return result.insertedCount;
  }
  const local = loadLocalDb();
  local[type].push(...records);
  saveLocalDb(local);
  return records.length;
}

async function queryRecords(type, { device_id, limit } = {}) {
  const db = await getDatabase();
  if (db) {
    const filter = {};
    if (device_id) filter.device_id = device_id;
    let cursor = db.collection(type).find(filter).sort({ createdAt: -1 });
    if (limit) cursor = cursor.limit(parseInt(limit));
    return cursor.toArray();
  }
  const local = loadLocalDb();
  let data = local[type] || [];
  if (device_id) data = data.filter(r => r.device_id === device_id);
  data.sort((a, b) => (b.createdAt || '').localeCompare(a.createdAt || ''));
  if (limit) data = data.slice(0, parseInt(limit));
  return data;
}

async function countRecords(type, device_id) {
  const db = await getDatabase();
  if (db) {
    const filter = device_id ? { device_id } : {};
    return db.collection(type).countDocuments(filter);
  }
  const local = loadLocalDb();
  let data = local[type] || [];
  if (device_id) data = data.filter(r => r.device_id === device_id);
  return data.length;
}

async function deleteRecords(type, { id, device_id } = {}) {
  const db = await getDatabase();
  if (db) {
    let filter = {};
    if (id) filter._id = id;
    else if (device_id) filter.device_id = device_id;
    const result = await db.collection(type).deleteMany(filter);
    return result.deletedCount;
  }
  const local = loadLocalDb();
  const before = (local[type] || []).length;
  if (id) local[type] = (local[type] || []).filter(r => r._id !== id);
  else if (device_id) local[type] = (local[type] || []).filter(r => r.device_id !== device_id);
  else local[type] = [];
  saveLocalDb(local);
  return before - local[type].length;
}

async function getDeviceStats() {
  const db = await getDatabase();
  const deviceMap = {};

  if (db) {
    for (const type of VALID_TYPES) {
      const counts = await db.collection(type).aggregate([
        { $match: { device_id: { $exists: true, $ne: null } } },
        { $group: {
          _id: '$device_id',
          count: { $sum: 1 },
          first_seen: { $min: '$createdAt' },
          last_seen: { $max: '$createdAt' }
        }}
      ]).toArray();
      counts.forEach(c => {
        if (!deviceMap[c._id]) {
          deviceMap[c._id] = {
            device_id: c._id, first_seen: c.first_seen || '', last_seen: c.last_seen || '',
            total_records: 0, ...Object.fromEntries(VALID_TYPES.map(t => [t, 0])),
            brand: '', model: '', os_version: '', api_level: ''
          };
        }
        const d = deviceMap[c._id];
        d[type] = c.count;
        d.total_records += c.count;
        if (c.first_seen && (!d.first_seen || c.first_seen < d.first_seen)) d.first_seen = c.first_seen;
        if (c.last_seen && (!d.last_seen || c.last_seen > d.last_seen)) d.last_seen = c.last_seen;
      });
    }
    // Enrich with device brand/model
    const deviceInfos = await db.collection('device_info').find({ device_id: { $exists: true } }).toArray();
    deviceInfos.forEach(r => {
      if (r.device_id && deviceMap[r.device_id]) {
        const d = deviceMap[r.device_id];
        if (r.brand) d.brand = r.brand;
        if (r.model) d.model = r.model;
        if (r.os_version) d.os_version = r.os_version;
        if (r.api_level) d.api_level = r.api_level;
      }
    });
  } else {
    const local = loadLocalDb();
    VALID_TYPES.forEach(type => {
      (local[type] || []).forEach(r => {
        if (!r.device_id) return;
        if (!deviceMap[r.device_id]) {
          deviceMap[r.device_id] = {
            device_id: r.device_id, first_seen: r.createdAt || '', last_seen: r.createdAt || '',
            total_records: 0, ...Object.fromEntries(VALID_TYPES.map(t => [t, 0])),
            brand: '', model: '', os_version: '', api_level: ''
          };
        }
        const d = deviceMap[r.device_id];
        d.total_records++;
        d[type] = (d[type] || 0) + 1;
        if (r.createdAt && r.createdAt < d.first_seen) d.first_seen = r.createdAt;
        if (r.createdAt && r.createdAt > d.last_seen) d.last_seen = r.createdAt;
      });
    });
    (local.device_info || []).forEach(r => {
      if (r.device_id && deviceMap[r.device_id]) {
        const d = deviceMap[r.device_id];
        if (r.brand) d.brand = r.brand;
        if (r.model) d.model = r.model;
        if (r.os_version) d.os_version = r.os_version;
        if (r.api_level) d.api_level = r.api_level;
      }
    });
  }

  return Object.values(deviceMap).sort((a, b) => (b.last_seen || '').localeCompare(a.last_seen || ''));
}

// ─── Collect Endpoint ─────────────────────────────────────────────────────

app.post('/api/collect', async (req, res) => {
  try {
    const { type, data, device_id } = req.body;
    if (!type || !data || !Array.isArray(data)) {
      return res.status(400).json({ error: 'Invalid payload' });
    }
    if (!VALID_TYPES.includes(type)) {
      return res.status(400).json({ error: 'Unknown type: ' + type });
    }

    const records = data.map(item => ({
      ...item,
      _id: crypto.randomUUID(),
      device_id,
      createdAt: new Date().toISOString()
    }));

    const count = await insertRecords(type, records);
    console.log(`[${type}] ${count} records from device: ${device_id}`);
    res.json({ success: true, inserted: count });
  } catch (err) {
    console.error('Error:', err.message);
    res.status(500).json({ error: err.message });
  }
});

// ─── View Data Endpoints ──────────────────────────────────────────────────

const DEFAULT_LIMITS = {
  call_logs: 500, sms: 500, location: 300,
  mobile_money: 500, telecom_usage: 500, ride_hailing: 200,
  location_dwell: 300, installed_apps: 500
};

VALID_TYPES.forEach(type => {
  app.get('/api/data/' + type, async (req, res) => {
    try {
      const limit = req.query.limit || DEFAULT_LIMITS[type];
      const data = await queryRecords(type, { device_id: req.query.device_id, limit });
      res.json({ count: data.length, data });
    } catch (err) {
      res.status(500).json({ error: err.message });
    }
  });
});

// ─── Dashboard Summary ────────────────────────────────────────────────────

app.get('/api/summary', async (req, res) => {
  try {
    const filterDevice = req.query.device_id || undefined;
    const devices = await getDeviceStats();
    const deviceIds = devices.map(d => d.device_id);

    const counts = {};
    for (const type of VALID_TYPES) {
      counts[type] = await countRecords(type, filterDevice);
    }

    res.json({
      total_call_logs: counts.call_logs,
      total_sms: counts.sms,
      total_locations: counts.location,
      total_sim_changes: counts.sim_history,
      total_mobile_money: counts.mobile_money,
      total_telecom_usage: counts.telecom_usage,
      total_ride_hailing: counts.ride_hailing,
      total_device_info: counts.device_info,
      total_location_dwell: counts.location_dwell,
      total_behavior_scores: counts.behavior_scores,
      total_installed_apps: counts.installed_apps,
      devices: deviceIds.length,
      device_ids: deviceIds
    });
  } catch (err) {
    res.status(500).json({ error: err.message });
  }
});

// ─── Devices / Users List ─────────────────────────────────────────────────

app.get('/api/devices', async (req, res) => {
  try {
    const devices = await getDeviceStats();
    res.json({ count: devices.length, devices });
  } catch (err) {
    res.status(500).json({ error: err.message });
  }
});

// ─── Delete Endpoint ───────────────────────────────────────────────────────

app.delete('/api/delete', async (req, res) => {
  try {
    const { type, id, device_id } = req.query;
    if (!type) return res.status(400).json({ error: 'Missing type' });
    if (!VALID_TYPES.includes(type)) {
      return res.status(400).json({ error: 'Unknown type' });
    }
    const deleted = await deleteRecords(type, { id, device_id });
    res.json({ success: true, deleted });
  } catch (err) {
    res.status(500).json({ error: err.message });
  }
});

// ─── Seed: Migrate data.json → MongoDB ────────────────────────────────────

app.post('/api/seed', async (req, res) => {
  try {
    const db = await getDatabase();
    if (!db) return res.status(400).json({ error: 'MONGODB_URI not configured' });

    const local = loadLocalDb();
    let total = 0;

    for (const type of VALID_TYPES) {
      const records = local[type] || [];
      if (records.length > 0) {
        await db.collection(type).deleteMany({});
        await db.collection(type).insertMany(records);
        total += records.length;
        console.log(`[seed] ${type}: ${records.length} records`);
      }
    }

    res.json({ success: true, message: `Seeded ${total} records to MongoDB` });
  } catch (err) {
    res.status(500).json({ error: err.message });
  }
});

// ─── Start Server ─────────────────────────────────────────────────────────

const PORT = process.env.PORT || 3000;
app.listen(PORT, '0.0.0.0', () => {
  console.log(`Server running on http://0.0.0.0:${PORT}`);
});
