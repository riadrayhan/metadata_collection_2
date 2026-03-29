const {
  countRecords, getDeviceIds,
  optionsResponse, errorResponse, jsonResponse,
} = require('./lib/db');

exports.handler = async (event) => {
  if (event.httpMethod === 'OPTIONS') return optionsResponse();

  try {
    const [calls, sms, locations, simChanges,
           mobileMoney, telecomUsage, rideHailing,
           deviceInfo, locationDwell, behaviorScores, installedApps] = await Promise.all([
      countRecords('call_logs'),
      countRecords('sms'),
      countRecords('location'),
      countRecords('sim_history'),
      countRecords('mobile_money'),
      countRecords('telecom_usage'),
      countRecords('ride_hailing'),
      countRecords('device_info'),
      countRecords('location_dwell'),
      countRecords('behavior_scores'),
      countRecords('installed_apps'),
    ]);
    const deviceIds = await getDeviceIds();

    return jsonResponse({
      total_call_logs: calls,
      total_sms: sms,
      total_locations: locations,
      total_sim_changes: simChanges,
      total_mobile_money: mobileMoney,
      total_telecom_usage: telecomUsage,
      total_ride_hailing: rideHailing,
      total_device_info: deviceInfo,
      total_location_dwell: locationDwell,
      total_behavior_scores: behaviorScores,
      total_installed_apps: installedApps,
      devices: deviceIds.length,
      device_ids: deviceIds,
    });
  } catch (err) {
    return errorResponse(err);
  }
};
