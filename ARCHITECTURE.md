# Architecture

## High-level shape

```
                 ┌──────────────────────────────┐
                 │  Android telephony framework │
                 │  (ImsManager, ImsPhone,       │
                 │   ImsPhoneCallTracker, …)     │
                 └──────────────┬───────────────┘
                                │  AIDL (ImsService surface)
                                ▼
       ┌─────────────────────────────────────────────────┐
       │                HamelinPortsImsService                 │
       │                                                  │
       │   ┌────────────────┐    ┌─────────────────┐    │
       │   │ MmTelFeature    │    │ Registration    │    │
       │   │  + CallSession  │    │ Controller      │    │
       │   │  + IncomingCall │    │  (REGISTER, AKA,│    │
       │   │  + Sms          │    │   IPsec, refresh│    │
       │   └────────┬────────┘    │   + 5xx retry)  │    │
       │            │             └────┬────────────┘    │
       │            │                  │                  │
       │   ┌────────▼──────────────────▼─────────┐       │
       │   │ jni/hamelinports_ims_jni.cpp              │       │
       │   │  → reSIProcate Stack + DUM           │       │
       │   │  → AKA / IPsec helpers               │       │
       │   └──────────────────────────────────────┘       │
       │                                                  │
       │            android.telephony.imsmedia            │
       │             (RTP/RTCP for audio + video)         │
       └────────────────────────┬─────────────────────────┘
                                │ IImsModemBridge AIDL
                                ▼
            ┌─────────────────────────────────┐
            │  Per-device modem-bridge service │
            │  (vendor-specific, or absent →   │
            │   in-process no-op)              │
            └────────────┬─────────────────────┘
                         │  vendor-specific binder
                         ▼
                ┌────────────────────┐
                │  Vendor RIL / modem │
                └────────────────────┘
```

## Design tenets

1. **Carrier-agnostic core.** No carrier-specific identifiers in
   source — Telefónica DE was the bring-up target, but every
   protocol decision is justified by RFC / 3GPP spec, not by
   matching one P-CSCF's quirks.
2. **Modem-agnostic core.** All vendor-proprietary modem
   co-ordination lives behind the `IImsModemBridge` AIDL in a separate
   per-device APK. The IMS service core can be reused on any device
   that ships either a bridge implementation or none (AOSP-conformant
   modems).
3. **No vendor blob runtime dependencies.** `HamelinPortsImsService`
   itself loads no proprietary libraries — REGISTER, calls, SMS, and
   media all run on AOSP framework APIs + reSIProcate (Apache 2.0).

## Components

### `aidl/` — `hamelinports-ims-aidl` java_library

Two AIDL interfaces, compiled to a Java stub library that is
`static_libs`-referenced by both HamelinPortsImsService and any per-device
bridge implementation.

- `IImsModemBridge` — one-way calls from HamelinPortsImsService to the
  bridge: `start`, `stop`, `sendRegistration`, `sendPreference`,
  `register/unregisterCallback`. Constants `RAT_LTE` / `RAT_NR` /
  `RAT_IWLAN` are first-class on the interface.
- `IImsModemBridgeCallback` — bridge → HamelinPortsImsService for
  vendor-specific events that don't fit any AOSP AIDL.

### `src/org/hamelinports/ims/`

- `HamelinPortsImsService.java` — the AIDL-discoverable `ImsService` that
  the framework's `ImsResolver` binds.
- `HamelinPortsMmTelFeature.java` — capability advertisement, MO/MT call
  routing, SRVCC notification handlers.
- `ImsRegistrationController.java` — REGISTER lifecycle, AKA
  handshake, IPsec SA setup, refresh watchdog with RFC-compliant
  retry-after handling, IMS-PDN re-bind on LTE return.
- `HamelinPortsCallSession.java` / `HamelinPortsIncomingCallSession.java` —
  MO and MT IMS call profiles + SDP negotiation.
- `HamelinPortsSmsImpl.java` — SIP MESSAGE-based MO and MT SMS.
- `sip/` — SIP/SDP helpers, native handle lifetimes,
  `HamelinPortsSipStack` (the JNI front door).
- `net/` — IPsec SA setup helpers, ESP routing fix.
- `modem/` — bridge factory + AIDL-side proxies (see below).

### `jni/hamelinports_ims_jni.cpp`

Native bridge between the Java service and reSIProcate. Hosts the
`SipStack` and `DialogUsageManager`, wires registration/INVITE/MESSAGE
handlers, exposes JNI methods for outbound REGISTER, INVITE, ACK, BYE,
re-INVITE, MESSAGE, refresh.

Includes carrier-quirk fixes accumulated during bring-up (in-dialog
TCP routing, SDP offer/answer matching, 5xx Retry-After parsing).

### `jni/ims_xfrm`, `jni/ims_oemipc/ims_ipsec_setup.c`

Standalone C tools invoked via `Runtime.exec` for kernel XFRM
operations the IpSecManager API doesn't fully cover.

## Modem-bridge separation

Vendor-proprietary modem-coordination surfaces that AOSP's `IRadioIms`
does not model are intentionally kept out of this APK. Without those
messages, on hardware where the modem doesn't autonomously observe IMS
registrations the EPC routes MT voice as CSFB even when our IMS REGISTER
succeeded; with them, routing flips to IMS.

The split:

```
HamelinPortsImsService (this repo) --AIDL--> per-device bridge service
                                             (under the device tree)
```

`ImsModemBridgeFactory.create()`:

1. Queries `PackageManager.queryIntentServices` for the action
   `org.hamelinports.ims.modem.action.BIND_BRIDGE`.
2. If a service is registered, `bindService()` it. Returns a
   `BoundImsModemBridge` proxy that forwards calls to the latched
   remote (and silently drops calls before the binding completes —
   safe under the AIDL's `oneway` contract).
3. If no service is registered, returns `NoOpImsModemBridge` — an
   in-process stub. The IMS service runs unchanged.

Per-device bridge implementations are free to differ on the wire:
each chooses whatever transport its vendor RIL exposes (vendor RIL
channels, QMI commands, etc.); a fully AOSP-conformant device most
likely ships nothing at all.

## Porting to a new device

For an AOSP-conformant modem (most modern Qualcomm, MediaTek with
recent vendor): no bridge needed. `HamelinPortsImsService` runs as-is, the
no-op bridge stays in place, AOSP `IRadioIms` handles MT routing,
SRVCC, etc. Add to `device.mk`:

```mk
PRODUCT_PACKAGES += HamelinPortsImsService privapp-permissions-org.hamelinports.ims
PRODUCT_PROPERTY_OVERRIDES += persist.dbg.volte_avail_ovr=1
```

For a device that needs a vendor-proprietary modem-coordination bridge:

1. Create `device/<vendor>/<device>/<Vendor>ImsModemBridge/`.
2. `static_libs: ["hamelinports-ims-aidl"]` in the bridge's `Android.bp`.
3. Implement `IImsModemBridge.Stub` for the start/stop +
   sendRegistration + sendPreference methods, plus a `Service`
   declaring an `<intent-filter>` for the `BIND_BRIDGE` action.
4. Add the APK to `device.mk` PRODUCT_PACKAGES.

## Build dependencies

- Carrier-agnostic: `HamelinPortsImsService` + `hamelinports-ims-aidl` +
  reSIProcate (`external/resiprocate`) + the AOSP imsmedia library.
- Per-device: a bridge APK as above, plus carrier-config XML if the
  device's carrier needs non-default IMS provisioning.

## Sepolicy

`HamelinPortsImsService` runs in `system_app` (sharedUserId=`android.uid.system`,
platform certificate). Per-device bridges using the same UID inherit
all rules; the only common per-device add typically needed is
`hal_radio_service:service_manager find` if the bridge needs to lookup
a vendor binder service — see `device/samsung/a51/sepolicy/private/
system_app.te`.

## Diagnostics

`dumpsys activity service org.hamelinports.ims/.HamelinPortsImsService` is the
authoritative source for current state. Per-slot it prints:

- `running` + `registered`
- negotiated Expires, P-CSCF list
- last-registered / last-deregistered timestamps + counts
- refresh-tick counters (fired / ok / failed)
- 5xx retry counter
- next refresh due time

## Known limitations

- **SRVCC** mid-call: the framework dispatches to our handler but on
  some modems `RIL_UNSOL_SRVCC_STATE_NOTIFY` is not emitted even with
  the AIDL HAL bound, so calls drop on LTE → 2G transition.
  Investigation ongoing.
- **eSIM / DSDS**: dual-SIM is detected but only the active subscription
  registers. Concurrent IMS on both slots is untested.
