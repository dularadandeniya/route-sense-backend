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



