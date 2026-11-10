#include "policy.h"
#include <string.h>

#define MAX_LEASE_MS INT64_C(15000)
#define MAX_SESSION_MS INT64_C(21600000)
static const unsigned volume_codes[3] = {113, 114, 115};

InputSource classify_device(const char *name, unsigned bus, unsigned vendor,
                            unsigned product) {
    if (strcmp(name, "Onn-Remote Keyboard") == 0 && bus == 5 &&
        vendor == 0x0957 && product == 0x0031) return SOURCE_PHYSICAL;
    if (strcmp(name, "virtual-remote") == 0 && bus == 6 &&
        vendor == 0x18d1 && product == 0x0100) return SOURCE_PHONE;
    if (strcmp(name, "Onn Policy Volume") == 0 && bus == 6 &&
        vendor == 0x1209 && product == 0x1110) return SOURCE_BRIDGE;
    if (strcmp(name, "cec_input") == 0) return SOURCE_CEC;
    if (strcmp(name, "input_ethrcu") == 0 || strcmp(name, "input_btrcu") == 0 ||
        strcmp(name, "vad_keypad") == 0) return SOURCE_OTHER_POWER;
    return SOURCE_OTHER;
}

const char *source_name(InputSource source) {
    static const char *const names[] = {"other", "physical", "phone", "cec",
                                        "other_power", "volume_bridge"};
    unsigned index = (unsigned)source;
    return index < sizeof(names) / sizeof(names[0]) ? names[index] : "other";
}

Lease lease_arm(Lease previous, int64_t now_boot, int64_t now_epoch,
                int64_t ttl, int64_t session_epoch) {
    if (ttl <= 0 || ttl > MAX_LEASE_MS || now_boot < 0 || now_epoch < 0 ||
        session_epoch <= now_epoch || session_epoch - now_epoch > MAX_SESSION_MS ||
        now_boot > INT64_MAX - MAX_SESSION_MS) return (Lease){0};
    if (previous.session_epoch_ms != 0 && previous.session_epoch_ms != session_epoch)
        return (Lease){0};
    int64_t session_boot = now_boot + (session_epoch - now_epoch);
    if (previous.session_boottime_deadline_ms != 0 &&
        previous.session_boottime_deadline_ms < session_boot)
        session_boot = previous.session_boottime_deadline_ms;
    if (session_boot <= now_boot) return (Lease){0};
    Lease next = {LEASE_ARMED, now_boot + ttl, session_epoch, session_boot};
    return next;
}

Lease lease_tick(Lease previous, int64_t now_boot, int64_t now_epoch) {
    if (previous.phase == LEASE_ARMED &&
        (now_boot >= previous.heartbeat_deadline_ms ||
         now_boot >= previous.session_boottime_deadline_ms ||
         now_epoch >= previous.session_epoch_ms)) previous.phase = LEASE_DISARMED;
    return previous;
}

KeyAction key_action(InputSource source, GrabPhase grab, unsigned code) {
    if (source != SOURCE_PHYSICAL || grab != GRAB_ACTIVE) return KEY_OBSERVE;
    return code >= 113 && code <= 115 ? KEY_FORWARD_VOLUME : KEY_BLOCK;
}

VolumeResult volume_update(VolumeState previous, unsigned slot,
                           unsigned code, int value) {
    VolumeResult result = {previous, VOLUME_IGNORE, code, value};
    if (slot >= 64 || code < 113 || code > 115 || value < 0 || value > 2) return result;
    unsigned i = code - 113;
    uint64_t bit = UINT64_C(1) << slot;
    if (value == 1) {
        if (previous.holders[i] == 0) result.transition = VOLUME_EMIT;
        result.next.holders[i] |= bit;
    } else if (value == 0) {
        if ((previous.holders[i] & bit) != 0) {
            result.next.holders[i] &= ~bit;
            if (result.next.holders[i] == 0) result.transition = VOLUME_EMIT;
        }
    } else if ((previous.holders[i] & bit) != 0) {
        result.transition = VOLUME_EMIT;
    }
    return result;
}

VolumeRelease volume_release_device(VolumeState previous, unsigned slot) {
    VolumeRelease result = {previous, 0, {{0, 0}, {0, 0}, {0, 0}}};
    if (slot >= 64) return result;
    uint64_t bit = UINT64_C(1) << slot;
    for (unsigned i = 0; i < 3; ++i) {
        result.next.holders[i] &= ~bit;
        if (previous.holders[i] != 0 && result.next.holders[i] == 0)
            result.events[result.count++] = (VolumeEvent){volume_codes[i], 0};
    }
    return result;
}

VolumeRelease volume_release_all(VolumeState previous) {
    VolumeRelease result = {{ {0, 0, 0} }, 0, {{0, 0}, {0, 0}, {0, 0}}};
    for (unsigned i = 0; i < 3; ++i)
        if (previous.holders[i] != 0)
            result.events[result.count++] = (VolumeEvent){volume_codes[i], 0};
    return result;
}
