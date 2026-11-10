#include "policy.h"
#include <assert.h>
#include <limits.h>
#include <stdio.h>

static void identities_are_explicit(void) {
    assert(classify_device("Onn-Remote Keyboard", 5, 0x0957, 0x0031) == SOURCE_PHYSICAL);
    assert(classify_device("virtual-remote", 6, 0x18d1, 0x0100) == SOURCE_PHONE);
    assert(classify_device("Onn-Remote Keyboard", 6, 0x0957, 0x0031) == SOURCE_OTHER);
    assert(classify_device("virtual-remote", 5, 0x18d1, 0x0100) == SOURCE_OTHER);
    assert(classify_device("Some Remote", 5, 0x0957, 0x0031) == SOURCE_OTHER);
    assert(classify_device("Onn Policy Volume", 6, 0x1209, 0x1110) == SOURCE_BRIDGE);
    assert(classify_device("cec_input", 0, 0, 0) == SOURCE_CEC);
    assert(classify_device("input_ethrcu", 0, 0, 0) == SOURCE_OTHER_POWER);
}

static void lease_requires_fresh_authenticated_heartbeat(void) {
    Lease lease = {0};
    lease = lease_arm(lease, 1000, 100000, 15000, 160000);
    assert(lease.phase == LEASE_ARMED);
    assert(lease_tick(lease, 15999, 114999).phase == LEASE_ARMED);
    assert(lease_tick(lease, 16000, 115000).phase == LEASE_DISARMED);
    lease = lease_arm(lease, 10000, 109000, 15000, 160000);
    assert(lease.heartbeat_deadline_ms == 25000);
    assert(lease_tick(lease, 25000, 124000).phase == LEASE_DISARMED);
    assert(lease_arm(lease, 11000, 110000, 15001, 160000).phase == LEASE_DISARMED);
    assert(lease_arm(lease, 11000, 110000, 0, 160000).phase == LEASE_DISARMED);
    assert(lease_arm(lease, 11000, 110000, 1000, 109999).phase == LEASE_DISARMED);
    assert(lease_arm(lease, 11000, 110000, 1000, INT64_MAX).phase == LEASE_DISARMED);
}

static void sleep_and_clock_changes_cannot_extend_session(void) {
    Lease lease = lease_arm((Lease){0}, 1000, 100000, 15000, 160000);
    lease = lease_arm(lease, 50000, 90000, 15000, 160000);
    assert(lease.session_boottime_deadline_ms == 61000);
    assert(lease_tick(lease, 61000, 101000).phase == LEASE_DISARMED);
    lease = lease_arm((Lease){0}, 1000, 100000, 15000, 160000);
    assert(lease_tick(lease, 2000, 160001).phase == LEASE_DISARMED);
    assert(lease_arm(lease, 2000, 101000, 15000, 170000).phase == LEASE_DISARMED);
}

static void physical_volume_stays_paired_across_release_and_disconnect(void) {
    VolumeState state = {0};
    VolumeResult r = volume_update(state, 0, 115, 1);
    assert(r.transition == VOLUME_EMIT && r.value == 1 && r.code == 115);
    state = r.next;
    r = volume_update(state, 0, 115, 2);
    assert(r.transition == VOLUME_EMIT && r.value == 2);
    state = r.next;
    r = volume_update(state, 1, 115, 1);
    assert(r.transition == VOLUME_IGNORE);
    state = r.next;
    r = volume_update(state, 0, 115, 0);
    assert(r.transition == VOLUME_IGNORE);
    state = r.next;
    r = volume_update(state, 1, 115, 0);
    assert(r.transition == VOLUME_EMIT && r.value == 0);
    state = r.next;
    assert(volume_update(state, 1, 115, 0).transition == VOLUME_IGNORE);
    state = volume_update(state, 2, 114, 1).next;
    state = volume_update(state, 2, 113, 1).next;
    VolumeRelease released = volume_release_device(state, 2);
    assert(released.count == 2);
    assert(released.events[0].value == 0 && released.events[1].value == 0);
    assert(volume_release_all(released.next).count == 0);
    state = volume_update((VolumeState){0}, 5, 115, 1).next;
    released = volume_release_all(state);
    assert(released.count == 1 && released.events[0].code == 115 && released.events[0].value == 0);
    assert(volume_update(released.next, 5, 115, 2).transition == VOLUME_IGNORE);
}

static void only_known_physical_input_is_blocked(void) {
    assert(key_action(SOURCE_PHYSICAL, GRAB_ACTIVE, 115) == KEY_FORWARD_VOLUME);
    assert(key_action(SOURCE_PHYSICAL, GRAB_ACTIVE, 113) == KEY_FORWARD_VOLUME);
    assert(key_action(SOURCE_PHYSICAL, GRAB_ACTIVE, 102) == KEY_BLOCK);
    assert(key_action(SOURCE_PHYSICAL, GRAB_ACTIVE, 116) == KEY_BLOCK);
    assert(key_action(SOURCE_PHYSICAL, GRAB_INACTIVE, 116) == KEY_OBSERVE);
    assert(key_action(SOURCE_PHONE, GRAB_INACTIVE, 116) == KEY_OBSERVE);
    assert(key_action(SOURCE_PHONE, GRAB_ACTIVE, 116) == KEY_OBSERVE);
    assert(key_action(SOURCE_OTHER_POWER, GRAB_ACTIVE, 116) == KEY_OBSERVE);
    assert(key_action(SOURCE_CEC, GRAB_ACTIVE, 115) == KEY_OBSERVE);
}

int main(void) {
    identities_are_explicit();
    lease_requires_fresh_authenticated_heartbeat();
    sleep_and_clock_changes_cannot_extend_session();
    physical_volume_stays_paired_across_release_and_disconnect();
    only_known_physical_input_is_blocked();
    puts("PASS: identity, lease expiry, clock shifts, paired volume, phone bypass");
    return 0;
}
