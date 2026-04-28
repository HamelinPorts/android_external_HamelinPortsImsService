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

### Decision tree

```
Does the device's vendor RIL publish AOSP's android.hardware.radio.ims.IRadioIms?
├─ Yes (most modern Qualcomm / MediaTek): no bridge needed; AOSP IRadioIms
│       handles MT routing, SRVCC, etc. NoOpImsModemBridge stays in place.
└─ No  → does the device run a Samsung-derived RIL (Shannon, secril,
         secril-on-Unisoc)?
        ├─ Yes: implement an IImsModemBridge that emits Samsung IIL byte frames
        │       on top of vendor.samsung.hardware.radio.channel — see "Samsung
        │       IIL bridge" below; the byte protocol is shared, only transport
        │       and header size vary per chipset family.
        └─ No  → write a fully custom bridge against whatever vendor surface
                 the modem actually exposes (QMI, AT-over-vendor-binder, etc.).
                 No shared infrastructure helps here.
```

### AOSP-conformant modems

Drop into `device.mk`:

```mk
PRODUCT_PACKAGES += HamelinPortsImsService privapp-permissions-org.hamelinports.ims
PRODUCT_PROPERTY_OVERRIDES += persist.dbg.volte_avail_ovr=1
```

### Custom-bridge devices (non-Samsung, non-AOSP)

1. Create `device/<vendor>/<device>/<Vendor>ImsModemBridge/`.
2. `static_libs: ["hamelinports-ims-aidl"]` in the bridge's `Android.bp`.
3. Implement `IImsModemBridge.Stub` for the start/stop +
   sendRegistration + sendPreference methods, plus a `Service`
   declaring an `<intent-filter>` for the `BIND_BRIDGE` action.
4. Add the APK to `device.mk` PRODUCT_PACKAGES.

### Samsung IIL bridge — byte protocol shared, transport varies

All Samsung-RIL devices we've seen route IMS-state notifications to the
modem through the same opaque "IIL" byte-frame channel, but the binder
transport that carries the bytes — and the size of the IIL frame header
itself — differs per chipset family.

**Transport.** Look up the vendor RIL's published service in
`/vendor/etc/vintf/manifest/vendor.samsung.hardware.sehradio_manifest_*.xml`:

| Stack / chipset family            | Transport      | Service name                                                                  | Java API                                  |
|-----------------------------------|---------------|--------------------------------------------------------------------------------|--------------------------------------------|
| Shannon (e.g. Exynos 9611, A51)   | **AIDL**      | `vendor.samsung.hardware.radio.channel.ISehRadioChannel/imsd{,2}`              | `ServiceManager.checkService` + `Parcel`   |
| Samsung-RIL on Unisoc (e.g. UMS512, gta8) | **HIDL**  | `vendor.samsung.hardware.radio.channel@2.0::ISehChannel/imsd{,2}`              | `HwBinder.getService` + `HwParcel`         |

Both expose the same two functional methods on top of HIDL/AIDL housekeeping:

- `setCallback(ISehChannelCallback)` — transaction code 1 (HIDL: `FIRST_CALL_TRANSACTION`)
- `send(vec<uint8>)` — transaction code 2

`send()` carries one opaque IIL frame; the modem-side firmware parses
the bytes inside the proprietary `secril_*` modules.

**IIL frame format.** The frame is `[header] + [body]`, where the header
size depends on the chipset family:

| Stack family               | Header size | Header layout                                                          |
|----------------------------|-------------|------------------------------------------------------------------------|
| Shannon                    | **5 bytes** | `[len_lo, len_hi, mainCmd=0x70, subCmd, cmdType=0x03 EXEC]`            |
| Samsung-RIL on Unisoc      | **7 bytes** | `[len_lo, len_hi, seq=0, aseq=0, mainCmd=0x70, subCmd, cmdType=0x03]`  |

`len` is total frame length (header + body) as little-endian u16.
`seq`/`aseq` are unused on uplink (always zero on uplink frames; modem
uses them for paired req/rsp tracking which we don't need for
fire-and-forget NOTIs).

**Sub-command bodies are identical** across the two families. The
load-bearing ones for MT IMS-call routing are:

- `IPC_IIL_REGISTRATION` (sub=0x01, cmdType=3 NOTI), 268-byte body:
  - `body[0]` LimitedMode (0)
  - `body[1]` capability flags bitmap (VOLTE 0x01 / SMSIP 0x02 / RCS 0x04 / PSVT 0x08 / CDPN 0x20)
  - `body[2]` PdnType (0)
  - `body[3]` FeatureTag bitmap (CS 0x01 / SMSIP 0x02 / VOLTE 0x04 / VIDEO 0x08 / MMTEL 0x10)
  - `body[4..9]` zeros / Ecmp / EpdgMode / ErrorCode / reserved
  - `body[10]` IMPU UTF-8 length (max 256)
  - `body[11..n]` IMPU UTF-8 bytes
  - `body[0x10B]` RegiRat (`RAT_LTE=14`, `RAT_NR=20`, `RAT_IWLAN=18`)
- `IPC_IIL_PREFERENCE` (sub=0x06, cmdType=3 NOTI), 14-byte body — VoLTE / VT / SMS-over-IMS preference flags. Recommended on bridge bind to advertise capabilities.
- `IPC_IIL_CONNECTED` (sub=0x12) — handshake on bridge bind. Not strictly required but stock Samsung sends it.

Everything else (`RETRYOVER`, `SSAC`, `ISIM_LOADED`, `EMC_ATTACH_AUTH`,
`VONR_USER_STATUS`, `SIP_SUSPEND`) is informational and can be no-op'd
for first bring-up.

**Implementation pattern.** Two reference implementations live in-tree:

- `device/samsung/a51/SamsungImsModemBridge/` — Shannon AIDL transport, 5-byte IIL header.
- `device/samsung/gta8/SamsungImsModemBridge/` — Samsung-RIL-on-Unisoc HIDL transport, 7-byte IIL header.

Both share the same `IImsModemBridge.aidl` contract and the same
sub-command body layouts; they diverge only in the binder transport
helpers and the `HEADER_LEN`/`TOTAL_LEN` constants. New Samsung-derived
ports should pick whichever reference matches their chipset family,
copy it, and bump the framing constants if needed. Once two devices
have shipped working ports the shared bits are due to be extracted into
an external repo (see memory note `project_samsung_ims_bridge_shared_repo_question`).

**Sepolicy.** Per-device bridges should bundle their sepolicy files
next to the bridge source rather than scatter rules across the device
tree's `sepolicy/` directory. The recommended layout (used by
gta8's `SamsungImsModemBridge/`):

```
SamsungImsModemBridge/
├── BoardConfig.mk              # registers the dirs below
└── sepolicy/
    ├── system_ext_public/      # types shared with vendor
    ├── system_ext/             # platform_app/system_app rules
    └── vendor/                 # rild ↔ bridge binder round-trip rules
```

The device `BoardConfig.mk` then `include`s the bridge's `BoardConfig.mk`,
which appends to `SYSTEM_EXT_PUBLIC_SEPOLICY_DIRS`,
`SYSTEM_EXT_PRIVATE_SEPOLICY_DIRS`, and `BOARD_VENDOR_SEPOLICY_DIRS`.
This keeps the bridge self-contained and easier to extract to a
shared repo later.

Rules needed (in addition to whatever the bridge implementation
specifically requires):

AIDL transport (Shannon, A51-style):

```
allow system_app hal_radio_service:service_manager find;
binder_call(system_app, rild)
allow rild system_app:binder call;
```

HIDL transport (Samsung-RIL-on-Unisoc, gta8-style — with the bridge
running as `platform_app` because `sharedUserId="android.uid.system"`
silently breaks the AM bind on A16+):

```
hwbinder_use(platform_app)
allow platform_app hal_sehradio_channel_hwservice:hwservice_manager find;
binder_call(platform_app, rild)
allow rild platform_app:binder call;
```

The `hal_sehradio_channel_hwservice` type itself must be declared in
`system_ext_public/` so both the system-side `find` rule and the
vendor-side `add_hwservice()` see the same type at compile time.

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
