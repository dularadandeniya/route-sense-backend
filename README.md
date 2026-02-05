# 1. Process the map data (Extract)
docker run -t -v "${PWD}:/data" osrm/osrm-backend osrm-extract -p /opt/car.lua /data/sri-lanka-latest.osm.pbf

# 2. Prepare the topology (Partition)
docker run -t -v "${PWD}:/data" osrm/osrm-backend osrm-partition /data/sri-lanka-latest.osrm

# 3. Finalize (Customize)
docker run -t -v "${PWD}:/data" osrm/osrm-backend osrm-customize /data/sri-lanka-latest.osrm

# 4. Start the Server
docker run -t -i -p 5000:5000 -v "${PWD}:/data" osrm/osrm-backend osrm-routed --algorithm mld /data/sri-lanka-latest.osrm


# RouteSense Backend 

RouteSense Backend is a Spring Boot–based route optimization engine that calculates:
- Optimal and alternative delivery routes
- Travel time (via OSRM)
- Fuel consumption
- CO₂ emissions
- Fuel cost (Sri Lanka diesel pricing)

It supports both **direct routes** and **multi-stop route optimization** using **NSGA-II (MOEA Framework)**.

---

##  Core Features

- OSRM-based routing (distance + duration)
- Multi-objective optimization:
    - Minimize time
    - Minimize fuel cost
    - Minimize CO₂ emissions
- Vehicle-aware calculations (Light / Medium / Heavy)
- Payload & traffic factor aware
- Clean separation of concerns:
    - Routing → OSRM
    - Optimization → MOEA / NSGA-II
    - Emissions & fuel → Utility module

---

##  Tech Stack

- **Java**
- **Spring Boot**
- **OSRM (Docker)**
- **MOEA Framework (NSGA-II)**
- **REST API**

---

##  Cost & Emission Model

### Vehicle Profiles

| Vehicle | Max Payload | Fuel / km |
|-------|-------------|-----------|
| LIGHT | ≤ 2T | 0.14 L/km |
| MEDIUM | ≤ 10T | 0.28 L/km |
| HEAVY | ≤ 20T | 0.38 L/km |

### Fuel & CO₂

- Diesel price (Sri Lanka): **LKR 277 / liter**
- CO₂ emission factor: **2.68 kg CO₂ / liter**



