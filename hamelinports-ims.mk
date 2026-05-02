# LineageOS IMS service bundle.
#
# Inherit from a device's device.mk to pull in the carrier-agnostic,
# modem-agnostic IMS stack:
#
#     $(call inherit-product, packages/apps/HamelinPortsImsService/hamelinports-ims.mk)
#
# Devices that need vendor-proprietary modem co-ordination should
# additionally inherit their per-device bridge bundle:
#
#     $(call inherit-product, device/<vendor>/<dev>/<Vendor>ImsModemBridge/<vendor>-ims-bridge.mk)
#
# Devices with AOSP-conformant IRadioIms support need only this file;
# HamelinPortsImsService falls back to the in-process NoOpImsModemBridge.

# AOSP ImsMediaService (libimsmedia + ImsMediaService APK + framework
# classes under android.telephony.imsmedia). HamelinPortsImsService drives
# voice + video media via this service; it's userspace-only and doesn't
# need a vendor HAL on bring-up devices.
$(call inherit-product, packages/modules/ImsMedia/imsmedia.mk)

PRODUCT_PACKAGES += \
    HamelinPortsImsService \
    privapp-permissions-org.hamelinports.ims \
    ims_xfrm

# Framework-resource overlay carrying config_use_voip_mode_for_ims=true.
# Without this, Telecom drives MODE_IN_CALL on IMS calls and audio routes
# to a non-existent vendor "voice_call" device — calls connect over SIP
# but the user hears silence. Build-time merge into framework-res.apk;
# devices can still override in their own device-tree overlays.
PRODUCT_PACKAGE_OVERLAYS += packages/apps/HamelinPortsImsService/framework-overlay

# AOSP IWLAN DataService: brings up the IKEv2/IPsec tunnel to the carrier
# ePDG and exposes the resulting IMS PDN to the framework. Required for
# Wi-Fi Calling. Inert on devices/carriers where WFC is disabled in
# CarrierConfig — safe to ship unconditionally in the shared bundle.
PRODUCT_PACKAGES += Iwlan

# AOSP QualifiedNetworksService: tells AccessNetworksManager which
# transport (WWAN vs WLAN) each APN type prefers. Without it,
# AccessNetworksManager has no opinion and IMS APNs always go via
# cellular — IwlanDataService is never asked to bring up a tunnel,
# so Wi-Fi Calling cannot activate even with all the carrier-config /
# user-pref gates flipped. Pairs with the framework-overlay below
# that points config_qualified_networks_service_package at it.
PRODUCT_PACKAGES += QualifiedNetworksService

# Device-agnostic sepolicy (ims_xfrm domain, hamelinports.ims.* property
# namespace, AOSP imsmedia ↔ system_app rules). Devices that need extra
# vendor-side rules layer those on via their own SYSTEM_EXT_*_SEPOLICY_DIRS /
# BOARD_VENDOR_SEPOLICY_DIRS in BoardConfig.mk.
SYSTEM_EXT_PRIVATE_SEPOLICY_DIRS += packages/apps/HamelinPortsImsService/sepolicy/private
SYSTEM_EXT_PUBLIC_SEPOLICY_DIRS += packages/apps/HamelinPortsImsService/sepolicy/public

