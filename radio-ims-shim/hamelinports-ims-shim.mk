# Opt-in IRadioIms shim bundle.
#
# Inherit from a device's device.mk ONLY if the device's vendor RIL does
# not expose android.hardware.radio.ims.IRadioIms/slotN natively. Devices
# that already ship a real IRadioIms HAL must NOT inherit this — the
# binder names would collide.
#
#     $(call inherit-product, packages/apps/HamelinPortsImsService/hamelinports-ims.mk)
#     $(call inherit-product, packages/apps/HamelinPortsImsService/radio-ims-shim/hamelinports-ims-shim.mk)
#
# The bundled VINTF fragment declares both slot1 and slot2; the shim's
# main.cpp uses AServiceManager_isDeclared() to gate publish() on what
# the merged device manifest actually carries. Single-SIM devices that
# don't want slot2 advertised should override the manifest fragment in
# their device tree.

PRODUCT_PACKAGES += \
    android.hardware.radio.ims-service.hamelinports_shim

BOARD_VENDOR_SEPOLICY_DIRS += packages/apps/HamelinPortsImsService/sepolicy/vendor
