const {
  VALID_TYPES, deleteRecord, deleteAll,
  optionsResponse, errorResponse, jsonResponse,
} = require('./lib/db');

exports.handler = async (event) => {
  if (event.httpMethod === 'OPTIONS') return optionsResponse();
  if (event.httpMethod !== 'DELETE' && event.httpMethod !== 'POST') {
    return jsonResponse({ error: 'Method not allowed' }, 405);
  }

  try {
    const params = event.queryStringParameters || {};
    const { type, id, device_id } = params;

    if (!type) {
      return jsonResponse({ error: 'Missing "type" parameter' }, 400);
    }
    if (!VALID_TYPES.includes(type)) {
      return jsonResponse({ error: 'Unknown type: ' + type }, 400);
    }

    let deleted;
    if (id) {
      deleted = await deleteRecord(type, id);
    } else {
      deleted = await deleteAll(type, device_id);
    }

    return jsonResponse({ success: true, deleted });
  } catch (err) {
    return errorResponse(err);
  }
};
