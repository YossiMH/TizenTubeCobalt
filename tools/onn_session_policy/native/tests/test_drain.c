/* Exercise the real command parser, input reader, poll, and output writer.
 * Anonymous pipes carry Linux input_event records; no /dev/input writes,
 * mocks, fake syscall implementations, or real device grabs are used. */
#define main input_guard_program_main
#include "../onn_input_guard.c"
#undef main
#include <assert.h>

static Runtime fresh_runtime(void) {
    Runtime r;
    memset(&r, 0, sizeof(r));
    r.bridge_fd = -1;
    r.signal_fd = -1;
    r.lock_fd = -1;
    r.mode = MODE_CONTROLLED;
    return r;
}

static int add_pipe(Runtime *r, unsigned slot, InputSource source) {
    int fds[2];
    assert(pipe2(fds, O_NONBLOCK | O_CLOEXEC) == 0);
    Device *d = &r->devices[slot];
    d->phase = SLOT_OPEN;
    d->fd = fds[0];
    d->input_id = slot + 40;
    d->source = source;
    (void)snprintf(d->name, sizeof(d->name), "%s",
                   source == SOURCE_PHONE ? "virtual-remote" : "unclassified-test-source");
    return fds[1];
}

static void enqueue(int fd, uint16_t type, uint16_t code, int value) {
    struct input_event event;
    memset(&event, 0, sizeof(event));
    event.type = type;
    event.code = code;
    event.value = value;
    assert(write(fd, &event, sizeof(event)) == (ssize_t)sizeof(event));
}

/* Read emitted JSON through an actual stdout pipe, including flush_output. */
static void output(Runtime *r, char *text) {
    int fds[2];
    assert(pipe2(fds, O_NONBLOCK | O_CLOEXEC) == 0);
    int saved_stdout = dup(STDOUT_FILENO);
    assert(saved_stdout >= 0);
    assert(dup2(fds[1], STDOUT_FILENO) >= 0);
    flush_output(r);
    assert(dup2(saved_stdout, STDOUT_FILENO) >= 0);
    assert(close(saved_stdout) == 0);
    assert(close(fds[1]) == 0);
    assert(r->output_length == 0);
    size_t used = 0;
    ssize_t count;
    while ((count = read(fds[0], text + used, OUTPUT_SIZE - used)) > 0) {
        used += (size_t)count;
        assert(used < OUTPUT_SIZE);
    }
    assert(count == 0);
    text[used] = '\0';
    assert(close(fds[0]) == 0);
}

static void close_readers(Runtime *r) {
    for (unsigned i = 0; i < MAX_DEVICES; ++i) {
        if (r->devices[i].phase == SLOT_OPEN) {
            assert(close(r->devices[i].fd) == 0);
            r->devices[i].phase = SLOT_EMPTY;
        }
    }
}

static void test_queued_power_precedes_marker(void) {
    Runtime r = fresh_runtime();
    int writer = add_pipe(&r, 0, classify_device("virtual-remote", 6, 0x18d1, 0x0100));
    enqueue(writer, EV_KEY, KEY_POWER, 1);
    enqueue(writer, EV_SYN, SYN_REPORT, 0);
    enqueue(writer, EV_KEY, KEY_POWER, 0);
    enqueue(writer, EV_SYN, SYN_REPORT, 0);
    apply_command(&r, "STATUS");
    apply_command(&r, "DRAIN 42");
    char text[OUTPUT_SIZE + 1];
    output(&r, text);
    const char *status = strstr(text, "\"kind\":\"status\"");
    const char *down = strstr(text, "\"code\":116,\"value\":1,\"action\":\"observed\"");
    const char *up = strstr(text, "\"code\":116,\"value\":0,\"action\":\"observed\"");
    const char *marker = strstr(text, "{\"kind\":\"drain\",\"nonce\":42,\"result\":\"clear\"}");
    assert(status && down && up && marker);
    assert(status < down && down < up && up < marker);
    assert(strstr(marker + 1, "\"kind\":\"key\"") == NULL);
    assert(r.phase == RUN_CONTINUE);
    assert(close(writer) == 0);
    close_readers(&r);
}

static void test_empty_phone_is_clear(void) {
    Runtime r = fresh_runtime();
    int writer = add_pipe(&r, 7, SOURCE_PHONE);
    apply_command(&r, "DRAIN 2147483647");
    char text[OUTPUT_SIZE + 1];
    output(&r, text);
    assert(strcmp(text, "{\"kind\":\"drain\",\"nonce\":2147483647,\"result\":\"clear\"}\n") == 0);
    assert(close(writer) == 0);
    close_readers(&r);
}

static void test_backlog_is_busy_until_next_barrier(void) {
    Runtime r = fresh_runtime();
    int writer = add_pipe(&r, 0, SOURCE_PHONE);
    for (unsigned i = 0; i < 128; ++i) enqueue(writer, EV_SYN, SYN_REPORT, 0);
    enqueue(writer, EV_KEY, KEY_POWER, 1);
    apply_command(&r, "DRAIN 51");
    char text[OUTPUT_SIZE + 1];
    output(&r, text);
    assert(strcmp(text, "{\"kind\":\"drain\",\"nonce\":51,\"result\":\"busy\"}\n") == 0);
    apply_command(&r, "DRAIN 52");
    output(&r, text);
    const char *power = strstr(text, "\"code\":116,\"value\":1");
    const char *marker = strstr(text, "{\"kind\":\"drain\",\"nonce\":52,\"result\":\"clear\"}");
    assert(power && marker && power < marker);
    assert(close(writer) == 0);
    close_readers(&r);
}

static void test_every_phone_is_drained(void) {
    Runtime r = fresh_runtime();
    int first = add_pipe(&r, 0, SOURCE_PHONE);
    int last = add_pipe(&r, MAX_DEVICES - 1, SOURCE_PHONE);
    enqueue(first, EV_KEY, KEY_DOWN, 1);
    enqueue(last, EV_KEY, KEY_POWER, 1);
    apply_command(&r, "DRAIN 60");
    char text[OUTPUT_SIZE + 1];
    output(&r, text);
    const char *navigation = strstr(text, "\"code\":108,\"value\":1");
    const char *power = strstr(text, "\"code\":116,\"value\":1");
    const char *marker = strstr(text, "{\"kind\":\"drain\",\"nonce\":60,\"result\":\"clear\"}");
    assert(navigation && power && marker && navigation < marker && power < marker);
    assert(close(first) == 0);
    assert(close(last) == 0);
    close_readers(&r);
}

static void test_unclassified_source_cannot_supply_barrier(void) {
    Runtime r = fresh_runtime();
    InputSource source = classify_device("virtual-remote", 6, 0x18d1, 0x0101);
    assert(source == SOURCE_OTHER);
    int writer = add_pipe(&r, 0, source);
    enqueue(writer, EV_KEY, KEY_POWER, 1);
    apply_command(&r, "DRAIN 70");
    char text[OUTPUT_SIZE + 1];
    output(&r, text);
    assert(strcmp(text, "{\"kind\":\"drain\",\"nonce\":70,\"result\":\"unavailable\"}\n") == 0);
    struct input_event event;
    assert(read(r.devices[0].fd, &event, sizeof(event)) == (ssize_t)sizeof(event));
    assert(event.code == KEY_POWER);
    assert(close(writer) == 0);
    close_readers(&r);
}

static void test_disconnected_phone_denies_even_with_other_phone(void) {
    Runtime r = fresh_runtime();
    int first = add_pipe(&r, 0, SOURCE_PHONE);
    int second = add_pipe(&r, 1, SOURCE_PHONE);
    enqueue(first, EV_KEY, KEY_POWER, 1);
    assert(close(first) == 0);
    apply_command(&r, "DRAIN 80");
    char text[OUTPUT_SIZE + 1];
    output(&r, text);
    const char *power = strstr(text, "\"code\":116,\"value\":1");
    const char *marker = strstr(text, "{\"kind\":\"drain\",\"nonce\":80,\"result\":\"unavailable\"}");
    assert(power && marker && power < marker);
    assert(r.devices[0].phase == SLOT_EMPTY);
    assert(close(second) == 0);
    close_readers(&r);
}

static void test_hangup_at_batch_limit_is_unavailable(void) {
    Runtime r = fresh_runtime();
    int writer = add_pipe(&r, 0, SOURCE_PHONE);
    for (unsigned i = 0; i < 128; ++i) enqueue(writer, EV_SYN, SYN_REPORT, 0);
    assert(close(writer) == 0);
    apply_command(&r, "DRAIN 90");
    char text[OUTPUT_SIZE + 1];
    output(&r, text);
    assert(strstr(text, "{\"kind\":\"drain\",\"nonce\":90,\"result\":\"unavailable\"}"));
    close_readers(&r);
}

static void test_input_loss_never_emits_clear(void) {
    Runtime r = fresh_runtime();
    int writer = add_pipe(&r, 0, SOURCE_PHONE);
    enqueue(writer, EV_SYN, SYN_DROPPED, 0);
    apply_command(&r, "DRAIN 100");
    char text[OUTPUT_SIZE + 1];
    output(&r, text);
    assert(r.phase == RUN_STOP && r.result == 26);
    assert(strstr(text, "\"operation\":\"input_overflow\""));
    assert(strstr(text, "\"result\":\"clear\"") == NULL);
    assert(close(writer) == 0);
    close_readers(&r);
}

static void test_closed_poll_slot_does_not_consume_controller_stdin(void) {
    Runtime r = fresh_runtime();
    int writer = add_pipe(&r, 0, SOURCE_PHONE);
    assert(close(writer) == 0);
    apply_command(&r, "DRAIN 110");
    assert(r.devices[0].phase == SLOT_EMPTY);
    int commands[2];
    assert(pipe2(commands, O_NONBLOCK | O_CLOEXEC) == 0);
    const char command[] = "STATUS\n";
    assert(write(commands[1], command, sizeof(command) - 1) == (ssize_t)sizeof(command) - 1);
    int saved_stdin = dup(STDIN_FILENO);
    assert(saved_stdin >= 0);
    assert(dup2(commands[0], STDIN_FILENO) >= 0);
    /* Simulate the main loop revisiting a ready slot from before DRAIN. */
    read_device(&r, 0);
    assert(dup2(saved_stdin, STDIN_FILENO) >= 0);
    assert(close(saved_stdin) == 0);
    char remaining[sizeof(command)] = {0};
    assert(read(commands[0], remaining, sizeof(remaining)) == (ssize_t)sizeof(command) - 1);
    assert(strcmp(command, remaining) == 0);
    assert(r.phase == RUN_CONTINUE);
    assert(close(commands[0]) == 0);
    assert(close(commands[1]) == 0);
}

static void test_invalid_nonces_fail_open_without_marker(void) {
    const char *commands[] = {"DRAIN", "DRAIN ", "DRAIN 0", "DRAIN -1", "DRAIN +1",
        "DRAIN 2147483648", "DRAIN 999999999999999999999999999999999", "DRAIN 1 extra",
        "DRAIN 1\"", "DRAIN 1 ", "DRAIN  1"};
    for (size_t i = 0; i < sizeof(commands) / sizeof(commands[0]); ++i) {
        Runtime r = fresh_runtime();
        r.lease.phase = LEASE_ARMED;
        apply_command(&r, commands[i]);
        char text[OUTPUT_SIZE + 1];
        output(&r, text);
        assert(r.lease.phase != LEASE_ARMED);
        assert(strstr(text, "\"reason\":\"invalid_command\""));
        assert(strstr(text, "\"kind\":\"drain\"") == NULL);
    }
}

/* Cross-language reader integration uses the production protocol functions.
 * Keep a phone event queued until a command drains it, reproducing the real
 * scheduling case where stdin and the phone fd are ready in the same batch. */
static int protocol_stream(void) {
    Runtime r = fresh_runtime();
    int writer = add_pipe(&r, 0, classify_device("virtual-remote", 6, 0x18d1, 0x0100));
    r.devices[0].input_id = 7;
    enqueue(writer, EV_KEY, KEY_POWER, 1);
    enqueue(writer, EV_SYN, SYN_REPORT, 0);
    int flags = fcntl(STDIN_FILENO, F_GETFL);
    assert(flags >= 0 && fcntl(STDIN_FILENO, F_SETFL, flags | O_NONBLOCK) == 0);
    flags = fcntl(STDOUT_FILENO, F_GETFL);
    assert(flags >= 0 && fcntl(STDOUT_FILENO, F_SETFL, flags | O_NONBLOCK) == 0);
    emit(&r, "{\"kind\":\"ready\",\"version\":2,\"mode\":\"controlled\",\"pid\":%ld,\"max_lease_ms\":15000}",
         (long)getpid());
    int64_t deadline = clock_ms(CLOCK_MONOTONIC) + 10000;
    while (r.phase == RUN_CONTINUE && clock_ms(CLOCK_MONOTONIC) < deadline) {
        flush_output(&r);
        struct pollfd commands = {STDIN_FILENO, POLLIN, 0};
        int result = poll(&commands, 1, 1000);
        if (result < 0 && errno != EINTR) { r.result = 90; break; }
        if ((commands.revents & (POLLIN | POLLHUP | POLLERR | POLLNVAL)) != 0)
            read_commands(&r);
    }
    if (r.phase != RUN_STOP && r.result == 0) r.result = 91;
    r.phase = RUN_STOP;
    emit(&r, "{\"kind\":\"stopped\",\"result\":%d}", r.result);
    flush_output(&r);
    assert(close(writer) == 0);
    close_readers(&r);
    return r.result;
}

int main(int argc, char **argv) {
    if (argc == 2 && strcmp(argv[1], "--protocol-stream") == 0) return protocol_stream();
    if (argc != 1) return 2;
    test_queued_power_precedes_marker();
    test_empty_phone_is_clear();
    test_backlog_is_busy_until_next_barrier();
    test_every_phone_is_drained();
    test_unclassified_source_cannot_supply_barrier();
    test_disconnected_phone_denies_even_with_other_phone();
    test_hangup_at_batch_limit_is_unavailable();
    test_input_loss_never_emits_clear();
    test_closed_poll_slot_does_not_consume_controller_stdin();
    test_invalid_nonces_fail_open_without_marker();
    puts("PASS: real-pipe DRAIN ordering, bounded backlog, all-phone coverage, disconnect, loss, nonce validation");
    return 0;
}
