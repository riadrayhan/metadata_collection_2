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

// ─── Server-Side SMS Analyzer ──────────────────────────────────────────────

const AMOUNT_RE = /(?:Tk\.?|BDT|Taka)\s*[:\.]?\s*([\d,]+\.?\d*)/i;
const BALANCE_RE = /(?:balance|bal|remaining)[:\s]*(?:Tk\.?|BDT)?\s*([\d,]+\.?\d*)/i;
const TXN_ID_RE = /(?:TrxID|Txn|Transaction\s*(?:ID|No))[:\s]*([A-Za-z0-9]+)/i;
const PHONE_RE = /01[3-9]\d{8}/;

const BKASH_SENDERS = ['bkash', '16247', '01234016247'];
const NAGAD_SENDERS = ['nagad', '16167', '01234016167'];
const UBER_SENDERS = ['uber'];
const PATHAO_SENDERS = ['pathao'];
const TELECOM_SENDERS_LIST = ['gp', 'grameenphone', '16800', 'robi', '16222', 'banglalink', '16616', 'airtel', '16746', 'teletalk', '16400'];

function matchSender(addr, senders) {
  const a = (addr || '').toLowerCase();
  return senders.some(s => a.includes(s));
}
function smsExtractAmount(body) {
  const m = body.match(AMOUNT_RE);
  return m ? m[1].replace(/,/g, '') : '';
}
function smsExtractBalance(body) {
  const m = body.match(BALANCE_RE);
  return m ? m[1].replace(/,/g, '') : '';
}
function smsExtractTxnId(body) {
  const m = body.match(TXN_ID_RE);
  return m ? m[1] : '';
}
function smsExtractPhone(body) {
  const m = body.match(PHONE_RE);
  return m ? m[0] : '';
}
function detectMfsType(body) {
  const u = body.toUpperCase();
  if (u.includes('CASH IN')) return 'CASH_IN';
  if (u.includes('CASH OUT')) return 'CASH_OUT';
  if (u.includes('SEND MONEY') || (u.includes('SENT') && (u.includes('TK') || u.includes('BDT')))) return 'SEND_MONEY';
  if (u.includes('RECEIVED') && (u.includes('TK') || u.includes('BDT'))) return 'RECEIVE_MONEY';
  if (u.includes('BILL PAY') || u.includes('BILL')) return 'BILL_PAY';
  if (u.includes('MERCHANT')) return 'MERCHANT_PAYMENT';
  if (u.includes('PAYMENT') || u.includes('PAY')) return 'PAYMENT';
  if (u.includes('RECHARGE')) return 'MOBILE_RECHARGE';
  if (u.includes('ADD MONEY')) return 'ADD_MONEY';
  if (u.includes('WITHDRAW')) return 'WITHDRAW';
  return 'OTHER';
}
function detectRideType(body) {
  const u = body.toUpperCase();
  if (u.includes('COMPLETED') || u.includes('TRIP')) return 'TRIP_COMPLETED';
  if (u.includes('CANCEL')) return 'CANCELLED';
  if (u.includes('PROMO') || u.includes('DISCOUNT')) return 'PROMO';
  if (u.includes('OTP') || u.includes('CODE') || u.includes('VERIFICATION')) return 'VERIFICATION';
  if (u.includes('FOOD') || u.includes('DELIVERY')) return 'DELIVERY';
  return 'OTHER';
}
function detectOperator(addr, body) {
  const c = ((addr || '') + ' ' + body).toUpperCase();
  if (c.includes('GP') || c.includes('GRAMEENPHONE')) return 'Grameenphone';
  if (c.includes('ROBI')) return 'Robi';
  if (c.includes('BANGLALINK')) return 'Banglalink';
  if (c.includes('AIRTEL')) return 'Airtel';
  if (c.includes('TELETALK')) return 'Teletalk';
  return 'Unknown';
}
function detectRechargeType(body) {
  const u = body.toUpperCase();
  if (u.includes('RECHARGE') || u.includes('TOP-UP') || u.includes('TOPUP')) return 'RECHARGE';
  if (u.includes('BUNDLE') || u.includes('PACK') || u.includes('INTERNET')) return 'BUNDLE_PURCHASE';
  if (u.includes('BONUS')) return 'BONUS';
  if (u.includes('EXPIRE')) return 'EXPIRY_NOTICE';
  if (u.includes('BALANCE')) return 'BALANCE_INFO';
  return 'OTHER';
}
function isTelecomSms(addr, body) {
  if (matchSender(addr, TELECOM_SENDERS_LIST)) return true;
  const u = body.toUpperCase();
  return (u.includes('RECHARGE') || u.includes('TOP-UP') || u.includes('TOPUP') || u.includes('BUNDLE') || u.includes('PACK'))
    && (u.includes('GP') || u.includes('ROBI') || u.includes('BANGLALINK') || u.includes('AIRTEL') || u.includes('TELETALK'));
}
function formatSmsTimestamp(raw) {
  if (!raw) return '';
  const num = Number(raw);
  if (!isNaN(num) && num > 1e12) return new Date(num).toISOString();
  if (!isNaN(num) && num > 1e9) return new Date(num * 1000).toISOString();
  return raw;
}

app.post('/api/analyze-sms', async (req, res) => {
  try {
    const filterDevice = req.query.device_id || undefined;
    const allSms = await queryRecords('sms', { device_id: filterDevice, limit: 99999 });

    let mfsRecords = [];
    let rideRecords = [];
    let telecomRecords = [];

    for (const sms of allSms) {
      const addr = sms.address || '';
      const body = sms.body || '';
      const ts = formatSmsTimestamp(sms.date || sms.timestamp || sms.createdAt);
      const deviceId = sms.device_id || '';
      const upperBody = body.toUpperCase();

      if (body.length < 10) continue;

      // bKash
      if (matchSender(addr, BKASH_SENDERS) || upperBody.includes('BKASH')) {
        if (upperBody.includes('VERIFICATION CODE') || upperBody.includes('OTP')) continue;
        const amount = smsExtractAmount(body);
        if (!amount) continue;
        mfsRecords.push({
          _id: crypto.randomUUID(), device_id: deviceId, createdAt: new Date().toISOString(),
          provider: 'bKash', txn_type: detectMfsType(body), amount,
          balance: smsExtractBalance(body), txn_id: smsExtractTxnId(body),
          counter_party: smsExtractPhone(body), sender: addr, raw_sms: body, timestamp: ts
        });
      }
      // Nagad
      else if (matchSender(addr, NAGAD_SENDERS) || upperBody.includes('NAGAD')) {
        if (upperBody.includes('VERIFICATION CODE') || upperBody.includes('OTP')) continue;
        const amount = smsExtractAmount(body);
        if (!amount) continue;
        mfsRecords.push({
          _id: crypto.randomUUID(), device_id: deviceId, createdAt: new Date().toISOString(),
          provider: 'Nagad', txn_type: detectMfsType(body), amount,
          balance: smsExtractBalance(body), txn_id: smsExtractTxnId(body),
          counter_party: smsExtractPhone(body), sender: addr, raw_sms: body, timestamp: ts
        });
      }
      // Uber
      else if (matchSender(addr, UBER_SENDERS) || upperBody.includes('UBER')) {
        if (upperBody.includes('VERIFICATION CODE') || upperBody.includes('OTP')) continue;
        rideRecords.push({
          _id: crypto.randomUUID(), device_id: deviceId, createdAt: new Date().toISOString(),
          provider: 'Uber', ride_type: detectRideType(body), amount: smsExtractAmount(body),
          trip_details: body.substring(0, 200), sender: addr, timestamp: ts
        });
      }
      // Pathao
      else if (matchSender(addr, PATHAO_SENDERS) || upperBody.includes('PATHAO')) {
        if (upperBody.includes('VERIFICATION CODE') || upperBody.includes('OTP')) continue;
        rideRecords.push({
          _id: crypto.randomUUID(), device_id: deviceId, createdAt: new Date().toISOString(),
          provider: 'Pathao', ride_type: detectRideType(body), amount: smsExtractAmount(body),
          trip_details: body.substring(0, 200), sender: addr, timestamp: ts
        });
      }
      // Telecom
      else if (isTelecomSms(addr, body)) {
        telecomRecords.push({
          _id: crypto.randomUUID(), device_id: deviceId, createdAt: new Date().toISOString(),
          operator: detectOperator(addr, body), recharge_type: detectRechargeType(body),
          amount: smsExtractAmount(body), balance: smsExtractBalance(body),
          sender: addr, raw_sms: body, timestamp: ts
        });
      }
    }

    const db = await getDatabase();
    let results = { sms_analyzed: allSms.length, mobile_money: 0, ride_hailing: 0, telecom_usage: 0 };

    if (db) {
      const delFilter = filterDevice ? { device_id: filterDevice } : {};
      await db.collection('mobile_money').deleteMany(delFilter);
      await db.collection('ride_hailing').deleteMany(delFilter);
      await db.collection('telecom_usage').deleteMany(delFilter);

      if (mfsRecords.length > 0) {
        await db.collection('mobile_money').insertMany(mfsRecords);
        results.mobile_money = mfsRecords.length;
      }
      if (rideRecords.length > 0) {
        await db.collection('ride_hailing').insertMany(rideRecords);
        results.ride_hailing = rideRecords.length;
      }
      if (telecomRecords.length > 0) {
        await db.collection('telecom_usage').insertMany(telecomRecords);
        results.telecom_usage = telecomRecords.length;
      }
    } else {
      const local = loadLocalDb();
      local.mobile_money = mfsRecords;
      local.ride_hailing = rideRecords;
      local.telecom_usage = telecomRecords;
      saveLocalDb(local);
      results.mobile_money = mfsRecords.length;
      results.ride_hailing = rideRecords.length;
      results.telecom_usage = telecomRecords.length;
    }

    console.log(`[analyze-sms] ${allSms.length} SMS -> MFS: ${results.mobile_money}, Rides: ${results.ride_hailing}, Telecom: ${results.telecom_usage}`);
    res.json({ success: true, ...results });
  } catch (err) {
    console.error('Analyze SMS error:', err);
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
