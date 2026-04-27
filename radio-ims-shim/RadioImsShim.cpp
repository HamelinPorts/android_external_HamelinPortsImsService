/* SPDX-License-Identifier: Apache-2.0 */
#include "RadioImsShim.h"

#include <aidl/android/hardware/radio/RadioError.h>
#include <aidl/android/hardware/radio/RadioResponseInfo.h>
#include <aidl/android/hardware/radio/RadioResponseType.h>
#include <android-base/logging.h>

namespace android::hardware::radio::ims::shim {

using ::ndk::ScopedAStatus;
using RadioError = ::aidl::android::hardware::radio::RadioError;
using RadioResponseInfo = ::aidl::android::hardware::radio::RadioResponseInfo;
using RadioResponseType = ::aidl::android::hardware::radio::RadioResponseType;

static RadioResponseInfo ackOk(int32_t serial) {
    return RadioResponseInfo{
            .type = RadioResponseType::SOLICITED,
            .serial = serial,
            .error = RadioError::NONE,
    };
}

ScopedAStatus RadioImsShim::setResponseFunctions(
        const std::shared_ptr<aidl::IRadioImsResponse>& resp,
        const std::shared_ptr<aidl::IRadioImsIndication>& ind) {
    LOG(INFO) << "[RadioImsShim/" << mSlot << "] setResponseFunctions"
              << " resp=" << (resp != nullptr)
              << " ind=" << (ind != nullptr);
    std::lock_guard<std::mutex> g(mMu);
    mResp = resp;
    mInd = ind;
    return ScopedAStatus::ok();
}

ScopedAStatus RadioImsShim::setSrvccCallInfo(
        int32_t serial, const std::vector<aidl::SrvccCall>& calls) {
    LOG(INFO) << "[RadioImsShim/" << mSlot << "] setSrvccCallInfo"
              << " serial=" << serial << " calls=" << calls.size();
    auto r = respond();
    if (r) r->setSrvccCallInfoResponse(ackOk(serial));
    return ScopedAStatus::ok();
}

ScopedAStatus RadioImsShim::updateImsRegistrationInfo(
        int32_t serial, const aidl::ImsRegistration& /*reg*/) {
    LOG(INFO) << "[RadioImsShim/" << mSlot << "] updateImsRegistrationInfo"
              << " serial=" << serial;
    auto r = respond();
    if (r) r->updateImsRegistrationInfoResponse(ackOk(serial));
    return ScopedAStatus::ok();
}

ScopedAStatus RadioImsShim::startImsTraffic(
        int32_t serial, int32_t token,
        aidl::ImsTrafficType /*trafficType*/,
        ::aidl::android::hardware::radio::AccessNetwork /*accessNetwork*/,
        aidl::ImsCall::Direction /*direction*/) {
    LOG(INFO) << "[RadioImsShim/" << mSlot << "] startImsTraffic"
              << " serial=" << serial << " token=" << token;
    auto r = respond();
    /* std::nullopt — the modem didn't fail anything (we didn't ask
     * it anything). Framework proceeds. */
    if (r) r->startImsTrafficResponse(ackOk(serial), std::nullopt);
    return ScopedAStatus::ok();
}

ScopedAStatus RadioImsShim::stopImsTraffic(int32_t serial, int32_t token) {
    LOG(INFO) << "[RadioImsShim/" << mSlot << "] stopImsTraffic"
              << " serial=" << serial << " token=" << token;
    auto r = respond();
    if (r) r->stopImsTrafficResponse(ackOk(serial));
    return ScopedAStatus::ok();
}

ScopedAStatus RadioImsShim::triggerEpsFallback(
        int32_t serial, aidl::EpsFallbackReason /*reason*/) {
    LOG(INFO) << "[RadioImsShim/" << mSlot << "] triggerEpsFallback"
              << " serial=" << serial;
    auto r = respond();
    if (r) r->triggerEpsFallbackResponse(ackOk(serial));
    return ScopedAStatus::ok();
}

ScopedAStatus RadioImsShim::sendAnbrQuery(
        int32_t serial, aidl::ImsStreamType /*mediaType*/,
        aidl::ImsStreamDirection /*direction*/, int32_t bitsPerSecond) {
    LOG(INFO) << "[RadioImsShim/" << mSlot << "] sendAnbrQuery"
              << " serial=" << serial << " bps=" << bitsPerSecond;
    auto r = respond();
    if (r) r->sendAnbrQueryResponse(ackOk(serial));
    return ScopedAStatus::ok();
}

ScopedAStatus RadioImsShim::updateImsCallStatus(
        int32_t serial, const std::vector<aidl::ImsCall>& calls) {
    LOG(INFO) << "[RadioImsShim/" << mSlot << "] updateImsCallStatus"
              << " serial=" << serial << " calls=" << calls.size();
    auto r = respond();
    if (r) r->updateImsCallStatusResponse(ackOk(serial));
    return ScopedAStatus::ok();
}

}  // namespace android::hardware::radio::ims::shim
