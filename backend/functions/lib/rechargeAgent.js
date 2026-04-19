/**
 * SMS Recharge Agent
 * ------------------
 * A lightweight "agent" (CrewAI-style pipeline implemented in pure JS so it
 * runs inside the existing Node backend) that inspects SMS bodies and picks
 * out mobile-recharge messages — including Bengali messages such as:
 *
 *     "আপনি 100.00 টাকা রিচার্জ করেছেন"
 *     "আপনার নম্বরে ৫০ টাকা রিচার্জ হয়েছে"
 *     "You have recharged Tk 200.00"
 *
 * The agent runs three logical "tools":
 *   1. classifier  — decides whether the SMS is about a recharge
 *   2. extractor   — pulls the recharged amount (handles Bengali digits)
 *   3. enricher    — attaches operator / timestamp / device metadata
 *
 * Only messages classified as RECHARGE are returned — this keeps the admin
 * panel filter clean (exactly what the user asked for: only recharge numbers
 * should be counted).
 */

// ─── Bengali digit handling ─────────────────────────────────────────────
const BN_DIGITS = { '০':'0','১':'1','২':'2','৩':'3','৪':'4','৫':'5','৬':'6','৭':'7','৮':'8','৯':'9' };

function normalizeDigits(str) {
  if (!str) return '';
  return String(str).replace(/[০-৯]/g, d => BN_DIGITS[d] || d);
}

// ─── Recharge classifier ────────────────────────────────────────────────
// Bengali + English keywords. We require BOTH a "recharge" keyword AND a
// currency/amount hint to avoid false positives (balance info, bundle ads…).
const RECHARGE_KEYWORDS = [
  'রিচার্জ',       // Bengali: recharge
  'রিচার্জ করেছেন', // Bengali: you have recharged
  'রিচার্জ হয়েছে',  // Bengali: has been recharged
  'recharge',
  'recharged',
  'top-up',
  'top up',
  'topup',
  'load'
];

const CURRENCY_KEYWORDS = ['টাকা', 'tk', 'tk.', 'bdt', 'taka'];

// Words that *disqualify* a message even if it contains "recharge".
// Includes Bengali promotional / data-pack vocabulary.
const NEGATIVE_KEYWORDS = [
  // English
  'failed', 'unsuccessful', 'could not',
  'offer', 'bundle', 'pack', 'bonus',
  'otp', 'verification code',
  'balance check', 'balance is',
  'super deal', 'discount', 'free',
  'mb', 'gb', 'minute', 'minutes',
  // Bengali
  'অফার',        // offer
  'ডিল',         // deal
  'সুপার ডিল',   // super deal
  'বোনাস',       // bonus
  'ফ্রি',        // free
  'জিবি',        // GB
  'এমবি',        // MB
  'মিনিট',       // minutes
  'দিন',         // days (e.g. "৩০ দিন" validity — indicates a pack)
  'মেয়াদ',      // validity
  'প্যাক',       // pack
  'ছাড়',         // discount
  'কিনুন',       // buy
  'গ্রাহক সেবা', // customer care
];

// Confirmation phrases — if present, we consider this a TRUE recharge
// confirmation (overrides weaker negatives like "free" which can appear in
// carrier footers). All lowercase.
const STRONG_RECHARGE_PHRASES = [
  'রিচার্জ করেছেন',   // "you have recharged"
  'রিচার্জ হয়েছে',   // "has been recharged"
  'recharged successfully',
  'recharge successful',
  'have recharged',
  'has been recharged',
];

function isRechargeSms(body) {
  if (!body) return false;
  const lower = body.toLowerCase();

  // Exclude bKash / Nagad / other MFS wallet messages — those belong to the
  // Mobile Money tab, not the telecom recharge tab.
  const MFS_MARKERS = ['bkash', 'nagad', 'rocket', 'upay', 'tap', 'mcash'];
  if (MFS_MARKERS.some(m => lower.includes(m))) return false;

  const hasRecharge = RECHARGE_KEYWORDS.some(k => lower.includes(k.toLowerCase()));
  if (!hasRecharge) return false;

  const hasCurrency = CURRENCY_KEYWORDS.some(k => lower.includes(k));
  if (!hasCurrency) return false;

  const hasStrongConfirmation = STRONG_RECHARGE_PHRASES.some(p => lower.includes(p));
  // Only the strong confirmation can override the promotional filter.
  if (!hasStrongConfirmation) {
    if (NEGATIVE_KEYWORDS.some(k => lower.includes(k))) return false;
  }

  return true;
}

// ─── Amount extractor ───────────────────────────────────────────────────
// Tries, in order:
//   1. number immediately before "টাকা" / "tk" / "bdt"
//   2. any numeric token in a recharge sentence
function extractRechargeAmount(body) {
  if (!body) return null;
  const normalized = normalizeDigits(body);

  // Pattern 1: "100.00 টাকা রিচার্জ"  OR  "Tk 100.00 recharged"
  const patterns = [
    /([\d,]+(?:\.\d+)?)\s*(?:টাকা|tk\.?|bdt|taka)/i,
    /(?:টাকা|tk\.?|bdt|taka)\s*([\d,]+(?:\.\d+)?)/i,
    /recharged?\s*(?:of|for)?\s*(?:tk\.?|bdt)?\s*([\d,]+(?:\.\d+)?)/i
  ];
  for (const re of patterns) {
    const m = normalized.match(re);
    if (m) {
      const n = parseFloat(m[1].replace(/,/g, ''));
      if (!isNaN(n) && n > 0) return n;
    }
  }
  return null;
}

// ─── Operator detection (reused from server.js logic) ───────────────────
function detectOperator(address, body) {
  const c = ((address || '') + ' ' + (body || '')).toUpperCase();
  if (c.includes('GP') || c.includes('GRAMEENPHONE')) return 'Grameenphone';
  if (c.includes('ROBI')) return 'Robi';
  if (c.includes('BANGLALINK') || c.includes('BL ')) return 'Banglalink';
  if (c.includes('AIRTEL')) return 'Airtel';
  if (c.includes('TELETALK')) return 'Teletalk';
  if (c.includes('BKASH')) return 'bKash';
  if (c.includes('NAGAD')) return 'Nagad';
  return 'Unknown';
}

function formatTimestamp(raw) {
  if (!raw) return '';
  const num = Number(raw);
  if (!isNaN(num) && num > 1e12) return new Date(num).toISOString();
  if (!isNaN(num) && num > 1e9)  return new Date(num * 1000).toISOString();
  return String(raw);
}

// ─── Main agent entry point ─────────────────────────────────────────────
/**
 * Run the recharge agent over a list of SMS records.
 * Returns { records, total_count, total_amount, by_operator }.
 */
function analyzeRechargeSms(smsList) {
  const records = [];

  // Senders that belong to MFS wallets, not telecom recharge.
  const MFS_SENDERS = ['bkash', 'nagad', 'rocket', 'upay', '16247', '16167', '16216'];

  for (const sms of smsList || []) {
    const body = sms.body || '';
    const addr = (sms.address || '').toLowerCase();
    if (MFS_SENDERS.some(s => addr.includes(s))) continue;
    if (!isRechargeSms(body)) continue;

    const amount = extractRechargeAmount(body);
    if (amount === null) continue;   // counted only when we have a number

    records.push({
      _id: sms._id,
      device_id: sms.device_id || '',
      sender: sms.address || '',
      operator: detectOperator(sms.address, body),
      amount,
      body,
      timestamp: formatTimestamp(sms.date || sms.timestamp || sms.createdAt),
      createdAt: sms.createdAt || new Date().toISOString(),
    });
  }

  records.sort((a, b) => (b.createdAt || '').localeCompare(a.createdAt || ''));

  const total_amount = records.reduce((s, r) => s + (r.amount || 0), 0);
  const by_operator = {};
  for (const r of records) {
    if (!by_operator[r.operator]) by_operator[r.operator] = { count: 0, amount: 0 };
    by_operator[r.operator].count  += 1;
    by_operator[r.operator].amount += r.amount;
  }

  return {
    total_count: records.length,
    total_amount: Number(total_amount.toFixed(2)),
    by_operator,
    records,
  };
}

module.exports = {
  analyzeRechargeSms,
  isRechargeSms,
  extractRechargeAmount,
  detectOperator,
  normalizeDigits,
};
