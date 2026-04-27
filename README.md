# HamelinPortsImsService

A carrier-agnostic IMS service for LineageOS — replaces the proprietary
vendor IMS stack on devices that ship one (typically Samsung) and adds
IMS support to devices that don't.

Implements the AOSP `ImsService` AIDL surface (`MmTelFeature`,
`ImsRegistrationImplBase`, `ImsCallSessionImplBase`, `ImsSmsImplBase`)
on top of [reSIProcate](https://github.com/resiprocate/resiprocate) for
SIP and the AOSP `android.telephony.imsmedia` framework for RTP/RTCP.

## Status

Working on Samsung Galaxy A51 (Exynos 9611) against o2 Germany /
Telefónica DE:

- IMS REGISTER over IPsec ESP (3GPP TS 33.203, hmac-md5-96 + null
  encryption)
- AKA-MD5 challenge / response via TelephonyManager
- MO + MT VoLTE calls with AMR-WB audio (via `imsmedia`)
- MO + MT SMS over SIP MESSAGE
- MMTel video (H.264 Constrained Baseline)
- VoWiFi (ePDG) — MO + MT calls and mid-call cellular ↔ Wi-Fi
  handover (audio preserved by carrier IP continuity)
- 5xx REGISTER refresh handling per RFC 3261 §21.5.4 / §20.33
- IMS PDN onLost recovery (re-REGISTER on LTE return)

Not yet:

- SRVCC (handler exists; modem doesn't yet drive the dispatch — see
  [project_srvcc_not_wired memory][1])

## Layout

```
aidl/                       AIDL contract for per-device modem bridges
                            (hamelinports-ims-aidl java_library)
jni/                        reSIProcate native bridge + SIP stack glue
src/org/hamelinports/ims/      Java side: ImsService, MmTelFeature,
                            registration, call/SMS sessions
src/org/hamelinports/ims/sip/  SIP/SDP helpers + native handle wrappers
src/org/hamelinports/ims/net/  IPsec SA setup helpers
src/org/hamelinports/ims/modem/  Bridge factory + AIDL stubs (see
                              ARCHITECTURE.md)
ims_xfrm/                   Standalone iproute2-equivalent for installing
                            kernel XFRM SAs (called via Runtime.exec)
ims_oemipc/                 IPsec setup tooling (despite the legacy
                            name, only ims_ipsec_setup.c is live)
```

## Build

Standard AOSP / LineageOS tree:

```
source build/envsetup.sh
lunch lineage_<device>-bp4a-eng
m HamelinPortsImsService
```

The privapp-permissions XML and the optional per-device modem bridge
need to be wired into `device.mk`; see
[ARCHITECTURE.md](ARCHITECTURE.md#porting-to-a-new-device) for the
porting checklist.

## Configuration

A handful of system properties tune behaviour:

| Property | Default | Use |
|---|---|---|
| `persist.lineage.ims.register.expires.sec` | 3600 | Requested REGISTER Expires (the carrier may force a higher minimum via 423 + Min-Expires) |
| `persist.lineage.ims.refresh.sec` | (Expires − 60) | Override the refresh tick interval (clamped ≥ 30s; only useful for testing the watchdog) |
| `persist.dbg.volte_avail_ovr` | unset | Set to `1` on `device.mk` to advertise VoLTE-by-platform when the vendor RIL doesn't |

## Diagnostics

```
dumpsys activity service org.hamelinports.ims/.HamelinPortsImsService
```

Per-slot snapshot: registration state, negotiated Expires, P-CSCF list,
refresh-tick counters, 5xx retry counter.

## Architecture & porting

See [ARCHITECTURE.md](ARCHITECTURE.md).
