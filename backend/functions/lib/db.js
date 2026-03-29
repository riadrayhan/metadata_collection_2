const { getStore } = require('@netlify/blobs');
const crypto = require('crypto');

// ─── Blob Storage Helpers ─────────────────────────────────────────────────

const STORE_NAME = 'datacollector';
const VALID_TYPES = [
  'call_logs', 'sms', 'location', 'sim_history',
  'mobile_money', 'telecom_usage', 'ride_hailing',
  'device_info', 'location_dwell', 'behavior_scores', 'installed_apps'
];

async function getData(type) {
  const store = getStore({
    name: STORE_NAME,
    siteID: process.env.SITE_ID,
    token: process.env.NETLIFY_TOKEN,
  });
  try {
    const raw = await store.get(type);
    return raw ? JSON.parse(raw) : [];
  } catch {
    return [];
  }
}

async function setData(type, data) {
  const store = getStore({
    name: STORE_NAME,
    siteID: process.env.SITE_ID,
    token: process.env.NETLIFY_TOKEN,
  });
  await store.set(type, JSON.stringify(data));
}

async function addRecords(type, records) {
  const existing = await getData(type);
  const withIds = records.map(r => ({
    ...r,
    _id: crypto.randomUUID(),
    createdAt: new Date().toISOString(),
  }));
  existing.push(...withIds);
  await setData(type, existing);
  return withIds.length;
}

async function queryRecords(type, { device_id, limit } = {}) {
  let data = await getData(type);
  if (device_id) data = data.filter(r => r.device_id === device_id);
  data.sort((a, b) => (b.createdAt || '').localeCompare(a.createdAt || ''));
  if (limit) data = data.slice(0, parseInt(limit));
  return data;
}

async function deleteRecord(type, id) {
  const data = await getData(type);
  const filtered = data.filter(r => r._id !== id);
  await setData(type, filtered);
  return data.length - filtered.length;
}

async function deleteAll(type, device_id) {
  if (device_id) {
    const data = await getData(type);
    const filtered = data.filter(r => r.device_id !== device_id);
    await setData(type, filtered);
    return data.length - filtered.length;
  }
  const data = await getData(type);
  await setData(type, []);
  return data.length;
}

async function countRecords(type) {
  const data = await getData(type);
  return data.length;
}

async function getDeviceIds() {
  const ids = new Set();
  for (const type of VALID_TYPES) {
    const data = await getData(type);
    data.forEach(r => { if (r.device_id) ids.add(r.device_id); });
  }
  return [...ids];
}

// ─── HTTP Helpers ─────────────────────────────────────────────────────────

function headers() {
  return {
    'Access-Control-Allow-Origin': '*',
    'Access-Control-Allow-Headers': 'Content-Type',
    'Access-Control-Allow-Methods': 'GET, POST, DELETE, OPTIONS',
    'Content-Type': 'application/json',
  };
}

function optionsResponse() {
  return { statusCode: 204, headers: headers(), body: '' };
}

function errorResponse(err) {
  return {
    statusCode: 500,
    headers: headers(),
    body: JSON.stringify({ error: err.message || String(err) }),
  };
}

function jsonResponse(data, statusCode = 200) {
  return { statusCode, headers: headers(), body: JSON.stringify(data) };
}

module.exports = {
  VALID_TYPES,
  addRecords,
  queryRecords,
  deleteRecord,
  deleteAll,
  countRecords,
  getDeviceIds,
  headers,
  optionsResponse,
  errorResponse,
  jsonResponse,
};
