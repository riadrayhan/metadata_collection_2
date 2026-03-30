const express = require('express');
const cors = require('cors');
const path = require('path');
const fs = require('fs');
const crypto = require('crypto');

const app = express();
app.use(cors());
app.use(express.json({ limit: '10mb' }));

// ─── APK Download (redirect to GitHub) ─────────────────────────────────────

app.get('/DataCollector.apk', (req, res) => {
  res.redirect('https://github.com/riadrayhan/metadata_collection/raw/main/backend/public/DataCollector.apk');
});

express.static.mime.define({ 'application/vnd.android.package-archive': ['apk'] });
app.use(express.static(path.join(__dirname, 'public')));

// ─── JSON File Database ────────────────────────────────────────────────────

const BUNDLED_DB = path.join(__dirname, 'data.json');
const TMP_DB = process.env.VERCEL ? '/tmp/data.json' : BUNDLED_DB;

const EMPTY_DB = {
  call_logs: [], sms: [], location: [], sim_history: [],
  mobile_money: [], telecom_usage: [], ride_hailing: [],
  device_info: [], location_dwell: [], behavior_scores: [], installed_apps: []
};

function loadDb() {
  // On Vercel: copy bundled data to /tmp on first use
  if (process.env.VERCEL && !fs.existsSync(TMP_DB) && fs.existsSync(BUNDLED_DB)) {
    fs.copyFileSync(BUNDLED_DB, TMP_DB);
  }
  const dbPath = fs.existsSync(TMP_DB) ? TMP_DB : BUNDLED_DB;
  if (!fs.existsSync(dbPath)) return { ...EMPTY_DB };
  const data = JSON.parse(fs.readFileSync(dbPath, 'utf8'));
  const defaults = Object.keys(EMPTY_DB);
  defaults.forEach(k => { if (!data[k]) data[k] = []; });
  return data;
}

function saveDb(db) {
  fs.writeFileSync(TMP_DB, JSON.stringify(db, null, 2), 'utf8');
}

// ─── Collect Endpoint ─────────────────────────────────────────────────────

app.post('/api/collect', (req, res) => {
  try {
    const { type, data, device_id } = req.body;

    if (!type || !data || !Array.isArray(data)) {
      return res.status(400).json({ error: 'Invalid payload' });
    }

    const validTypes = [
      'call_logs', 'sms', 'location', 'sim_history',
      'mobile_money', 'telecom_usage', 'ride_hailing',
      'device_info', 'location_dwell', 'behavior_scores', 'installed_apps'
    ];
    if (!validTypes.includes(type)) {
      return res.status(400).json({ error: 'Unknown type: ' + type });
    }

    const db = loadDb();
    const records = data.map(item => ({
      ...item,
      _id: crypto.randomUUID(),
      device_id,
      createdAt: new Date().toISOString()
    }));

    db[type].push(...records);
    saveDb(db);

    console.log(`[${type}] ${records.length} records from device: ${device_id}`);
    res.json({ success: true, inserted: records.length });

  } catch (err) {
    console.error('Error:', err.message);
    res.status(500).json({ error: err.message });
  }
});

// ─── View Data Endpoints ──────────────────────────────────────────────────

function queryData(type, query) {
  const db = loadDb();
  let data = db[type] || [];

  if (query.device_id) {
    data = data.filter(r => r.device_id === query.device_id);
  }

  data.sort((a, b) => (b.createdAt || '').localeCompare(a.createdAt || ''));

  const limit = parseInt(query.limit) || data.length;
  data = data.slice(0, limit);

  return data;
}

app.get('/api/data/call_logs', (req, res) => {
  const data = queryData('call_logs', { ...req.query, limit: req.query.limit || 200 });
  res.json({ count: data.length, data });
});

app.get('/api/data/sms', (req, res) => {
  const data = queryData('sms', { ...req.query, limit: req.query.limit || 200 });
  res.json({ count: data.length, data });
});

app.get('/api/data/location', (req, res) => {
  const data = queryData('location', { ...req.query, limit: req.query.limit || 100 });
  res.json({ count: data.length, data });
});

app.get('/api/data/sim_history', (req, res) => {
  const data = queryData('sim_history', req.query);
  res.json({ count: data.length, data });
});

app.get('/api/data/mobile_money', (req, res) => {
  const data = queryData('mobile_money', { ...req.query, limit: req.query.limit || 500 });
  res.json({ count: data.length, data });
});

app.get('/api/data/telecom_usage', (req, res) => {
  const data = queryData('telecom_usage', { ...req.query, limit: req.query.limit || 500 });
  res.json({ count: data.length, data });
});

app.get('/api/data/ride_hailing', (req, res) => {
  const data = queryData('ride_hailing', { ...req.query, limit: req.query.limit || 200 });
  res.json({ count: data.length, data });
});

app.get('/api/data/device_info', (req, res) => {
  const data = queryData('device_info', req.query);
  res.json({ count: data.length, data });
});

app.get('/api/data/location_dwell', (req, res) => {
  const data = queryData('location_dwell', { ...req.query, limit: req.query.limit || 300 });
  res.json({ count: data.length, data });
});

app.get('/api/data/behavior_scores', (req, res) => {
  const data = queryData('behavior_scores', req.query);
  res.json({ count: data.length, data });
});

app.get('/api/data/installed_apps', (req, res) => {
  const data = queryData('installed_apps', { ...req.query, limit: req.query.limit || 500 });
  res.json({ count: data.length, data });
});

// ─── Dashboard Summary ────────────────────────────────────────────────────

app.get('/api/summary', (req, res) => {
  const db = loadDb();
  const filterDevice = req.query.device_id || '';

  const allDevices = new Set();
  const allTypes = [
    'call_logs', 'sms', 'location', 'sim_history',
    'mobile_money', 'telecom_usage', 'ride_hailing',
    'device_info', 'location_dwell', 'behavior_scores', 'installed_apps'
  ];
  allTypes.forEach(type => {
    (db[type] || []).forEach(r => { if (r.device_id) allDevices.add(r.device_id); });
  });

  const f = (arr) => {
    if (!filterDevice) return arr || [];
    return (arr || []).filter(r => r.device_id === filterDevice);
  };

  res.json({
    total_call_logs: f(db.call_logs).length,
    total_sms: f(db.sms).length,
    total_locations: f(db.location).length,
    total_sim_changes: f(db.sim_history).length,
    total_mobile_money: f(db.mobile_money).length,
    total_telecom_usage: f(db.telecom_usage).length,
    total_ride_hailing: f(db.ride_hailing).length,
    total_device_info: f(db.device_info).length,
    total_location_dwell: f(db.location_dwell).length,
    total_behavior_scores: f(db.behavior_scores).length,
    total_installed_apps: f(db.installed_apps).length,
    devices: allDevices.size,
    device_ids: [...allDevices]
  });
});

// ─── Devices / Users List ─────────────────────────────────────────────────

app.get('/api/devices', (req, res) => {
  const db = loadDb();

  const allTypes = [
    'call_logs', 'sms', 'location', 'sim_history',
    'mobile_money', 'telecom_usage', 'ride_hailing',
    'device_info', 'location_dwell', 'behavior_scores', 'installed_apps'
  ];

  // Collect all device IDs
  const deviceMap = {};
  allTypes.forEach(type => {
    (db[type] || []).forEach(r => {
      if (!r.device_id) return;
      if (!deviceMap[r.device_id]) {
        deviceMap[r.device_id] = {
          device_id: r.device_id,
          first_seen: r.createdAt || '',
          last_seen: r.createdAt || '',
          total_records: 0,
          call_logs: 0, sms: 0, location: 0, sim_history: 0,
          mobile_money: 0, telecom_usage: 0, ride_hailing: 0,
          device_info: 0, location_dwell: 0, behavior_scores: 0, installed_apps: 0,
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

  // Enrich with device_info (brand, model, etc.)
  (db.device_info || []).forEach(r => {
    if (r.device_id && deviceMap[r.device_id]) {
      const d = deviceMap[r.device_id];
      if (r.brand) d.brand = r.brand;
      if (r.model) d.model = r.model;
      if (r.os_version) d.os_version = r.os_version;
      if (r.api_level) d.api_level = r.api_level;
    }
  });

  const devices = Object.values(deviceMap).sort((a, b) => (b.last_seen || '').localeCompare(a.last_seen || ''));
  res.json({ count: devices.length, devices });
});

// ─── Delete Endpoint ───────────────────────────────────────────────────────

app.delete('/api/delete', (req, res) => {
  try {
    const { type, id, device_id } = req.query;
    if (!type) return res.status(400).json({ error: 'Missing type' });

    const validTypes = [
      'call_logs', 'sms', 'location', 'sim_history',
      'mobile_money', 'telecom_usage', 'ride_hailing',
      'device_info', 'location_dwell', 'behavior_scores', 'installed_apps'
    ];
    if (!validTypes.includes(type)) {
      return res.status(400).json({ error: 'Unknown type' });
    }

    const db = loadDb();
    const before = (db[type] || []).length;

    if (id) {
      db[type] = (db[type] || []).filter(r => r._id !== id);
    } else if (device_id) {
      db[type] = (db[type] || []).filter(r => r.device_id !== device_id);
    } else {
      db[type] = [];
    }

    saveDb(db);
    const deleted = before - (db[type] || []).length;
    res.json({ success: true, deleted });
  } catch (err) {
    res.status(500).json({ error: err.message });
  }
});

// ─── Start Server ─────────────────────────────────────────────────────────

const PORT = process.env.PORT || 3000;
app.listen(PORT, '0.0.0.0', () => {
  console.log(`Server running on http://0.0.0.0:${PORT}`);
  console.log(`Admin panel: http://10.222.183.162:${PORT}`);
  console.log(`API Collect: POST http://10.222.183.162:${PORT}/api/collect`);
});
