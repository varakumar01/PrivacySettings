# AxPrivacy product hooks. Inherited from a device/common makefile via
# $(call inherit-product-if-exists, packages/apps/AxPrivacy/axprivacy.mk);
# inherit-product sets LOCAL_PATH to this directory.

PRODUCT_PACKAGES += \
    AxPrivacy

PRODUCT_COPY_FILES += \
    $(LOCAL_PATH)/privapp-permissions-axprivacy.xml:$(TARGET_COPY_OUT_SYSTEM_EXT)/etc/permissions/privapp-permissions-axprivacy.xml
