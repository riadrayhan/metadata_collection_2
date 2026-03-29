const {
  queryRecords,
  optionsResponse, errorResponse, jsonResponse,
} = require('./lib/db');

exports.handler = async (event) => {
  if (event.httpMethod === 'OPTIONS') return optionsResponse();

  try {
    const params = event.queryStringParameters || {};
    const data = await queryRecords('location', {
      device_id: params.device_id,
      limit: params.limit || 100,
    });
    return jsonResponse({ count: data.length, data });
  } catch (err) {
    return errorResponse(err);
  }
};
