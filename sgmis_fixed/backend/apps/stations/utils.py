import math

def calculate_haversine_distance_meters(lat1: float, lon1: float, lat2: float, lon2: float) -> float:
    """
    Calculate the great-circle distance between two points on the Earth
    in meters using the Haversine formula.
    """
    R = 6371000.0  # Earth radius in meters
    phi1 = math.radians(lat1)
    phi2 = math.radians(lat2)
    delta_phi = math.radians(lat2 - lat1)
    delta_lambda = math.radians(lon2 - lon1)

    a = (math.sin(delta_phi / 2.0) ** 2 +
         math.cos(phi1) * math.cos(phi2) * math.sin(delta_lambda / 2.0) ** 2)
    c = 2.0 * math.atan2(math.sqrt(a), math.sqrt(1.0 - a))

    return R * c

def is_within_geofence(
    target_lat: float,
    target_lon: float,
    center_lat: float,
    center_lon: float,
    radius_meters: float,
    buffer_meters: float = 50.0
) -> bool:
    """
    Check if target coordinates are within center coordinates + radius (+ buffer).
    If center coordinates are default 0.0, 0.0 (unconfigured station coordinates),
    validation passes to avoid locking unconfigured dev stations.
    """
    if center_lat == 0.0 and center_lon == 0.0:
        return True

    distance = calculate_haversine_distance_meters(target_lat, target_lon, center_lat, center_lon)
    return distance <= (radius_meters + buffer_meters)
