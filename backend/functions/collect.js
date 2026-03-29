const {
  VALID_TYPES, addRecords,
  optionsResponse, errorResponse, jsonResponse,
} = require('./lib/db');

exports.handler = async (event) => {
  if (event.httpMethod === 'OPTIONS') return optionsResponse();
  if (event.httpMethod !== 'POST') {
    return jsonResponse({ error: 'Method not allowed' }, 405);
  }

  try {
    const { type, data, device_id } = JSON.parse(event.body || '{}');

    if (!type || !data || !Array.isArray(data)) {
      return jsonResponse({ error: 'Invalid payload — need type, data[]' }, 400);
    }
    if (!VALID_TYPES.includes(type)) {
      return jsonResponse({ error: 'Unknown type: ' + type }, 400);
    }

    const records = data.map(item => ({ ...item, device_id }));
    const count = await addRecords(type, records);

    return jsonResponse({ success: true, inserted: count });
  } catch (err) {
    return errorResponse(err);
  }
};
