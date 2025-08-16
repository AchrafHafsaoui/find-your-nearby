# Fyn — Find Your Nearby

Peer-to-peer social exchange over Bluetooth LE.  
Each phone **advertises a rotating ID**, and when you **tap a nearby device** in the scanner, your phone establishes a **GATT connection**, performs a **secure handshake**, and fetches that person’s **social aliases** (e.g. Facebook, Instagram, LinkedIn... etc).

---

## Features

- **Rotating RID advertiser** (no PII in the air).
- **Tap-to-fetch** card via BLE GATT (no pairing required).
- **End-to-end handshake**: ECDH + HKDF + AES-GCM envelope.
- **Foreground service** keeps advertising + server alive.
- **Settings sheet** to edit your aliases (persisted locally).
- **List rows with logos + usernames** for fetched peers.

---

## How it works

**Advertise** and **Serve:** with:

| Characteristic | UUID                              | Properties |
|---|---|---|
| CAPS           | `0000be01-0000-1000-8000-00805f9b34fb` | READ       |
| EPH_PUB        | `0000be02-0000-1000-8000-00805f9b34fb` | READ, WRITE |
| ENVELOPE       | `0000be03-0000-1000-8000-00805f9b34fb` | READ, WRITE |
| STATUS         | `0000be04-0000-1000-8000-00805f9b34fb` | READ       |

**Client handshake (on tap):**

1. Connect (LE), **request MTU 185**, discover services.
2. **Write** client ECDH public key to `EPH_PUB`.
3. **Read** server ECDH public key from `EPH_PUB`.
4. Derive session key = `HKDF-SHA256(ECDH)`.
5. **Write** encrypted “request” envelope to `ENVELOPE`.
6. **Read** encrypted “card” envelope from `ENVELOPE`, **decrypt**, parse `ProfileCard`.
7. Update UI row: **service icon + handle** per alias.

Crypto: ephemeral ECDH (P-256), HKDF-SHA256, AES-GCM (12-byte nonce).

---

## Requirements

- Two Android devices with BLE (Android 8.0+ recommended)
- Bluetooth ON on both devices; Location permission required for BLE scan on many devices

---

## Project Structure

- `core/`
    - `Constants.kt` — UUIDs, timeouts, thresholds
    - `Crypto.kt` — ECDH(HKDF) + AES-GCM helpers
    - `ProfileCard.kt` — displayName + aliases map
    - `RotatingId.kt` — Rotating ID generator
    - `AliasesStore.kt` — local persistence for aliases
- `ble/`
    - `NearbyForegroundService.kt` — starts GattServer + RidAdvertiser
    - `RidAdvertiser.kt` — advertises `SERVICE_UUID`
    - `GattServer.kt` — publishes 0xBEEF service + envelope R/W
    - `GattClient.kt` — MTU→discover→RW handshake & decrypt
- `ui/`
    - `ScannerActivity.kt` — scan list; tap-to-fetch; render icons + handles
    - `AliasesSheet.kt` — bottom sheet to edit aliases
    - `MainActivity.kt` — app bootstrap (starts foreground service)