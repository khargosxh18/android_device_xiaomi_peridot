#
# Copyright (C) 2024 The LineageOS Project
#
# SPDX-License-Identifier: Apache-2.0
#

# Inherit from those products. Most specific first.
$(call inherit-product, $(SRC_TARGET_DIR)/product/core_64_bit_only.mk)
$(call inherit-product, $(SRC_TARGET_DIR)/product/full_base_telephony.mk)

# Inherit some common Lineage stuff.
$(call inherit-product, vendor/lineage/config/common_full_phone.mk)

# Inherit from peridot device
$(call inherit-product, device/xiaomi/peridot/device.mk)

PRODUCT_NAME := lineage_peridot
PRODUCT_DEVICE := peridot
PRODUCT_MANUFACTURER := Xiaomi
PRODUCT_BRAND := POCO
PRODUCT_MODEL := 24069PC21G

PRODUCT_SYSTEM_NAME := peridot_global
PRODUCT_SYSTEM_DEVICE := peridot

PRODUCT_BUILD_PROP_OVERRIDES += \
    BuildDesc="peridot_global-user 16 BP2A.250605.031.A3 OS3.0.303.0.WNPMIXM release-keys" \
    BuildFingerprint=POCO/peridot_global/peridot:16/BP2A.250605.031.A3/OS3.0.303.0.WNPMIXM:user/release-keys \
    DeviceName=$(PRODUCT_SYSTEM_DEVICE) \
    DeviceProduct=$(PRODUCT_SYSTEM_NAME)

PRODUCT_GMS_CLIENTID_BASE := android-xiaomi

# Lunch Maintainer Variable
RISING_MAINTAINER="Khargosxh18"

# Set RISING_MAINTAINER
PRODUCT_BUILD_PROP_OVERRIDES += \
    RisingChipset="Snapdragon® 8s Gen 3" \
    RisingMaintainer="Khargosxh18"

RISING_MAINTAINER := Khargosxh18

# Disable/enable blur support, false by default
TARGET_ENABLE_BLUR := true

# BCR
TARGET_INCLUDE_BCR := false

# Whether to ship aperture camera, false by default
PRODUCT_NO_CAMERA := false

# CORE build flags
WITH_GMS := true
TARGET_USES_PICO_GAPPS := true

# VANILLA build with MICROG
#WITH_GMS := false
#WITH_MICROG := true
