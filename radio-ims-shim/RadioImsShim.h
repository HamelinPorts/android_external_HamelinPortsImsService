/*
 * SPDX-License-Identifier: Apache-2.0
 *
 * Minimal IRadioIms vendor HAL shim for devices whose vendor RIL
 * does not expose android.hardware.radio.ims.IRadioIms/slotN.
 *
 * Without IRadioIms in the vendor manifest the AOSP framework skips
 * MmTelFeature.notifySrvcc* dispatch (ImsPhoneCallTracker gates SRVCC
 * forwarding on HAL presence). The call list never reaches
 * HamelinPortsImsService and the SRVCC handler cannot run.
 *
 * This shim publishes IRadioIms/slot{1,2} with ack-success stubs.
 * SRVCC state itself arrives via the existing Voice RIL path; what we
 * gain by publishing here is the framework's willingness to invoke
 * setSrvccCallInfo on us (= trigger MmTelFeature.notifySrvccStarted)
 * when the modem reports SRVCC.
 *
 * No modem bytes are sent. For vendor-specific downstream modem
 * co-ordination see the per-device ImsModemBridge implementations.
 *
 * Devices that already ship a real IRadioIms HAL must NOT inherit this
 * sub-bundle — the names would collide.
 */
#pragma once

#include <aidl/android/hardware/radio/ims/BnRadioIms.h>
#include <aidl/android/hardware/radio/ims/IRadioImsIndication.h>
#include <aidl/android/hardware/radio/ims/IRadioImsResponse.h>

#include <mutex>

namespace android::hardware::radio::ims::shim {

namespace aidl = ::aidl::android::hardware::radio::ims;

class RadioImsShim : public aidl::BnRadioIms {
  public:
    explicit RadioImsShim(std::string slot) : mSlot(std::move(slot)) {}

    ::ndk::ScopedAStatus setResponseFunctions(
            const std::shared_ptr<aidl::IRadioImsResponse>& resp,
            const std::shared_ptr<aidl::IRadioImsIndication>& ind) override;

    ::ndk::ScopedAStatus setSrvccCallInfo(
            int32_t serial,
            const std::vector<aidl::SrvccCall>& calls) override;

    ::ndk::ScopedAStatus updateImsRegistrationInfo(
            int32_t serial,
            const aidl::ImsRegistration& reg) override;

    ::ndk::ScopedAStatus startImsTraffic(
            int32_t serial, int32_t token,
            aidl::ImsTrafficType trafficType,
            ::aidl::android::hardware::radio::AccessNetwork accessNetwork,
            aidl::ImsCall::Direction direction) override;

    ::ndk::ScopedAStatus stopImsTraffic(int32_t serial, int32_t token) override;

    ::ndk::ScopedAStatus triggerEpsFallback(
            int32_t serial, aidl::EpsFallbackReason reason) override;

    ::ndk::ScopedAStatus sendAnbrQuery(
            int32_t serial, aidl::ImsStreamType mediaType,
            aidl::ImsStreamDirection direction,
            int32_t bitsPerSecond) override;

    ::ndk::ScopedAStatus updateImsCallStatus(
            int32_t serial,
            const std::vector<aidl::ImsCall>& calls) override;

  private:
    const std::string mSlot;

    /* Callbacks handed to us by setResponseFunctions. Accessed from
     * the binder thread; guarded by mMu. */
    mutable std::mutex mMu;
    std::shared_ptr<aidl::IRadioImsResponse> mResp;
    std::shared_ptr<aidl::IRadioImsIndication> mInd;

    std::shared_ptr<aidl::IRadioImsResponse> respond() const {
        std::lock_guard<std::mutex> g(mMu);
        return mResp;
    }
};

}  // namespace android::hardware::radio::ims::shim
