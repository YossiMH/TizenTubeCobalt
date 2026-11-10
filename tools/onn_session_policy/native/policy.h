#ifndef ONN_POLICY_H
#define ONN_POLICY_H

#include <stdint.h>

typedef enum { SOURCE_OTHER, SOURCE_PHYSICAL, SOURCE_PHONE, SOURCE_CEC,
               SOURCE_OTHER_POWER, SOURCE_BRIDGE } InputSource;
typedef enum { LEASE_DISARMED, LEASE_ARMED } LeasePhase;
typedef enum { GRAB_INACTIVE, GRAB_ACTIVE } GrabPhase;
typedef enum { KEY_OBSERVE, KEY_BLOCK, KEY_FORWARD_VOLUME } KeyAction;
typedef enum { VOLUME_IGNORE, VOLUME_EMIT } VolumeTransition;

typedef struct {
    LeasePhase phase;
    int64_t heartbeat_deadline_ms;
    int64_t session_epoch_ms;
    int64_t session_boottime_deadline_ms;
} Lease;

/* Each key's bitset identifies which physical input slots hold it down. */
typedef struct { uint64_t holders[3]; } VolumeState;
typedef struct { unsigned code; int value; } VolumeEvent;
typedef struct {
    VolumeState next;
    VolumeTransition transition;
    unsigned code;
    int value;
} VolumeResult;
typedef struct {
    VolumeState next;
    unsigned count;
    VolumeEvent events[3];
} VolumeRelease;

InputSource classify_device(const char *name, unsigned bus, unsigned vendor,
                            unsigned product);
const char *source_name(InputSource source);
Lease lease_arm(Lease previous, int64_t boottime_ms, int64_t epoch_ms,
                int64_t ttl_ms, int64_t session_epoch_ms);
Lease lease_tick(Lease previous, int64_t boottime_ms, int64_t epoch_ms);
KeyAction key_action(InputSource source, GrabPhase grab, unsigned code);
VolumeResult volume_update(VolumeState previous, unsigned device_slot,
                           unsigned code, int value);
VolumeRelease volume_release_device(VolumeState previous, unsigned device_slot);
VolumeRelease volume_release_all(VolumeState previous);

#endif
