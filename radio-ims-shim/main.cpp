/* SPDX-License-Identifier: Apache-2.0 */
#include "RadioImsShim.h"

#include <android-base/logging.h>
#include <android/binder_manager.h>
#include <android/binder_process.h>

using android::hardware::radio::ims::shim::RadioImsShim;

namespace imsaidl = ::aidl::android::hardware::radio::ims;

static std::vector<std::shared_ptr<RadioImsShim>> gPublished;

static void publish(const std::string& slot) {
    const std::string instance = std::string(imsaidl::IRadioIms::descriptor) + "/" + slot;
    if (!AServiceManager_isDeclared(instance.c_str())) {
        LOG(INFO) << instance << " not declared in VINTF — skipping";
        return;
    }
    auto impl = ndk::SharedRefBase::make<RadioImsShim>(slot);
    gPublished.push_back(impl);
    const auto status = AServiceManager_addService(
            impl->asBinder().get(), instance.c_str());
    CHECK_EQ(status, STATUS_OK) << "register " << instance;
    LOG(INFO) << "Published " << instance;
}

int main() {
    android::base::InitLogging(nullptr,
            android::base::LogdLogger(android::base::RADIO));
    android::base::SetDefaultTag("RadioImsShim");
    android::base::SetMinimumLogSeverity(android::base::VERBOSE);

    LOG(INFO) << "RadioImsShim starting (HamelinPorts ack-only IRadioIms stub — "
              << "unblocks framework SRVCC dispatch on devices without a real "
              << "IRadioIms HAL)";

    /* Publish both common slot names; isDeclared() filters to whatever
     * the device's VINTF actually advertises, so single-SIM devices
     * skip slot2 automatically. */
    publish("slot1");
    publish("slot2");

    ABinderProcess_setThreadPoolMaxThreadCount(1);
    ABinderProcess_joinThreadPool();
    LOG(FATAL) << "RadioImsShim exited thread pool";
    return 1;
}
