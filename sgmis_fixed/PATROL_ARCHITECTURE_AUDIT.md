# SMART SECURITY (SGMIS) — PATROL ARCHITECTURE AND IMPLEMENTATION AUDIT
**Project:** Smart Security / SGMIS  
**Path:** `C:\Projects\SGMIS_FIXED\sgmis_fixed`  
**Audit Type:** Strict Read-Only Technical Architecture & Implementation Audit  
**Date:** 2026-10-05  
**Audit Status:** COMPLETE  

---

## 1. Executive Summary

An exhaustive, read-only architectural audit of the Smart Security / SGMIS guard tour and patrol subsystem was conducted across both backend services (`Django 5.1 / DRF 3.15`) and the Android mobile application (`Jetpack Compose / Kotlin / Room / Retrofit`).

### Key Findings
1. **Current Operational Model:** The patrol subsystem is an **online-only, guard-self-initiated** routine. Guards clock into duty, navigate to the patrol screen, and initiate an unassigned patrol for their station. 
2. **Verification Mechanism Reality:** While the Android UI labels its inspection action with a QR scanner icon (`Icons.Default.QrCodeScanner`) and the "About App" screen advertises *"Guard Patrol & QR Route Tracking"*, **no optical camera scanning, QR code decoder, or NFC reader currently exists in the Android codebase**. Tapping "Verify" simply executes a single-shot GPS query via `FusedLocationProviderClient` and transmits latitude/longitude coordinates to the backend.
3. **Critical Backend Vulnerabilities Identified:**
   - **NFC UID Bypass:** `PatrolLogViewSet.scan_checkpoint()` accepts any incoming `nfc_uid` string that is 4 characters or longer as verified proof, without comparing it against any database record or registered tag UID (`backend/apps/patrols/views.py:152`).
   - **Zero-Coordinate Geofence Bypass:** If a station or checkpoint has unconfigured coordinates (`0.0, 0.0`), `is_within_geofence()` unconditionally returns `True` (`backend/apps/stations/utils.py:33-34`), allowing guards anywhere in the world to verify checkpoints.
   - **No Sequential Ordering Enforcement:** Checkpoints have an `order` integer for display, but checkpoints can be scanned in any arbitrary sequence (e.g., Checkpoint 6, then 1, then 3).
   - **No Checkpoint Travel Velocity Checks:** A guard can scan 10 checkpoints across a 2-kilometer facility in 10 seconds. The backend enforces a 60-second total patrol duration upon finish, but zero minimum duration or travel speed between intermediate checkpoints.
   - **Zero Offline Capability:** The Android client has no offline patrol event store or sync queue. If cellular data drops in a basement or perimeter sector, every patrol action immediately crashes with a network error.
   - **Supervisor Approval is a No-Op:** The `POST /api/patrols/logs/{id}/approve/` endpoint exists, but persists nothing to the database (no `approved_by` or `approved_at` fields exist on `PatrolLog`).
4. **Strong Architectural Foundations:**
   - Strict guard ownership: guards can only initiate and record scans on their own active patrol.
   - Off-duty lockout: guards cannot initiate patrols if off-duty or not scheduled on the daily roster.
   - Station isolation: guards and supervisors cannot access or record patrols for foreign stations.
   - Total station checkpoint completion check: a patrol cannot be completed unless all active station checkpoints have been scanned.

---

## 2. Existing Patrol Architecture

### Component Architecture Overview

```
┌────────────────────────────────────────────────────────────────────────┐
│                        ANDROID MOBILE CLIENT                           │
├────────────────────────────────────────────────────────────────────────┤
│  PatrolScreen.kt (Compose UI)                                          │
│  ├── Timer (LaunchedEffect elapsedSeconds)                             │
│  ├── CheckpointItemCard (Verify Button -> single-shot GPS)             │
│  └── Finish Button (Requires elapsed >= 60s & all checkpoints scanned) │
├────────────────────────────────────────────────────────────────────────┤
│  SgmisViewModel.kt                                                     │
│  └── fetchCheckpoints(), fetchPatrolLogs(), startPatrol(),             │
│      scanCheckpoint(), finishPatrol()                                  │
├────────────────────────────────────────────────────────────────────────┤
│  SgmisRepository.kt (Online Direct Dispatch)                           │
│  └── api.startPatrol(), api.scanCheckpoint(), api.finishPatrol()       │
├────────────────────────────────────────────────────────────────────────┤
│  LocationHelper.kt                                                     │
│  └── getDeviceLocation() -> FusedLocationProviderClient (Single-shot)  │
├────────────────────────────────────────────────────────────────────────┤
│  SgmisDatabase.kt (Room Local Persistence)                             │
│  └── CachedCheckpointEntity / CheckpointDao (Read-only cache)         │
│      * NO CachedPatrolLogEntity, NO CachedCheckpointScanEntity *       │
└───────────────────────────────────┬────────────────────────────────────┘
                                    │ HTTPS / REST (DRF)
                                    ▼
┌────────────────────────────────────────────────────────────────────────┐
│                         BACKEND REST SERVICES                          │
├────────────────────────────────────────────────────────────────────────┤
│  apps.patrols.urls                                                     │
│  ├── /api/patrols/checkpoints/ (CheckpointViewSet)                     │
│  └── /api/patrols/logs/ (PatrolLogViewSet)                             │
│      ├── POST /                       -> perform_create (Guard only)   │
│      ├── POST /{id}/scan/             -> scan_checkpoint (Guard only)  │
│      ├── POST /{id}/finish/           -> finish_patrol (Guard only)    │
│      └── POST /{id}/approve/          -> approve_patrol (Sup/Admin)    │
├────────────────────────────────────────────────────────────────────────┤
│  apps.stations.utils                                                   │
│  ├── calculate_haversine_distance_meters() (Great-circle distance)    │
│  └── is_within_geofence() (radius + 50m buffer; 0.0,0.0 dev bypass)    │
├────────────────────────────────────────────────────────────────────────┤
│  apps.patrols.models                                                   │
│  ├── Checkpoint (station, name, code, qr_code, lat, lon, order)        │
│  ├── PatrolLog (guard, station, start_time, end_time, status, notes)   │
│  └── CheckpointScan (patrol_log, checkpoint, scanned_at, gps, notes)   │
└────────────────────────────────────────────────────────────────────────┘
```

---

## 3. Backend Models and Services

### Existing Database Models (`backend/apps/patrols/models.py`)

#### 1. `Checkpoint`
- **File:** [backend/apps/patrols/models.py](file:///c:/Projects/SGMIS_FIXED/sgmis_fixed/backend/apps/patrols/models.py#L5-L26)
- **Primary Key:** `id` (UUIDField)
- **Foreign Keys:** `station` (`ForeignKey("stations.Station", on_delete=CASCADE, related_name="checkpoints")`)
- **Fields:**
  - `name`: `CharField(max_length=150)`
  - `code`: `CharField(max_length=50)`
  - `qr_code`: `CharField(max_length=100, blank=True, default="")`
  - `latitude`: `FloatField(default=0.0)`
  - `longitude`: `FloatField(default=0.0)`
  - `order`: `PositiveIntegerField(default=1)`
  - `is_active`: `BooleanField(default=True)`
- **Meta:** `ordering = ["station", "order"]`, `unique_together = ("station", "code")`
- **Missing Capabilities:** No NFC tag identifier (`nfc_uid`), no route reference, no required minimum/maximum dwell time, no tolerance radius field (hardcoded in views).

#### 2. `PatrolLog`
- **File:** [backend/apps/patrols/models.py](file:///c:/Projects/SGMIS_FIXED/sgmis_fixed/backend/apps/patrols/models.py#L31-L45)
- **Primary Key:** `id` (UUIDField)
- **Foreign Keys:**
  - `guard`: `ForeignKey(settings.AUTH_USER_MODEL, on_delete=CASCADE, related_name="patrol_logs")`
  - `station`: `ForeignKey("stations.Station", on_delete=CASCADE, related_name="patrol_logs")`
- **Fields:**
  - `start_time`: `DateTimeField(auto_now_add=True)`
  - `end_time`: `DateTimeField(null=True, blank=True)`
  - `status`: `CharField(max_length=20, choices=PatrolStatus.choices, default="IN_PROGRESS")`
  - `notes`: `TextField(blank=True, default="")`
- **Meta:** `ordering = ["-start_time"]`
- **Missing Capabilities:** No route foreign key, no assigned supervisor foreign key, no scheduled start/deadline fields, no approval tracking fields (`approved_by`, `approved_at`, `is_approved`), no anomaly flags (`is_anomalous`, `anomaly_reason`).

#### 3. `CheckpointScan`
- **File:** [backend/apps/patrols/models.py](file:///c:/Projects/SGMIS_FIXED/sgmis_fixed/backend/apps/patrols/models.py#L46-L59)
- **Primary Key:** `id` (UUIDField)
- **Foreign Keys:**
  - `patrol_log`: `ForeignKey(PatrolLog, on_delete=CASCADE, related_name="scans")`
  - `checkpoint`: `ForeignKey(Checkpoint, on_delete=CASCADE, related_name="scans")`
- **Fields:**
  - `scanned_at`: `DateTimeField(auto_now_add=True)`
  - `gps_coords`: `CharField(max_length=100, blank=True, default="")`
  - `notes`: `CharField(max_length=255, blank=True, default="Checkpoint secure.")`
- **Meta:** `ordering = ["scanned_at"]`
- **Missing Capabilities:** No scan sequence number, no GPS accuracy metric, no verification method enum field (currently embedded into string notes as `[GPS_PROXIMITY]`), no device hardware identifier, no offline creation timestamp vs sync timestamp.

### Domain Capability Evaluation Matrix

| Domain Requirement | Status | Current Code Reality |
|---|---|---|
| **A. Patrol definition** | **PARTIAL** | `Checkpoint` definitions exist under stations; no independent `PatrolRoute` or `PatrolTemplate` exists. |
| **B. Patrol assignment** | **NOT IMPLEMENTED** | No supervisor assignment mechanism. Guards self-initiate unassigned rounds on their current station. |
| **C. Patrol route** | **NOT IMPLEMENTED** | No `Route` model or grouping. Checkpoints belong directly to `Station`. |
| **D. Ordered checkpoints** | **PARTIAL** | `Checkpoint.order` exists for UI display only. The backend does not enforce sequential completion order. |
| **E. Checkpoint verification** | **PARTIAL** | Backend verifies GPS distance ($\le 150\text{m}$), static QR string match, or NFC length ($\ge 4$ chars). Android only supplies GPS. |
| **F. Start/end timestamps** | **IMPLEMENTED** | `start_time` recorded automatically via `auto_now_add`; `end_time` recorded upon completion. |
| **G. Guard identity** | **IMPLEMENTED** | `PatrolLog.guard` foreign key enforced. Proxy patrol initiation or scanning rejected. |
| **H. Station identity** | **IMPLEMENTED** | `PatrolLog.station` foreign key enforced. Cross-station execution rejected. |
| **I. Supervisor identity** | **NOT IMPLEMENTED** | No supervisor link on `PatrolLog`. `approve_patrol` does not record supervisor identity in DB. |
| **J. Patrol status** | **IMPLEMENTED** | `PatrolStatus` enum (`IN_PROGRESS`, `COMPLETED`). |
| **K. Patrol history / audit trail** | **PARTIAL** | DB records retained (`destroy()` and `update()` disallowed via DRF). Lacks device info, tamper detection, and offline sync metadata. |

---

## 4. Android Patrol Implementation

### UI Components and State Management
- **File:** [app/src/main/java/com/example/ui/screens/PatrolScreen.kt](file:///c:/Projects/SGMIS_FIXED/sgmis_fixed/app/src/main/java/com/example/ui/screens/PatrolScreen.kt)
- **ViewModel:** [app/src/main/java/com/example/ui/viewmodel/SgmisViewModel.kt](file:///c:/Projects/SGMIS_FIXED/sgmis_fixed/app/src/main/java/com/example/ui/viewmodel/SgmisViewModel.kt#L889-L958)
- **Repository:** [app/src/main/java/com/example/data/repository/SgmisRepository.kt](file:///c:/Projects/SGMIS_FIXED/sgmis_fixed/app/src/main/java/com/example/data/repository/SgmisRepository.kt#L644-L700)

#### Screen Behaviors
1. **Timer (`PatrolScreen.kt:47-57`):** Runs a local coroutine looping with `delay(1000)` to update `elapsedSeconds`. Resets to 0 whenever `activePatrol` changes.
2. **Access Control:**
   - Only guards who are marked **On Duty** (`uiState.isOnDuty == true`) and have an assigned station can press **"Initiate Station Patrol"**.
   - Supervisors and Admins see a banner informing them that their access is read-only.
3. **Verification Button (`PatrolScreen.kt:445-460`):**
   - Clicking "Verify" on `CheckpointItemCard` does **not** launch an optical camera.
   - It invokes `LocationHelper.getDeviceLocation(context)`.
   - If a GPS fix is acquired, it constructs `gpsStr = "lat,lon"` and sends `viewModel.scanCheckpoint()`.
4. **Completion Dialog (`PatrolScreen.kt:291-304`):**
   - Guards cannot submit if `elapsedSeconds < 60` (triggers a client alert).
   - Guards cannot submit if `remainingCheckpoints > 0` (triggers a client alert).
   - Confirming submission calls `viewModel.finishPatrol()`.

### Location Helper (`app/src/main/java/com/example/util/LocationHelper.kt`)
- Calls `LocationServices.getFusedLocationProviderClient(context).getCurrentLocation(Priority.PRIORITY_HIGH_ACCURACY, cts.token)`.
- If immediate fix is null, falls back to `lastLocation`.
- Returns `Pair(latitude, longitude)` or `null`.
- **Shortcomings:** Discards `accuracy`, does not verify `isFromMockProvider`, does not inspect `elapsedRealtimeNanos`, and does not track speed or heading.

---

## 5. Current Patrol Workflow

### Detailed Analysis of Workflow Questions (1 to 20)

| # | Question | Code Answer | Source Reference |
|---|---|---|---|
| **1** | Who can create a patrol? | **Guard only.** `PatrolLogViewSet.perform_create` checks `user.role != UserRole.GUARD` and throws `PermissionDenied`. | [views.py:74-75](file:///c:/Projects/SGMIS_FIXED/sgmis_fixed/backend/apps/patrols/views.py#L74-L75) |
| **2** | Who can edit a patrol? | **Nobody.** `update()` and `partial_update()` raise `PermissionDenied("Patrol history is read-only after creation.")`. | [views.py:42-46](file:///c:/Projects/SGMIS_FIXED/sgmis_fixed/backend/apps/patrols/views.py#L42-L46) |
| **3** | Who can assign a patrol? | **Nobody.** There is no patrol assignment service or endpoint. | N/A |
| **4** | Can a supervisor assign a patrol? | **No.** Supervisors cannot create or assign patrols via the API or Android app. | [views.py:74](file:///c:/Projects/SGMIS_FIXED/sgmis_fixed/backend/apps/patrols/views.py#L74) |
| **5** | Can an admin assign a patrol? | **No.** Administrators cannot assign patrols via API. In Django Admin, an admin can manually insert a `PatrolLog` record. | [admin.py:10](file:///c:/Projects/SGMIS_FIXED/sgmis_fixed/backend/apps/patrols/admin.py#L10) |
| **6** | Can a guard create his own patrol? | **Yes.** Guards self-initiate patrols using `POST /api/patrols/logs/`. | [views.py:68-98](file:///c:/Projects/SGMIS_FIXED/sgmis_fixed/backend/apps/patrols/views.py#L68-L98) |
| **7** | Can a guard modify an assigned patrol? | **No.** Modifications are blocked; only scanning checkpoints and finishing are allowed. | [views.py:42](file:///c:/Projects/SGMIS_FIXED/sgmis_fixed/backend/apps/patrols/views.py#L42) |
| **8** | How does the guard know a patrol is assigned? | **He doesn't.** Patrols are not assigned; guards initiate them as part of their routine shift duties. | N/A |
| **9** | How does the guard start a patrol? | Guard opens `PatrolScreen.kt` while clocked in, and clicks "Initiate Station Patrol". | [PatrolScreen.kt:399](file:///c:/Projects/SGMIS_FIXED/sgmis_fixed/app/src/main/java/com/example/ui/screens/PatrolScreen.kt#L399) |
| **10** | How does the system determine the next checkpoint? | **It does not.** All checkpoints for the station are displayed in a list sorted by `order`. Guard can tap any item in any order. | [PatrolScreen.kt:439](file:///c:/Projects/SGMIS_FIXED/sgmis_fixed/app/src/main/java/com/example/ui/screens/PatrolScreen.kt#L439) |
| **11** | How is a checkpoint currently verified? | Guard clicks "Verify"; Android gets device GPS; backend checks if GPS is within 150m of checkpoint/station, or if QR/NFC tokens match. | [views.py:143-170](file:///c:/Projects/SGMIS_FIXED/sgmis_fixed/backend/apps/patrols/views.py#L143-L170) |
| **12** | Can checkpoints be skipped? | Checkpoints can be skipped during the patrol round, but **cannot be omitted from completion**. `finish_patrol` requires all active checkpoints to be scanned. | [views.py:227-239](file:///c:/Projects/SGMIS_FIXED/sgmis_fixed/backend/apps/patrols/views.py#L227-L239) |
| **13** | Can checkpoints be scanned out of sequence? | **Yes.** There is zero sequence checking on the backend or Android. Checkpoints 4, 2, 1, 3 can be scanned in that order. | [views.py:100-186](file:///c:/Projects/SGMIS_FIXED/sgmis_fixed/backend/apps/patrols/views.py#L100-L186) |
| **14** | How is patrol completion determined? | Guard clicks "Complete & Submit Patrol Log". Backend checks $\ge 60$s elapsed and all station checkpoints scanned. | [views.py:215-239](file:///c:/Projects/SGMIS_FIXED/sgmis_fixed/backend/apps/patrols/views.py#L215-L239) |
| **15** | Can a guard manually mark a patrol complete? | Yes, by invoking the `finish` endpoint, provided the minimum duration and scan count checks pass. | [views.py:189-248](file:///c:/Projects/SGMIS_FIXED/sgmis_fixed/backend/apps/patrols/views.py#L189-L248) |
| **16** | Can the same checkpoint be recorded repeatedly? | **Yes.** `CheckpointScan` allows multiple scans for the same checkpoint within one patrol. | [views.py:180-185](file:///c:/Projects/SGMIS_FIXED/sgmis_fixed/backend/apps/patrols/views.py#L180-L185) |
| **17** | Can a patrol be completed without reaching checkpoints? | **Only if checkpoints have default coords (0.0, 0.0)** or if false NFC/QR tokens are injected via the API. If coordinates are configured and no tokens are injected, GPS proximity is required. | [views.py:165](file:///c:/Projects/SGMIS_FIXED/sgmis_fixed/backend/apps/patrols/views.py#L165) |
| **18** | Can a patrol be completed from the guardroom? | **Yes, under three conditions:** (1) Checkpoints/station coords are `0.0, 0.0`; (2) Checkpoints are within 150m of guardroom; or (3) Guard uses mock GPS / API script. | [utils.py:33](file:///c:/Projects/SGMIS_FIXED/sgmis_fixed/backend/apps/stations/utils.py#L33) |
| **19** | What does supervisor see during active patrol? | Supervisor sees `SupervisorPatrolCard` on Dashboard with badge "● IN PROGRESS", guard name, start time, and scans count. | [DashboardScreen.kt:3713-3810](file:///c:/Projects/SGMIS_FIXED/sgmis_fixed/app/src/main/java/com/example/ui/screens/DashboardScreen.kt#L3713-L3810) |
| **20** | What does supervisor see after completion? | Supervisor sees `SupervisorPatrolCard` switch to "IDLE" with total patrol count; can view patrol log and scan count in `ReportsScreen`. | [ReportsScreen.kt:106-110](file:///c:/Projects/SGMIS_FIXED/sgmis_fixed/app/src/main/java/com/example/ui/screens/ReportsScreen.kt#L106-L110) |

---

## 6. Checkpoint Verification Audit

### Verification Mechanism Status Matrix

| Mechanism | Implementation Status | Current Code Reality |
|---|---|---|
| **GPS** | **PARTIALLY IMPLEMENTED** | Acquired via `LocationHelper.kt` on click; sent to backend as string `"lat,lon"`. |
| **Latitude / Longitude** | **IMPLEMENTED** | Extracted from string or float payload and parsed by backend. |
| **GPS Accuracy** | **NOT IMPLEMENTED** | Android does not extract `location.accuracy`; backend has no accuracy field. |
| **Distance Calculation** | **IMPLEMENTED** | `calculate_haversine_distance_meters()` in `apps/stations/utils.py:3-18`. |
| **Geofence Check** | **IMPLEMENTED** | `is_within_geofence()` checks radius (100m checkpoint, or station radius) + 50m buffer. |
| **NFC Verification** | **REFERENCED BUT UNUSED** | Backend accepts `len(nfc_uid) >= 4` (`views.py:152`). Android has zero NFC code. |
| **QR Verification** | **REFERENCED BUT UNUSED** | Backend matches `qr_token == checkpoint.qr_code`. Android has zero QR scanner code. |
| **Barcode (1D)** | **NOT IMPLEMENTED** | No code or schema exists. |
| **BLE (Bluetooth Beacons)** | **NOT IMPLEMENTED** | No BLE scanning or Beacon models exist. |
| **Manual Confirmation** | **NOT IMPLEMENTED** | Unverified button presses are rejected with HTTP 400 (`views.py:171-175`). |
| **Timestamp** | **PARTIALLY IMPLEMENTED** | Server stamps `scanned_at` on scan save; client hardware timestamp is not verified. |
| **Device Identity** | **NOT IMPLEMENTED** | Android IMEI, Android ID, or Secure Hardware Keystore ID is not recorded. |
| **Network Status** | **NOT IMPLEMENTED** | No network state verification or latency check during scan event. |
| **Offline Event Storage** | **NOT IMPLEMENTED** | No local queue or Room table for scans exists. Fails when offline. |

---

## 7. GPS and Geofencing Details

### Location Acquisition
- **Method:** `LocationHelper.getDeviceLocation(context)` in `app/src/main/java/com/example/util/LocationHelper.kt`.
- **Mode:** Single-shot on demand (`getCurrentLocation(Priority.PRIORITY_HIGH_ACCURACY)`). No background breadcrumbs or path tracking.
- **Payload:** Dispatched as comma-delimited string `gps_coords: "lat,lon"` in `CheckpointScanRequest`.

### Server-Side Validation Logic (`backend/apps/stations/utils.py`)
```python
def is_within_geofence(target_lat, target_lon, center_lat, center_lon, radius_meters, buffer_meters=50.0):
    if center_lat == 0.0 and center_lon == 0.0:
        return True  # Dev bypass!
    distance = calculate_haversine_distance_meters(target_lat, target_lon, center_lat, center_lon)
    return distance <= (radius_meters + buffer_meters)
```

### Vulnerability Analysis
1. **Unconfigured Bypass:** If center coordinates are `0.0, 0.0`, geofencing is bypassed entirely.
2. **Excessive Radius Buffer:** The effective radius is `100.0m + 50.0m = 150.0m`. In dense urban or multi-story buildings, 150 meters is large enough that a guard in the guardroom can scan checkpoints located across the entire campus.
3. **Spoofing Vulnerability:** Android mock locations (fake GPS apps) are not detected (`location.isFromMockProvider` or `location.isMock` are never inspected).

---

## 8. NFC Implementation Details

### Current State
- **Android NFC API Usage:** **NONE.** No imports of `android.nfc.NfcAdapter`, `android.nfc.Tag`, or `android.nfc.tech.IsoDep`.
- **Android Manifest:** Missing `<uses-permission android:name="android.permission.NFC" />` and `<uses-feature android:name="android.hardware.nfc" />`.
- **Backend Schema:** `Checkpoint` model has no `nfc_uid` field.
- **Critical Vulnerability (`backend/apps/patrols/views.py:152-154`):**
  ```python
  # 2. NFC UID verification
  elif nfc_uid and len(nfc_uid.strip()) >= 4:
      verified = True
      verification_method = "NFC_UID"
  ```
  Any HTTP client that submits `{"checkpoint": "<uuid>", "nfc_uid": "test"}` is unconditionally accepted by the backend as verified! The string is never matched against the database.

---

## 9. QR Implementation Details

### Current State
- **Optical Scanner:** **NONE.** No CameraX preview, no SurfaceView, no barcode analyzer.
- **Dependencies:** CameraX libraries are declared in `gradle/libs.versions.toml`, but **not linked** in `app/build.gradle.kts`. Neither Google ML Kit Barcode Scanning nor ZXing exists in dependencies.
- **Android Manifest:** Missing `<uses-permission android:name="android.permission.CAMERA" />`.
- **Backend Storage:** `Checkpoint.qr_code` stores a static plaintext token (e.g. `"CP-ST1-001-QR"`).
- **Vulnerability:** The QR token is displayed in plaintext on the Android UI (`PatrolScreen.kt:551`: `Text("Code: ${cp.code} • QR: ${cp.qrCode}")`). Anyone can copy this string and submit it via REST API.

---

## 10. Offline Operation Audit

### Can a guard currently complete any patrol activity without Internet?
**ABSOLUTELY NOT.**

### Proof from Code
1. **Local Database (`app/src/main/java/com/example/data/local/`):**
   - `SgmisDatabase` registers `@Database(entities = [CachedShiftEntity::class, CachedOBEntryEntity::class, CachedIncidentEntity::class, CachedStationEntity::class, CachedCheckpointEntity::class], version = 3)`.
   - There is **no** `CachedPatrolLogEntity` and **no** `CachedCheckpointScanEntity`.
2. **Repository Network Dispatch (`app/src/main/java/com/example/data/repository/SgmisRepository.kt:658-699`):**
   - `startPatrol()`, `scanCheckpoint()`, and `finishPatrol()` make direct, synchronous calls to `api.startPatrol()`, `api.scanCheckpoint()`, and `api.finishPatrol()`.
   - On network failure, each method immediately catches `Exception` and returns `Result.failure(sanitizeException(e))`.
   - No offline queue, no disk serialization, no WorkManager sync job exists.
3. **Outcome:** If connectivity drops, tapping "Verify" or "Complete Patrol" displays a red error banner on the screen. The event is permanently lost unless retried with live internet.

---

## 11. Timing and Sequence Controls

### Timing Evaluation
- **Patrol Start Time:** Yes (`start_time = auto_now_add`).
- **Patrol Deadline:** None. A patrol can stay `IN_PROGRESS` indefinitely.
- **Minimum Duration:** Yes, 60 seconds enforced server-side (`finish_patrol` checks `(now - start_time).total_seconds() < 60`).
- **Checkpoint Minimum Interval:** None.
- **Expected Travel Time:** None.
- **Route Duration:** None.
- **Patrol Expiry:** None.

### Sequence Test Case
**Scenario:**
- Checkpoint A scanned at `21:00:00`
- Checkpoint B scanned at `21:00:05`
- Checkpoint C scanned at `21:00:12`
- Checkpoint D scanned at `21:00:20`

**Current System Behavior:**
- The backend **accepts all 4 checkpoint scans**.
- If the guard waits until `21:01:01` (61 seconds after starting), the backend **accepts the completion**.
- The system has **no mechanism to detect or prevent** this physically impossible speed.

---

## 12. Anti-Cheating Assessment

| Scenario | Classification | Code Rationale |
|---|---|---|
| **A. Guard remains in guardroom** | **PARTIALLY PROTECTED** | Protected if checkpoints have valid GPS coordinates $>150\text{m}$ away. Unprotected if coordinates are `(0.0, 0.0)` (bypass) or within 150m of guardroom. |
| **B. Guard photographs QR code** | **NOT PROTECTED** | QR tokens are static strings displayed in plaintext on the UI; optical scanner not even enforced; easily replayed. |
| **C. Guard manually completes all remaining checkpoints** | **PROTECTED** | Backend requires proof for each checkpoint. `finish_patrol` strictly enforces that all active station checkpoints have a verified scan. |
| **D. Guard completes checkpoints in impossible sequence** | **NOT PROTECTED** | Checkpoints have no sequential state machine. Checkpoints can be scanned in any random order without rejection. |
| **E. Guard completes route faster than physically possible** | **NOT PROTECTED** | Only the total 60-second duration is checked. Inter-checkpoint travel intervals are not validated. |
| **F. Guard changes GPS / uses mock location** | **NOT PROTECTED** | Android does not check `location.isMock`. Spoofed coordinates are accepted by both app and backend. |
| **G. Guard turns off GPS during patrol** | **PARTIALLY PROTECTED** | `LocationHelper` returns `null` $\rightarrow$ scan request sends empty GPS $\rightarrow$ rejected by backend with HTTP 400 (unless fake QR/NFC token supplied). |
| **H. Guard loses Internet during patrol** | **NOT PROTECTED** | Scan fails immediately. Guard cannot record checkpoints or finish the patrol while offline. |
| **I. Guard starts patrol and hands phone to another person** | **NOT PROTECTED** | No biometrics, session re-authentication, or selfie verification during patrol. |
| **J. Guard repeatedly scans the same checkpoint** | **PARTIALLY PROTECTED** | Duplicate scans are stored without error. However, `finish_patrol` verifies distinct checkpoint IDs covering all active station checkpoints. |

---

## 13. Supervisor Capabilities

| Action | Classification | Code Reality |
|---|---|---|
| Create patrol | **NOT AVAILABLE** | Only guards can create patrols (`views.py:74`). |
| Edit patrol | **NOT AVAILABLE** | Patrol logs are immutable (`views.py:43`). |
| Assign guard | **NOT AVAILABLE** | No patrol assignment feature exists. |
| Change guard | **NOT AVAILABLE** | No reassignment feature exists. |
| Select station | **NOT AVAILABLE** | Supervisors cannot create patrols. |
| Create route | **NOT AVAILABLE** | No route entity exists. |
| Edit route | **NOT AVAILABLE** | No route entity exists. |
| Create checkpoint | **ADMIN / API ONLY** | `CheckpointViewSet` allows `IsSupervisorOrAdmin` to POST checkpoints via API. No Android UI screen exists. |
| Edit checkpoint | **ADMIN / API ONLY** | `CheckpointViewSet` allows `IsSupervisorOrAdmin` to PUT/PATCH checkpoints via API. No Android UI screen exists. |
| Reorder checkpoints | **ADMIN / API ONLY** | Modifiable via `order` field in API or Django Admin. |
| Define patrol window | **NOT AVAILABLE** | No window fields exist in models. |
| Define patrol duration | **NOT AVAILABLE** | Hardcoded to 60s in backend. |
| Define checkpoint timing | **NOT AVAILABLE** | No timing fields exist on Checkpoint model. |
| Activate/deactivate patrol | **NOT AVAILABLE** | Patrol status is guard-driven. |
| Monitor active patrol | **CURRENTLY AVAILABLE** | `SupervisorPatrolCard` on Dashboard displays live guard and scan count. |
| View completed patrol | **CURRENTLY AVAILABLE** | Viewable in `ReportsScreen` under "PATROLS" category. |
| View failed patrol | **NOT AVAILABLE** | No failed patrol status exists. |
| View anomalous patrol | **NOT AVAILABLE** | No anomaly detection logic exists. |
| Receive patrol alerts | **NOT AVAILABLE** | No push notifications or alert events for missed patrols. |

---

## 14. Administrator Capabilities

- **Django Admin (`backend/apps/patrols/admin.py`):**
  - Full CRUD on `Checkpoint`, `PatrolLog`, and `CheckpointScan`.
  - Can search by guard, station, checkpoint code, and filter by status and date.
- **REST API:**
  - Can create and modify `Checkpoint` objects via `/api/patrols/checkpoints/`.
  - Can query `/api/patrols/logs/?station=<uuid>` to audit any station across the company.
  - **Cannot** execute patrols via API (`perform_create` checks `user.role == UserRole.GUARD`).
- **Android App:**
  - Admin view in `PatrolScreen.kt` is read-only (monitoring banner displayed).
  - Admin can inspect company-wide patrol history in `ReportsScreen.kt`.

---

## 15. Station Isolation Audit

- **Guard Isolation:**
  - `PatrolLogViewSet.get_queryset()`: Guards strictly receive `qs.filter(guard=user)`.
  - `PatrolLogViewSet.perform_create()`: Rejects proxy creation for another guard. Automatically binds patrol to guard's duty station.
  - `PatrolLogViewSet.scan_checkpoint()`: Verifies checkpoint belongs to the patrol's assigned station (`get_object_or_404(Checkpoint, id=checkpoint_id, station=patrol.station)`).
  - Cross-station execution is strictly blocked.
- **Supervisor Isolation:**
  - `PatrolLogViewSet.get_queryset()`: Supervisors receive `qs.filter(station=user.station)`. If `user.station` is null, returns `qs.none()`.
  - Supervisors cannot view patrol records belonging to foreign stations.
- **Isolation Leak Assessment:** **ZERO LEAKAGE DETECTED.** Station isolation is strictly enforced at the ORM queryset level.

---

## 16. Existing Tests

### Automated Test Suite Inventory

| Test File | Test Method | What It Verifies | Current Result |
|---|---|---|---|
| `backend/tests/test_duty_conflicts_and_patrol_integrity.py` | `test_13_patrol_cannot_instantly_complete` | Verifies finish rejects if duration $< 60$ seconds. | **PASSED** |
| `backend/tests/test_duty_conflicts_and_patrol_integrity.py` | `test_14_patrol_cannot_be_completed_twice` | Verifies duplicate finish call is rejected with HTTP 400. | **PASSED** |
| `backend/tests/test_duty_conflicts_and_patrol_integrity.py` | `test_15_patrol_completion_validates_correct_guard` | Verifies a foreign guard cannot finish another guard's patrol. | **PASSED** |
| `backend/tests/test_duty_conflicts_and_patrol_integrity.py` | `test_16_patrol_completion_verifies_required_station_checkpoints` | Verifies finish rejects if not all active station checkpoints were scanned. | **PASSED** |
| `backend/tests/test_sgmis_api.py` | `test_patrol_start_auto_station_assignment` | Verifies guard starting patrol gets assigned station bound automatically. | **PASSED** |
| `backend/tests/test_sgmis_api.py` | `test_patrol_start_unassigned_guard_rejected` | Verifies guard with no station cannot start a patrol. | **PASSED** |
| `backend/tests/test_sgmis_api.py` | `test_regression_patrol_guard_cannot_override_station` | Verifies guard cannot override patrol station to a different station ID. | **PASSED** |
| `backend/tests/test_sgmis_api.py` | `test_supervisor_restrictions_ob_incident_patrol` | Verifies supervisors cannot initiate patrols. | **PASSED** |
| `backend/tests/test_sgmis_api.py` | `test_guard_patrol_termination_guard_only` | Verifies only guards can terminate active patrols. | **PASSED** |
| `app/src/test/java/com/example/RoleAndDutyStateTest.kt` | `RoleRouter` navigation route tests | Verifies `NavRoutes.PATROL` is accessible to Guard only when On Duty, and blocked for Supervisor. | **PASSED** |

*All 9 backend tests executed and confirmed passing in 0.203s.*

---

## 17. Missing Tests

The following critical security and operational behaviors currently have **NO** automated tests:
1. **NFC UID arbitrary string acceptance:** No test verifying whether invalid/arbitrary NFC UIDs are rejected (they are currently accepted).
2. **Zero-coordinate geofence bypass:** No test verifying that unconfigured station coordinates `(0.0, 0.0)` reject or flag scans.
3. **Out-of-sequence checkpoint scanning:** No test verifying sequential ordering enforcement.
4. **Zero-interval rapid checkpoint scanning:** No test verifying minimum travel time between checkpoints.
5. **GPS mock provider detection:** No Android test verifying mock location rejection.
6. **QR token cryptographic signing:** No test verifying that photographed QR codes are rejected.
7. **Offline scan queue persistence:** No test verifying local SQLite serialization when network drops.
8. **Offline scan synchronization conflict resolution:** No test verifying bulk sync with server timestamp reconciliation.
9. **Supervisor patrol approval persistence:** No test verifying supervisor approval saves to DB (currently a no-op).
10. **Patrol expiration / deadline timeout:** No test verifying automatic flagging of abandoned patrols.
11. **GPS accuracy threshold enforcement:** No test verifying scans with low accuracy ($>30\text{m}$) are rejected or flagged.
12. **Duplicate scan rate limiting:** No test verifying multiple rapid scans of the same checkpoint are debounced.

---

## 18. Security Risks Register

| # | Vulnerability | Severity | Impact | Code Path |
|---|---|---|---|---|
| **SEC-01** | Arbitrary NFC UID verification bypass | **CRITICAL** | Any 4-character string sent via API passes verification without matching a registered tag. | `backend/apps/patrols/views.py:152-154` |
| **SEC-02** | Geofence bypass on unconfigured (0,0) coords | **HIGH** | If station coords are not set, guard can complete patrols from anywhere in the world. | `backend/apps/stations/utils.py:33-34` |
| **SEC-03** | Lack of sequential route enforcement | **HIGH** | Guards can scan checkpoints in reverse or random order, skipping intended security sweeps. | `backend/apps/patrols/views.py:100-186` |
| **SEC-04** | Lack of checkpoint velocity limits | **HIGH** | Guards can scan multiple distant checkpoints in seconds without physical travel. | `backend/apps/patrols/views.py:100-186` |
| **SEC-05** | Total failure under offline conditions | **HIGH** | Security guards cannot record patrols in basements or cellular dead zones. | `app/src/main/java/com/example/data/repository/SgmisRepository.kt:673-685` |
| **SEC-06** | Static QR token displayed in UI | **MEDIUM** | QR string is visible on screen, enabling manual API replay without physical presence. | `app/src/main/java/com/example/ui/screens/PatrolScreen.kt:551` |
| **SEC-07** | Absence of GPS mock provider detection | **MEDIUM** | Spoofed GPS applications on Android devices are accepted by client and server. | `app/src/main/java/com/example/util/LocationHelper.kt:15-50` |
| **SEC-08** | Supervisor approval endpoint is a no-op | **LOW** | Supervisor approval returns HTTP 200 without saving audit records to the database. | `backend/apps/patrols/views.py:251-265` |

---

## 19. Existing Reusable Components

The following existing components are robust and should be **preserved and reused**:
1. **Database Schema:** `Checkpoint`, `PatrolLog`, `CheckpointScan` models provide clean foreign key relationships and metadata.
2. **Geospatial Utilities:** `calculate_haversine_distance_meters()` is mathematically sound and fast (pure Python standard library).
3. **Station Isolation:** Queryset filtering in `PatrolLogViewSet` reliably isolates records between guards, supervisors, and stations.
4. **Guard Ownership & Lockout:** Off-duty lockout and proxy prevention in `perform_create()` and `finish_patrol()`.
5. **Station Checkpoint Completeness:** Logic in `finish_patrol()` requiring all active station checkpoints to be scanned before completion.
6. **Android UI Foundation:** `PatrolScreen.kt` provides clear inspection progress, active patrol state banners, elapsed timer, and checkpoint cards.
7. **Local Room Database:** `SgmisDatabase` and `CachedCheckpointEntity` provide an existing foundation for extending offline storage.

---

## 20. Proposed Future Architecture (Proposal Only)

To achieve true physical presence verification without falsely accusing guards during GPS fluctuations, the future architecture should adopt a **Multi-Factor Checkpoint Evidence Pipeline**:

```
[ PHYSICAL NFC TAG / DYNAMIC QR ]  +  [ FILTERED GPS & ACCURACY ]  +  [ HARDWARE MONOTONIC TIME ]
                                              │
                                              ▼
                             [ OFFLINE ROOM ENCRYPTED EVENT QUEUE ]
                                              │ (WorkManager Sync)
                                              ▼
                             [ SERVER-SIDE MULTI-FACTOR VALIDATION ]
                             ├── 1. Cryptographic Tag / Nonce Match
                             ├── 2. Sequential Order Validation (1 -> 2 -> 3)
                             ├── 3. Minimum Realistic Travel Time Check
                             ├── 4. Spatial Proximity & Drift Buffer Check
                             └── 5. Anomaly Scoring (Flag vs Reject)
```

### Proposed Design Principles
1. **NFC Primary / QR Fallback:** Physical NTAG213/215 NFC chips mounted at checkpoints. Android reads hardware UID via `NfcAdapter`. If phone lacks NFC, use dynamic time-salted QR codes scanned via ML Kit CameraX.
2. **Hardware Monotonic Timestamps:** Record `SystemClock.elapsedRealtime()` alongside wall-clock time to prevent device clock tampering while offline.
3. **State-Machine Sequential Routes:** Define routes as ordered checkpoints with minimum and maximum transit times between pairs (e.g. Checkpoint A $\rightarrow$ B: minimum 90 seconds, maximum 15 minutes).
4. **Offline Resilient Queue:** Store scans locally in Room (`CachedPatrolEvent`). Background `WorkManager` syncs batched scans upon network restoration with cryptographic client signatures.
5. **Soft Anomaly Flagging:** Never strand a guard in an emergency: if GPS has high drift ($>30\text{m}$) indoors but NFC UID matches, accept the scan with an `ANOMALOUS_GPS` audit flag for supervisor review.

---

## 21. Implementation Risks

1. **Indoor GPS Attenuation:** Deep indoor basements and concrete stairwells lose GPS signal completely ($>100\text{m}$ accuracy error or null fix). Requiring strict GPS proximity without NFC fallback will cause false rejections.
2. **Device Hardware Disparity:** Low-end Android patrol phones may lack NFC hardware. The system must support both NFC and camera QR without creating security loopholes.
3. **Clock Skew in Offline Scans:** Guards changing phone settings or traveling across cell boundaries could manipulate event timing unless anchored to monotonic hardware elapsed time.
4. **Offline Sync Replay Conflicts:** Duplicate network delivery during poor connectivity could create duplicate scan records unless idempotent UUIDs are enforced.

---

## 22. Recommended Implementation Order

### Phase 1: Backend Security Hardening (Immediate)
- Fix NFC UID bypass: require registered NFC tag UIDs on `Checkpoint` model.
- Disallow unconfigured `(0.0, 0.0)` geofence bypass (require explicit coordinates).
- Add inter-checkpoint minimum travel interval validation.
- Make supervisor approval persistent on `PatrolLog`.

### Phase 2: Route & Sequence Domain Models
- Introduce optional `PatrolRoute` or enforce strict sequential ordering on `Checkpoint.order`.
- Add minimum/maximum transit time windows between checkpoints.

### Phase 3: Android Hardware Integration (CameraX & NFC)
- Add NFC permission and Android `NfcAdapter` reader mode.
- Integrate Google ML Kit Barcode Scanning with CameraX.
- Capture GPS accuracy and mock provider flags.

### Phase 4: Offline Queue & Sync Engine
- Create `CachedPatrolLogEntity` and `CachedCheckpointScanEntity` in Room.
- Implement offline sync queue with WorkManager and idempotent submission.

### Phase 5: Supervisor Real-Time Monitoring & Alerts
- Provide live checkpoint progression timeline on supervisor dashboard.
- Generate alerts for delayed, skipped, or anomalous checkpoint visits.

---

## PATROL AUDIT VERDICT

### A. What can safely be reused?
1. Existing database schema relationships: `Checkpoint`, `PatrolLog`, `CheckpointScan`.
2. Station geofencing mathematics (`calculate_haversine_distance_meters`).
3. Guard role restrictions, off-duty lockout, and proxy action prevention.
4. Station isolation query filtering for guards, supervisors, and admins.
5. Station checkpoint completion verification in `finish_patrol()`.
6. Compose UI layout, timer, and checkpoint card component structures.

### B. What must be extended?
1. `Checkpoint`: Add registered `nfc_uid`, transit interval, and custom geofence radius.
2. `PatrolLog`: Add route reference, assigned supervisor, scheduled window, approval metadata, and anomaly flags.
3. `CheckpointScan`: Add verification method enum, device UID, monotonic timestamp, and GPS accuracy.
4. Android Room database: Add offline patrol logs and scan event queue tables.
5. Android scanner: Add real optical CameraX QR scanner and hardware NFC reader.

### C. What must NOT be replaced?
1. Do not replace the existing station-based multi-tenant security architecture.
2. Do not replace the DRF permission classes (`IsGuard`, `IsSupervisorOrAdmin`).
3. Do not replace the requirement that all active station checkpoints must be verified before completion.
4. Do not replace the authoritative backend validation model with client-side-only checks.

### D. What are the highest-risk existing weaknesses?
1. The backend acceptance of any arbitrary 4-character NFC UID without DB verification.
2. The automatic geofence pass for `(0.0, 0.0)` coordinates.
3. The lack of optical camera or hardware NFC in the Android application (button click sends GPS only).
4. The lack of sequential route enforcement and inter-checkpoint travel time checks.
5. The complete absence of offline patrol recording and synchronization.

### E. What should be implemented FIRST?
**Backend validation hardening:** Close the arbitrary NFC UID bypass, eliminate the `(0.0, 0.0)` bypass, add minimum inter-checkpoint travel intervals, and enforce registered tag verification.

### F. What should be implemented LAST?
**Advanced supervisor live telemetry and mapping:** Live map tracking, historical playback breadcrumbs, and anomaly trend reports should be built only after verified physical data collection and offline sync are operational.

### G. What should remain under supervisor control?
1. Viewing real-time patrol telemetry, active guards, and completed reports.
2. Reviewing and authoritatively approving/rejecting completed patrol logs.
3. Reviewing flagged anomalies (e.g., GPS drift during indoor NFC scans).
4. Assigning scheduled patrol windows and specific guards to routes.

### H. What should remain under administrator control?
1. Defining stations, checkpoints, registered NFC hardware tags, and master coordinates.
2. Deleting or archiving historical audit records (with company-wide compliance logging).
3. System-wide configuration of security thresholds (minimum patrol duration, GPS radius tolerances).

### I. What should remain guard-only?
1. Physical execution of patrols (starting a round, scanning checkpoints, submitting de-brief notes).
2. Physical interaction with NFC tags and QR codes on the assigned station perimeter.
3. Reporting immediate physical safety hazards or checkpoint notes during inspection.

---

## PHASE 1 IMPLEMENTATION

**Implementation Date:** 2026-10-05  
**Scope:** Backend Patrol Verification Hardening (Phase 1)  
**Status:** COMPLETE (All 370 Backend Tests Passing)

### 1. What Was Changed
1. **NFC UID Bypass Eliminated:** Replaced the vulnerable `len(nfc_uid) >= 4` string-length check with strict database-backed verification against registered checkpoint NFC tags. Unregistered tags, cross-checkpoint tags, cross-station tags, and unconfigured checkpoints are rejected with HTTP 400.
2. **Zero-Coordinate (0.0, 0.0) GPS Bypass Removed:** Fixed `apps/stations/utils.py:is_within_geofence()` so that missing or default `(0.0, 0.0)` coordinates fail safely (returns `False`). In `apps/patrols/views.py:scan_checkpoint()`, unconfigured checkpoint coordinates fail safely (`LOCATION_NOT_CONFIGURED`), requiring registered physical NFC or QR tokens.
3. **Server-Side Checkpoint Sequence Enforcement:** Checkpoint scan requests now validate route order on the server based on `Checkpoint.order`. Out-of-sequence scans (e.g. 1 → 3, 1 → 2 → 5) and duplicate scans of previously verified checkpoints (e.g. 1 → 2 → 2) are rejected with HTTP 400.
4. **Inter-Checkpoint Minimum Travel Interval:** Implemented `min_interval_seconds` on `Checkpoint`. The server enforces a configurable minimum transit time between sequential checkpoint verifications based on authoritative server timestamps (`timezone.now()`). Physically impossible transit speeds are rejected with HTTP 400.
5. **Persistent Supervisor Approval:** Updated `PatrolLog` to persistently store `is_approved`, `approved_by` (FK to User), and `approved_at` (DateTimeField). Guard self-approvals and approvals on non-completed patrols are strictly rejected.
6. **Station Isolation Hardening:** Direct checkpoint ID lookups explicitly enforce `checkpoint.station_id == patrol.station_id` (returns HTTP 403 Forbidden for foreign station checkpoints).

### 2. Files Changed
- `backend/apps/patrols/models.py`: Added `nfc_uid` and `min_interval_seconds` to `Checkpoint`; added `is_approved`, `approved_by`, `approved_at` to `PatrolLog`.
- `backend/apps/patrols/views.py`: Hardened `scan_checkpoint` with NFC tag matching, zero-coordinate safe rejection, sequence verification, minimum transit interval checks, and station isolation; hardened `approve_patrol` with persistent database saving and conflict of interest checks.
- `backend/apps/patrols/serializers.py`: Updated `CheckpointSerializer` and `PatrolLogSerializer` to expose the new security and approval audit fields.
- `backend/apps/patrols/admin.py`: Updated `CheckpointAdmin` and `PatrolLogAdmin` with list displays, filters, and search fields for the new columns.
- `backend/apps/stations/utils.py`: Removed `(0.0, 0.0)` bypass from `is_within_geofence()`.
- `backend/apps/patrols/migrations/0002_checkpoint_min_interval_seconds_checkpoint_nfc_uid_and_more.py`: Django migration for schema changes.
- `backend/tests/test_patrol_security_hardening_phase1.py`: Dedicated test suite verifying criteria A through S (19 tests).

### 3. Migrations Created
- `backend/apps/patrols/migrations/0002_checkpoint_min_interval_seconds_checkpoint_nfc_uid_and_more.py`:
  - `Checkpoint.min_interval_seconds`: `PositiveIntegerField(default=0)`
  - `Checkpoint.nfc_uid`: `CharField(max_length=64, blank=True, default="")`
  - `PatrolLog.is_approved`: `BooleanField(default=False)`
  - `PatrolLog.approved_by`: `ForeignKey(settings.AUTH_USER_MODEL, null=True, blank=True, on_delete=SET_NULL)`
  - `PatrolLog.approved_at`: `DateTimeField(null=True, blank=True)`

### 4. Security Rules Introduced
- `RULE-SEC-NFC-01`: Scanned NFC UID must match `checkpoint.nfc_uid` registered in database; format must be $\ge 4$ characters.
- `RULE-SEC-NFC-02`: Checkpoints without registered NFC tags report `NFC_NOT_CONFIGURED` and reject NFC scans.
- `RULE-SEC-GPS-01`: Station and checkpoint coordinates of `(0.0, 0.0)` fail safely (`LOCATION_NOT_CONFIGURED`).
- `RULE-SEC-GPS-02`: Low GPS accuracy fixes ($>100\text{m}$) are rejected.
- `RULE-SEC-SEQ-01`: Checkpoints must be scanned strictly in ascending sequence determined server-side from `Checkpoint.order`.
- `RULE-SEC-SEQ-02`: Repeated/duplicate scans of the same checkpoint within an active patrol are rejected.
- `RULE-SEC-TIME-01`: Consecutive checkpoint scans must satisfy `checkpoint.min_interval_seconds` using server-authoritative timestamps.
- `RULE-SEC-TIME-02`: Minimum total patrol duration (60s) remains mandatory for overall patrol completion.
- `RULE-SEC-APPR-01`: Supervisor approval requires `patrol.status == 'COMPLETED'`, records approving supervisor and timestamp, and blocks guard self-approvals.

### 5. Tests Added
A dedicated automated test suite (`backend/tests/test_patrol_security_hardening_phase1.py`) was created, covering all 19 required criteria:
- `test_a_valid_registered_nfc_uid_accepted`: Verifies registered NFC UID creates verified scan with `[NFC_UID]`.
- `test_b_arbitrary_4_character_nfc_uid_rejected`: Verifies unregistered 4-character NFC UID is rejected (HTTP 400).
- `test_c_nfc_uid_belonging_to_another_checkpoint_rejected`: Verifies tag UID belonging to another checkpoint is rejected (HTTP 400).
- `test_d_nfc_uid_belonging_to_another_station_rejected`: Verifies tag UID belonging to another station is rejected (HTTP 400).
- `test_e_missing_nfc_configuration_does_not_automatically_pass`: Verifies unconfigured NFC returns `NFC_NOT_CONFIGURED` (HTTP 400).
- `test_f_checkpoint_with_zero_zero_coordinates_does_not_pass`: Verifies `(0.0, 0.0)` coordinates fail safely with `LOCATION_NOT_CONFIGURED` (HTTP 400).
- `test_g_invalid_or_missing_gps_coordinates_fail_safely`: Verifies corrupt/missing coordinates are rejected (HTTP 400).
- `test_h_valid_gps_inside_checkpoint_radius_still_works`: Verifies proximity fix creates verified scan with `[GPS_PROXIMITY]`.
- `test_i_checkpoint_sequence_1_2_3_works`: Verifies in-sequence route scanning (1 → 2 → 3) completes successfully.
- `test_j_checkpoint_sequence_1_3_is_rejected`: Verifies out-of-sequence skip (1 → 3) is rejected (HTTP 400).
- `test_k_checkpoint_sequence_1_2_2_rejected_duplicate`: Verifies duplicate scan of same checkpoint is rejected (HTTP 400).
- `test_l_impossible_inter_checkpoint_travel_time_rejected`: Verifies transit faster than `min_interval_seconds` is rejected (HTTP 400).
- `test_m_valid_realistic_travel_time_accepted`: Verifies transit satisfying `min_interval_seconds` is accepted (HTTP 201).
- `test_n_existing_minimum_total_patrol_duration_still_works`: Verifies 60s total patrol duration check remains enforced.
- `test_o_patrol_cannot_be_completed_twice`: Verifies duplicate finish is rejected (HTTP 400).
- `test_p_wrong_guard_cannot_submit_checkpoint`: Verifies proxy scan by another guard is rejected (HTTP 403/404).
- `test_q_foreign_station_checkpoint_cannot_be_submitted`: Verifies cross-station checkpoint submission is rejected (HTTP 403).
- `test_r_supervisor_approval_is_persisted`: Verifies supervisor approval persists `is_approved`, `approved_by`, `approved_at` in DB.
- `test_s_guard_cannot_approve_his_own_patrol`: Verifies conflict of interest check blocks guard self-approval (HTTP 403).

### 6. Test Suite Results
- **Phase 1 Test Suite:** 19 of 19 tests passed (0.638s).
- **Patrol Combined Tests:** 47 of 47 tests passed (1.254s).
- **Full Backend Test Suite:** 370 of 370 tests passed (36.268s).
- **Tests Failed:** 0 failed.

### 7. Known Limitations
- Optical camera QR scanning and hardware Android NFC reading are not yet implemented in the mobile client (planned for Phases 2 & 3).
- Offline patrol event queuing and Room-to-server sync are not yet implemented (planned for Phase 4).
- Patrol routes currently reuse `Checkpoint.order` grouped by station; dedicated reusable route templates (`PatrolRoute`) remain a future extension.
